package io.qent.broxy.cli.support

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.qent.broxy.agents.AgentFileSystemAccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.io.path.pathString

internal const val AGENT_RUN_TEST_SERVER_ID = BroxyCliIntegrationConfig.HTTP_SERVER_ID
internal const val AGENT_RUN_TEST_TOOL_NAME = BroxyCliIntegrationConfig.SUBTRACT_TOOL_NAME
internal const val AGENT_RUN_TEST_PREFIXED_TOOL = "${AGENT_RUN_TEST_SERVER_ID}_${AGENT_RUN_TEST_TOOL_NAME}"
internal const val AGENT_RUN_FILESYSTEM_SERVER_ID = "filesystem"
internal const val AGENT_RUN_UNKNOWN_SERVER_ID = "unknown"
internal const val AGENT_RUN_FS_READ_TOOL_NAME = "fsRead"
internal const val AGENT_RUN_WORKSPACE_SEED_FILE = "seed.txt"
internal const val AGENT_RUN_WORKSPACE_SEED_TEXT = "seed line 1\nseed line 2\n"

internal const val AGENT_RUN_SCENARIO_NO_CAPS_FS_NONE_TEXT = "no-caps-fs-none-text"
internal const val AGENT_RUN_SCENARIO_WITH_CAPS_FS_NONE_DOWNSTREAM_ALLOWED = "with-caps-fs-none-downstream-allowed"
internal const val AGENT_RUN_SCENARIO_NO_CAPS_FS_NONE_DOWNSTREAM_BLOCKED = "no-caps-fs-none-downstream-blocked"
internal const val AGENT_RUN_SCENARIO_NO_CAPS_FS_NONE_FS_BLOCKED = "no-caps-fs-none-fs-blocked"
internal const val AGENT_RUN_SCENARIO_NO_CAPS_FS_READ_ONLY_FS_ALLOWED = "no-caps-fs-read-only-fs-allowed"
internal const val AGENT_RUN_SCENARIO_WITH_CAPS_FS_READ_ONLY_MIXED_SEQUENCE = "with-caps-fs-read-only-mixed-sequence"

internal const val AGENT_RUN_NO_CAPS_FS_NONE_TEXT_RESPONSE = "no-caps-fs-none-text-ok"
internal const val AGENT_RUN_WITH_CAPS_FS_NONE_DOWNSTREAM_ALLOWED_RESPONSE = "with-caps-fs-none-downstream-allowed-ok"
internal const val AGENT_RUN_NO_CAPS_FS_NONE_DOWNSTREAM_BLOCKED_RESPONSE = "no-caps-fs-none-downstream-blocked-ok"
internal const val AGENT_RUN_NO_CAPS_FS_NONE_FS_BLOCKED_RESPONSE = "no-caps-fs-none-fs-blocked-ok"
internal const val AGENT_RUN_NO_CAPS_FS_READ_ONLY_FS_ALLOWED_RESPONSE = "no-caps-fs-read-only-fs-allowed-ok"
internal const val AGENT_RUN_WITH_CAPS_FS_READ_ONLY_MIXED_SEQUENCE_RESPONSE =
    "with-caps-fs-read-only-mixed-sequence-ok"

internal enum class AgentCapabilityProfile {
    WITH_DOWNSTREAM_TOOL,
    WITHOUT_CAPABILITIES,
}

internal data class AgentRunInputProfile(
    val capabilityProfile: AgentCapabilityProfile = AgentCapabilityProfile.WITH_DOWNSTREAM_TOOL,
    val fileSystemAccess: AgentFileSystemAccess = AgentFileSystemAccess.NONE,
)

internal data class AgentRunInputFiles(
    val mcpConfig: Path,
    val agentConfig: Path,
    val agentSettings: Path,
    val agentSecrets: Path,
    val stateDir: Path,
)

internal data class AgentRunProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

