package io.qent.broxy.agents.runtime.models

import io.qent.broxy.agents.DEFAULT_CODEX_COMMAND
import io.qent.broxy.core.utils.CommandLocator
import io.qent.broxy.core.utils.UserPathResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val CODEX_MODEL_LIST_LIMIT = 100
private const val CODEX_APP_SERVER_RESPONSE_TIMEOUT_MILLIS = 15_000L
private const val CODEX_PROCESS_SHUTDOWN_TIMEOUT_SECONDS = 2L
private const val CODEX_PROCESS_FORCE_SHUTDOWN_TIMEOUT_SECONDS = 2L
private const val CODEX_IO_THREAD_JOIN_TIMEOUT_MILLIS = 2_000L

interface CodexModelCatalog {
    suspend fun listModels(command: String): Result<List<String>>
}

@Suppress("TooManyFunctions")
class CodexCliModelCatalog(
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
        },
) : CodexModelCatalog {
    override suspend fun listModels(command: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                listModelsBlocking(command)
            }
        }

    @Suppress("LongMethod", "NestedBlockDepth")
    private fun listModelsBlocking(command: String): List<String> {
        val resolvedUserPath = UserPathResolver.resolve()
        val resolvedCommand = resolveCommand(command, resolvedUserPath)
        val environment = buildCodexEnvironment(resolvedUserPath)
        val processBuilder = ProcessBuilder(listOf(resolvedCommand, "app-server"))
        val workingDirectory =
            System
                .getProperty("user.home")
                ?.trim()
                .orEmpty()
                .ifBlank { "." }
        processBuilder.directory(File(workingDirectory))
        processBuilder.applyEnvironment(environment)

        val process = processBuilder.start()
        val stdoutQueue = LinkedBlockingQueue<String>()
        val stderr = StringBuilder()
        val stdoutThread =
            thread(start = true, isDaemon = true, name = "codex-models-stdout") {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        stdoutQueue.offer(line)
                    }
                }
            }
        val stderrThread =
            thread(start = true, isDaemon = true, name = "codex-models-stderr") {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(stderr) {
                            if (stderr.isNotEmpty()) {
                                stderr.append('\n')
                            }
                            stderr.append(line)
                        }
                    }
                }
            }

        return try {
            process.outputStream.bufferedWriter().use { writer ->
                var requestId = 1
                sendRequest(
                    writer = writer,
                    requestId = requestId,
                    method = "initialize",
                    params =
                        buildJsonObject {
                            put("protocolVersion", JsonPrimitive("1"))
                            put(
                                "clientInfo",
                                buildJsonObject {
                                    put("name", JsonPrimitive("broxy"))
                                    put("version", JsonPrimitive("1.0.0"))
                                },
                            )
                            put("capabilities", buildJsonObject {})
                        },
                )
                awaitResult(
                    stdoutQueue = stdoutQueue,
                    process = process,
                    requestId = requestId,
                    stderr = stderr,
                )

                sendNotification(
                    writer = writer,
                    method = "initialized",
                    params = buildJsonObject {},
                )

                val collected = mutableListOf<String>()
                var cursor: String? = null
                while (true) {
                    requestId += 1
                    sendRequest(
                        writer = writer,
                        requestId = requestId,
                        method = "model/list",
                        params = modelListParams(cursor),
                    )
                    val result =
                        awaitResult(
                            stdoutQueue = stdoutQueue,
                            process = process,
                            requestId = requestId,
                            stderr = stderr,
                        )
                    collected += extractModels(result)
                    cursor = extractNextCursor(result)
                    if (cursor == null) {
                        break
                    }
                }
                sanitizeModelList(collected)
            }
        } finally {
            runCatching { process.outputStream.close() }
            val finished = process.waitFor(CODEX_PROCESS_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                runCatching { process.destroyForcibly() }
                runCatching { process.waitFor(CODEX_PROCESS_FORCE_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            }
            stdoutThread.join(CODEX_IO_THREAD_JOIN_TIMEOUT_MILLIS)
            stderrThread.join(CODEX_IO_THREAD_JOIN_TIMEOUT_MILLIS)
        }
    }

    private fun resolveCommand(
        configuredCommand: String,
        resolvedUserPath: String?,
    ): String {
        val normalized = configuredCommand.trim().ifBlank { DEFAULT_CODEX_COMMAND }
        return CommandLocator.resolveCommand(command = normalized, pathOverride = resolvedUserPath)
            ?: error(
                "Codex command '$normalized' was not found in PATH. " +
                    "Set an absolute path in Agent Settings -> Codex command.",
            )
    }

    private fun buildCodexEnvironment(resolvedUserPath: String?): MutableMap<String, String> {
        val environment = ProcessBuilder().environment()
        if (!resolvedUserPath.isNullOrBlank()) {
            val pathKey = UserPathResolver.resolvePathKey(environment)
            environment[pathKey] = resolvedUserPath
        }
        val userHome = System.getProperty("user.home")?.trim().orEmpty()
        if (userHome.isNotBlank()) {
            environment.putKeyIgnoreCase("HOME", userHome)
            environment.putKeyIgnoreCase("CODEX_HOME", File(userHome, ".codex").absolutePath)
        }
        return environment
    }

    private fun sendRequest(
        writer: BufferedWriter,
        requestId: Int,
        method: String,
        params: JsonObject,
    ) {
        val payload =
            buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("id", JsonPrimitive(requestId))
                put("method", JsonPrimitive(method))
                put("params", params)
            }
        writer.appendLine(payload.toString())
        writer.flush()
    }

    private fun sendNotification(
        writer: BufferedWriter,
        method: String,
        params: JsonObject,
    ) {
        val payload =
            buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("method", JsonPrimitive(method))
                put("params", params)
            }
        writer.appendLine(payload.toString())
        writer.flush()
    }

    private fun modelListParams(cursor: String?): JsonObject =
        buildJsonObject {
            put("limit", JsonPrimitive(CODEX_MODEL_LIST_LIMIT))
            put("includeHidden", JsonPrimitive(false))
            cursor?.let { put("cursor", JsonPrimitive(it)) }
        }

    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
    private fun awaitResult(
        stdoutQueue: LinkedBlockingQueue<String>,
        process: Process,
        requestId: Int,
        stderr: StringBuilder,
    ): JsonObject {
        val deadline = System.currentTimeMillis() + CODEX_APP_SERVER_RESPONSE_TIMEOUT_MILLIS
        while (true) {
            val remainingMillis = deadline - System.currentTimeMillis()
            if (remainingMillis <= 0L) {
                val stderrOutput = synchronized(stderr) { stderr.toString().trim() }
                val exitCode = runCatching { process.exitValue() }.getOrNull()
                val suffix =
                    buildString {
                        exitCode?.let { append(" (exit $it)") }
                        if (stderrOutput.isNotBlank()) {
                            append(": ")
                            append(stderrOutput)
                        }
                    }
                error("Timed out waiting for Codex app-server response $requestId$suffix")
            }

            val line =
                try {
                    stdoutQueue.poll(remainingMillis, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    error("Interrupted while waiting for Codex app-server response $requestId")
                }
            if (line == null) {
                continue
            }

            val payload = parseJsonObject(line) ?: continue
            val id = payload["id"]?.jsonPrimitive?.contentOrNull ?: continue
            if (id != requestId.toString()) {
                continue
            }

            val errorPayload = payload["error"] as? JsonObject
            if (errorPayload != null) {
                val message = errorPayload["message"]?.jsonPrimitive?.contentOrNull ?: errorPayload.toString()
                error("Codex app-server error for request $requestId: $message")
            }

            return payload["result"] as? JsonObject
                ?: error("Codex app-server response $requestId does not include a result object")
        }
    }

    private fun extractModels(result: JsonObject): List<String> {
        val models = mutableListOf<String>()
        val data = result["data"] as? JsonArray ?: return models
        data.forEach { element ->
            val item = element as? JsonObject ?: return@forEach
            val hidden = item["hidden"]?.jsonPrimitive?.booleanOrNull ?: false
            if (hidden) {
                return@forEach
            }
            val modelId =
                item["id"]?.jsonPrimitive?.contentOrNull
                    ?: item["model"]?.jsonPrimitive?.contentOrNull
                    ?: return@forEach
            models += modelId
        }
        return models
    }

    private fun extractNextCursor(result: JsonObject): String? =
        result["nextCursor"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: result["next_cursor"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun parseJsonObject(line: String): JsonObject? {
        val parsed = runCatching { json.parseToJsonElement(line) }.getOrNull()
        return parsed as? JsonObject
    }
}

private fun sanitizeModelList(raw: List<String>): List<String> =
    raw
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()

private fun MutableMap<String, String>.putKeyIgnoreCase(
    key: String,
    value: String,
) {
    val existing = keys.firstOrNull { it.equals(key, ignoreCase = true) }
    if (existing == null) {
        put(key, value)
    } else {
        put(existing, value)
    }
}

private fun ProcessBuilder.applyEnvironment(environment: Map<String, String>) {
    val target = environment()
    target.clear()
    target.putAll(environment)
}