internal class OpenAiAgentTestBackend(
    private val host: String = BroxyCliIntegrationConfig.TEST_SERVER_HTTP_HOST,
) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val server = HttpServer.create(InetSocketAddress(host, 0), 0)

    init {
        server.createContext("/") { exchange ->
            handleRequest(exchange)
        }
        server.start()
    }

    val port: Int
        get() = server.address.port

    fun baseUrlForScenario(scenario: String): String = "http://$host:$port/v1/$scenario"

    override fun close() {
        server.stop(0)
    }

    private fun handleRequest(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") {
            respond(exchange, 405, """{"error":"method_not_allowed"}""")
            return
        }
        val requestBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        val requestJson = json.parseToJsonElement(requestBody).jsonObject
        val toolMessages = collectToolMessages(requestJson)
        val query = parseQuery(exchange.requestURI)
        val scenario = resolveScenario(exchange.requestURI, query)
        val primaryTool = query["tool"]?.takeIf { it.isNotBlank() } ?: AGENT_RUN_TEST_PREFIXED_TOOL

        val payload =
            when (scenario) {
                AGENT_RUN_SCENARIO_NO_CAPS_FS_NONE_TEXT ->
                    textResponse(modelName(requestJson), AGENT_RUN_NO_CAPS_FS_NONE_TEXT_RESPONSE)

                AGENT_RUN_SCENARIO_WITH_CAPS_FS_NONE_DOWNSTREAM_ALLOWED ->
                    if (toolMessages.isEmpty()) {
                        toolCallResponse(
                            modelName(requestJson),
                            primaryTool,
                            """{"a":8,"b":2}""",
                            "call-downstream-allowed-1",
                        )
                    } else if (isDownstreamToolSuccess(toolMessages.last())) {
                        textResponse(
                            modelName(requestJson),
                            AGENT_RUN_WITH_CAPS_FS_NONE_DOWNSTREAM_ALLOWED_RESPONSE,
                        )
                    } else {
                        textResponse(modelName(requestJson), unexpectedResponse(scenario))
                    }

                AGENT_RUN_SCENARIO_NO_CAPS_FS_NONE_DOWNSTREAM_BLOCKED ->
                    if (toolMessages.isEmpty()) {
                        toolCallResponse(
                            modelName(requestJson),
                            primaryTool,
                            """{"a":8,"b":2}""",
                            "call-downstream-blocked-1",
                        )
                    } else if (isToolBlocked(toolMessages.last(), primaryTool)) {
                        textResponse(
                            modelName(requestJson),
                            AGENT_RUN_NO_CAPS_FS_NONE_DOWNSTREAM_BLOCKED_RESPONSE,
                        )
                    } else {
                        textResponse(modelName(requestJson), unexpectedResponse(scenario))
                    }

                AGENT_RUN_SCENARIO_NO_CAPS_FS_NONE_FS_BLOCKED ->
                    if (toolMessages.isEmpty()) {
                        toolCallResponse(
                            modelName(requestJson),
                            AGENT_RUN_FS_READ_TOOL_NAME,
                            fsReadArguments(),
                            "call-fs-blocked-1",
                        )
                    } else if (isToolBlocked(toolMessages.last(), AGENT_RUN_FS_READ_TOOL_NAME)) {
                        textResponse(
                            modelName(requestJson),
                            AGENT_RUN_NO_CAPS_FS_NONE_FS_BLOCKED_RESPONSE,
                        )
                    } else {
                        textResponse(modelName(requestJson), unexpectedResponse(scenario))
                    }

                AGENT_RUN_SCENARIO_NO_CAPS_FS_READ_ONLY_FS_ALLOWED ->
                    if (toolMessages.isEmpty()) {
                        toolCallResponse(
                            modelName(requestJson),
                            AGENT_RUN_FS_READ_TOOL_NAME,
                            fsReadArguments(),
                            "call-fs-allowed-1",
                        )
                    } else if (isFsReadSuccess(toolMessages.last())) {
                        textResponse(
                            modelName(requestJson),
                            AGENT_RUN_NO_CAPS_FS_READ_ONLY_FS_ALLOWED_RESPONSE,
                        )
                    } else {
                        textResponse(modelName(requestJson), unexpectedResponse(scenario))
                    }

                AGENT_RUN_SCENARIO_WITH_CAPS_FS_READ_ONLY_MIXED_SEQUENCE ->
                    when (toolMessages.size) {
                        0 ->
                            toolCallResponse(
                                modelName(requestJson),
                                primaryTool,
                                """{"a":8,"b":2}""",
                                "call-mixed-1",
                            )

                        1 ->
                            if (isDownstreamToolSuccess(toolMessages.first())) {
                                toolCallResponse(
                                    modelName(requestJson),
                                    AGENT_RUN_FS_READ_TOOL_NAME,
                                    fsReadArguments(),
                                    "call-mixed-2",
                                )
                            } else {
                                textResponse(modelName(requestJson), unexpectedResponse(scenario))
                            }

                        else ->
                            if (isDownstreamToolSuccess(toolMessages.first()) && isFsReadSuccess(toolMessages.last())) {
                                textResponse(
                                    modelName(requestJson),
                                    AGENT_RUN_WITH_CAPS_FS_READ_ONLY_MIXED_SEQUENCE_RESPONSE,
                                )
                            } else {
                                textResponse(modelName(requestJson), unexpectedResponse(scenario))
                            }
                    }

                else -> textResponse(modelName(requestJson), AGENT_RUN_NO_CAPS_FS_NONE_TEXT_RESPONSE)
            }

        respond(exchange, 200, payload.toString())
    }

    private fun collectToolMessages(request: JsonObject): List<String> =
        request["messages"]
            ?.jsonArray
            ?.mapNotNull { message ->
                val value = message.jsonObject
                if (value["role"]?.jsonPrimitive?.content != "tool") {
                    return@mapNotNull null
                }
                val content = value["content"] ?: return@mapNotNull ""
                if (content is JsonPrimitive) {
                    content.contentOrNull.orEmpty()
                } else {
                    content.toString()
                }
            }.orEmpty()

    private fun isDownstreamToolSuccess(payload: String): Boolean = payload.contains("\"operation\":\"subtraction\"")

    private fun isFsReadSuccess(payload: String): Boolean = payload.contains("\"ok\":true") && payload.contains("\"lines\"")

    private fun isToolBlocked(
        payload: String,
        toolName: String,
    ): Boolean = payload.contains("not allowed by current preset") && payload.contains(toolName)

    private fun unexpectedResponse(scenario: String): String = "unexpected-$scenario"

    private fun fsReadArguments(): String =
        """
        {"filePath":"$AGENT_RUN_WORKSPACE_SEED_FILE","mode":"head","lineCount":1,"includeLineNumbers":false}
        """.trimIndent()

    private fun modelName(request: JsonObject): String =
        request["model"]
            ?.jsonPrimitive
            ?.content
            .orEmpty()
            .ifBlank { "test-model" }

    private fun textResponse(
        model: String,
        text: String,
    ): JsonObject =
        buildJsonObject {
            put("id", JsonPrimitive("chatcmpl-test"))
            put("object", JsonPrimitive("chat.completion"))
            put("created", JsonPrimitive(Instant.now().epochSecond))
            put("model", JsonPrimitive(model))
            put(
                "choices",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("index", JsonPrimitive(0))
                            put(
                                "message",
                                buildJsonObject {
                                    put("role", JsonPrimitive("assistant"))
                                    put("content", JsonPrimitive(text))
                                },
                            )
                            put("finish_reason", JsonPrimitive("stop"))
                        },
                    )
                },
            )
            put("usage", usage())
        }

    private fun toolCallResponse(
        model: String,
        toolName: String,
        arguments: String,
        callId: String,
    ): JsonObject =
        buildJsonObject {
            put("id", JsonPrimitive("chatcmpl-test-tool"))
            put("object", JsonPrimitive("chat.completion"))
            put("created", JsonPrimitive(Instant.now().epochSecond))
            put("model", JsonPrimitive(model))
            put(
                "choices",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("index", JsonPrimitive(0))
                            put(
                                "message",
                                buildJsonObject {
                                    put("role", JsonPrimitive("assistant"))
                                    put("content", JsonNull)
                                    put(
                                        "tool_calls",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("id", JsonPrimitive(callId))
                                                    put("type", JsonPrimitive("function"))
                                                    put(
                                                        "function",
                                                        buildJsonObject {
                                                            put("name", JsonPrimitive(toolName))
                                                            put("arguments", JsonPrimitive(arguments))
                                                        },
                                                    )
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                            put("finish_reason", JsonPrimitive("tool_calls"))
                        },
                    )
                },
            )
            put("usage", usage())
        }

    private fun usage(): JsonObject =
        buildJsonObject {
            put("prompt_tokens", JsonPrimitive(0))
            put("completion_tokens", JsonPrimitive(0))
            put("total_tokens", JsonPrimitive(0))
        }

    private fun parseQuery(uri: URI): Map<String, String> {
        val raw = uri.rawQuery ?: return emptyMap()
        return raw
            .split("&")
            .mapNotNull { part ->
                val tokens = part.split("=", limit = 2)
                val key = tokens.firstOrNull()?.trim().orEmpty()
                if (key.isBlank()) {
                    null
                } else {
                    key to (tokens.getOrNull(1)?.trim().orEmpty())
                }
            }.toMap()
    }

    private fun resolveScenario(
        uri: URI,
        query: Map<String, String>,
    ): String {
        query["scenario"]?.let { candidate ->
            if (candidate in supportedScenarios) {
                return candidate
            }
        }
        val segment =
            uri.path
                .split("/")
                .firstOrNull { it in supportedScenarios }
        return segment ?: AGENT_RUN_SCENARIO_NO_CAPS_FS_NONE_TEXT
    }

    private fun respond(
        exchange: HttpExchange,
        code: Int,
        payload: String,
    ) {
        val body = payload.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(code, body.size.toLong())
        exchange.responseBody.use { output ->
            output.write(body)
        }
    }

    private companion object {
        private val supportedScenarios =
            setOf(
                AGENT_RUN_SCENARIO_NO_CAPS_FS_NONE_TEXT,
                AGENT_RUN_SCENARIO_WITH_CAPS_FS_NONE_DOWNSTREAM_ALLOWED,
                AGENT_RUN_SCENARIO_NO_CAPS_FS_NONE_DOWNSTREAM_BLOCKED,
                AGENT_RUN_SCENARIO_NO_CAPS_FS_NONE_FS_BLOCKED,
                AGENT_RUN_SCENARIO_NO_CAPS_FS_READ_ONLY_FS_ALLOWED,
                AGENT_RUN_SCENARIO_WITH_CAPS_FS_READ_ONLY_MIXED_SEQUENCE,
            )
    }
}

internal fun writeAgentRunInputFiles(
    root: Path,
    downstreamUrl: String,
    llmBaseUrl: String,
    profile: AgentRunInputProfile = AgentRunInputProfile(),
): AgentRunInputFiles {
    val stateDir = root.resolve("state")
    val workspaceDir = root.resolve("workspace")
    Files.createDirectories(stateDir)
    Files.createDirectories(workspaceDir)
    if (profile.fileSystemAccess != AgentFileSystemAccess.NONE) {
        Files.writeString(workspaceDir.resolve(AGENT_RUN_WORKSPACE_SEED_FILE), AGENT_RUN_WORKSPACE_SEED_TEXT)
    }
    val mcpFile = root.resolve("mcp_agent.json")
    val agentId = "agent-cli-it"
    val agentFile = root.resolve("$agentId.md")
    val settingsFile = root.resolve("agents_settings.json")
    val secretsFile = root.resolve("agents_secrets.json")
    val metadataDir = root.resolve("metadata")
    Files.createDirectories(metadataDir)

    Files.writeString(
        mcpFile,
        """
        {
          "requestTimeoutSeconds": 60,
          "capabilitiesTimeoutSeconds": 30,
          "mcpServers": {
            "$AGENT_RUN_TEST_SERVER_ID": {
              "name": "Test Streamable HTTP",
              "enabled": true,
              "transport": "http",
              "url": "$downstreamUrl"
            }
          }
        }
        """.trimIndent(),
    )

    Files.writeString(
        agentFile,
        """
        ---
        name: Agent CLI IT
        description: Integration test agent for CLI markdown config.
        ---
        Use tools when needed and return concise answers.
        """.trimIndent(),
    )

    Files.writeString(
        metadataDir.resolve("agent_$agentId.json"),
        """
        {
          "tools": ${buildAgentToolsJson(profile.capabilityProfile)},
          "manualLaunchDefaults": {
            "prompt": "default prompt",
            "runtime": "LANGCHAIN",
            "llm": {
              "provider": "OPENAI",
              "model": "gpt-4o-mini",
              "temperature": 0.0
            },
            "fileSystem": {
              "path": "${workspaceDir.pathString}",
              "access": "${profile.fileSystemAccess.name}"
            }
          }
        }
        """.trimIndent(),
    )

    Files.writeString(
        settingsFile,
        """
        {
          "openAi": {
            "baseUrl": "$llmBaseUrl"
          }
        }
        """.trimIndent(),
    )

    Files.writeString(
        secretsFile,
        """
        {
          "values": {
            "openai_api_key": "test-openai-key"
          }
        }
        """.trimIndent(),
    )

    return AgentRunInputFiles(
        mcpConfig = mcpFile,
        agentConfig = agentFile,
        agentSettings = settingsFile,
        agentSecrets = secretsFile,
        stateDir = stateDir,
    )
}

private fun buildAgentToolsJson(capabilityProfile: AgentCapabilityProfile): String =
    when (capabilityProfile) {
        AgentCapabilityProfile.WITH_DOWNSTREAM_TOOL ->
            """
            [
              {
                "serverId": "$AGENT_RUN_TEST_SERVER_ID",
                "toolName": "$AGENT_RUN_TEST_TOOL_NAME",
                "enabled": true
              }
            ]
            """.trimIndent()

        AgentCapabilityProfile.WITHOUT_CAPABILITIES -> "[]"
    }

internal fun runAgentCliCommand(command: List<String>): AgentRunProcessResult {
    val process = ProcessBuilder(command).start()
    val finished = process.waitFor(90, TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
    }
    val rawStdout =
        process.inputStream
            .bufferedReader()
            .readText()
            .trim()
    val stdout = extractJsonPayload(rawStdout)
    val stderr =
        process.errorStream
            .bufferedReader()
            .readText()
            .trim()
    return AgentRunProcessResult(
        exitCode = if (finished) process.exitValue() else -1,
        stdout = stdout,
        stderr = stderr,
    )
}

private fun extractJsonPayload(rawStdout: String): String {
    val trimmed = rawStdout.trim()
    if (trimmed.isEmpty()) return trimmed
    if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed
    return trimmed
        .lineSequence()
        .map { it.trim() }
        .lastOrNull { it.startsWith("{") && it.endsWith("}") }
        ?: trimmed
}

internal fun buildAgentRunCommand(input: AgentRunInputFiles): List<String> =
    buildList {
        add(resolveJavaExecutable())
        add("-jar")
        add(BroxyCliIntegrationFiles.jarPath().pathString)
        add("agent")
        add("run")
        add("--mcp-config")
        add(input.mcpConfig.pathString)
        add("--agent-config")
        add(input.agentConfig.pathString)
        add("--agent-settings")
        add(input.agentSettings.pathString)
        add("--agents-secrets")
        add(input.agentSecrets.pathString)
        add("--state-dir")
        add(input.stateDir.pathString)
        add("--runtime")
        add("langchain")
        add("--output")
        add("json")
        add("--timeout-seconds")
        add("60")
        add("--log-level")
        add("warn")
        add("--prompt")
        add("integration prompt")
    }

private fun resolveJavaExecutable(): String {
    val javaHome = System.getProperty("java.home").orEmpty()
    if (javaHome.isBlank()) {
        return "java"
    }
    val executable = if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
    val candidate = Path.of(javaHome, "bin", executable)
    return if (Files.isRegularFile(candidate)) candidate.pathString else "java"
}
