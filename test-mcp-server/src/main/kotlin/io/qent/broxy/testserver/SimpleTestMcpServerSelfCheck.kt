package io.qent.broxy.testserver

import io.qent.broxy.core.mcp.McpClient
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.clients.KtorMcpClient
import io.qent.broxy.core.mcp.clients.StdioMcpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val HTTP_PATH = "/mcp"
private const val CLIENT_TIMEOUT_MILLIS = 15_000L
private const val SERVER_START_ATTEMPTS = 100
private const val SERVER_START_DELAY_MILLIS = 100L
private const val SOCKET_CONNECT_TIMEOUT_MILLIS = 100
private const val EPHEMERAL_PORT = 0
private const val READER_JOIN_TIMEOUT_MILLIS = 500L

fun main(args: Array<String>) =
    runBlocking {
        val options = SelfCheckOptions.parse(args)
        SimpleTestMcpServerSelfCheck(options).run()
    }

@Suppress("TooManyFunctions")
class SimpleTestMcpServerSelfCheck(
    private val options: SelfCheckOptions,
) {
    suspend fun run() {
        val serverHome = options.serverHome
        require(Files.isDirectory(serverHome)) {
            "Test MCP server home not found: $serverHome"
        }

        verifyStdio(serverHome)
        if (!options.skipHttp) {
            verifyHttpStreamable(serverHome)
        }
        if (!options.skipSse) {
            verifyHttpSse(serverHome)
        }
        if (!options.skipWs) {
            verifyWebSocket(serverHome)
        }
        println("All SimpleTestMcpServer checks passed")
    }

    private suspend fun verifyStdio(serverHome: Path) {
        println("[SelfCheck] Verifying STDIO mode...")
        val client =
            StdioMcpClient(
                command = serverExecutable(serverHome),
                args = listOf("--mode", "stdio"),
                env = emptyMap(),
            )
        val profile = TestServerProfiles.STDIO
        client.updateTimeouts(CLIENT_TIMEOUT_MILLIS, CLIENT_TIMEOUT_MILLIS)
        try {
            client.connect().getOrThrow("STDIO connect")
            verifyCapabilitiesAndOperations(client, profile)
            println("[SelfCheck] STDIO mode passed")
        } finally {
            client.disconnect()
        }
    }

    private suspend fun verifyHttpStreamable(serverHome: Path) {
        println("[SelfCheck] Verifying HTTP Streamable mode...")
        val port = nextFreePort()
        startHttpServerProcess(serverHome, port, "streamable-http").use { server ->
            waitForHttpServer(port, server)
            val client =
                KtorMcpClient(
                    mode = KtorMcpClient.Mode.StreamableHttp,
                    url = "http://127.0.0.1:$port$HTTP_PATH",
                )
            val profile = TestServerProfiles.STREAMABLE_HTTP
            client.updateTimeouts(CLIENT_TIMEOUT_MILLIS, CLIENT_TIMEOUT_MILLIS)
            try {
                client.connect().getOrThrow("HTTP Streamable connect")
                verifyCapabilitiesAndOperations(client, profile)
                println("[SelfCheck] HTTP Streamable mode passed")
            } finally {
                client.disconnect()
            }
        }
    }

    private suspend fun verifyHttpSse(serverHome: Path) {
        println("[SelfCheck] Verifying HTTP SSE mode...")
        val port = nextFreePort()
        startHttpSseServerProcess(serverHome, port).use { server ->
            waitForHttpServer(port, server)
            val client =
                KtorMcpClient(
                    mode = KtorMcpClient.Mode.Sse,
                    url = "http://127.0.0.1:$port$HTTP_PATH",
                )
            val profile = TestServerProfiles.HTTP_SSE
            client.updateTimeouts(CLIENT_TIMEOUT_MILLIS, CLIENT_TIMEOUT_MILLIS)
            try {
                client.connect().getOrThrow("HTTP SSE connect")
                verifyCapabilitiesAndOperations(client, profile)
                println("[SelfCheck] HTTP SSE mode passed")
            } finally {
                client.disconnect()
            }
        }
    }

    private suspend fun verifyWebSocket(serverHome: Path) {
        println("[SelfCheck] Verifying WebSocket mode...")
        val port = nextFreePort()
        startWebSocketServerProcess(serverHome, port).use { server ->
            waitForHttpServer(port, server)
            val client =
                KtorMcpClient(
                    mode = KtorMcpClient.Mode.WebSocket,
                    url = "ws://127.0.0.1:$port$HTTP_PATH",
                )
            val profile = TestServerProfiles.WS
            client.updateTimeouts(CLIENT_TIMEOUT_MILLIS, CLIENT_TIMEOUT_MILLIS)
            try {
                client.connect().getOrThrow("WebSocket connect")
                verifyCapabilitiesAndOperations(client, profile)
                println("[SelfCheck] WebSocket mode passed")
            } finally {
                client.disconnect()
            }
        }
    }

    private suspend fun verifyCapabilitiesAndOperations(
        client: McpClient,
        profile: ModeProfile,
    ) {
        val caps = client.fetchCapabilities().getOrThrow("fetch capabilities")
        assertCapabilities(caps, profile)
        verifyToolCalls(client, profile)
        verifyPrompts(client, profile)
        verifyResources(client, profile)
    }

    private fun assertCapabilities(
        caps: ServerCapabilities,
        profile: ModeProfile,
    ) {
        require(caps.tools.map { it.name }.toSet() == setOf(profile.toolName)) {
            "Tool capabilities mismatch: ${caps.tools}"
        }
        require(
            caps.resources.map { it.uri ?: it.name }.toSet() == setOf(profile.resourceUri),
        ) {
            "Resource capabilities mismatch: ${caps.resources}"
        }
        require(caps.prompts.map { it.name }.toSet() == setOf(profile.promptName)) {
            "Prompt capabilities mismatch: ${caps.prompts}"
        }
    }

    private suspend fun verifyToolCalls(
        client: McpClient,
        profile: ModeProfile,
    ) {
        val args = TestServerProfiles.TOOL_TEST_ARGS
        val payload =
            client.callTool(profile.toolName, arithmeticArgs(args)).getOrThrow("tool ${profile.toolName}")
                .jsonObject
        val expectedResult = profile.toolOperation.apply(args.a, args.b)
        assertStructuredResult(payload, profile.toolOperation.label, expectedResult)
    }

    private suspend fun verifyPrompts(
        client: McpClient,
        profile: ModeProfile,
    ) {
        val name = "Tester"
        val response =
            client.getPrompt(profile.promptName, mapOf(TestServerProfiles.PROMPT_ARGUMENT_NAME to name))
                .getOrThrow("prompt ${profile.promptName}")
        assertPromptContains(response, "${profile.promptPrefix} $name!")
    }

    private suspend fun verifyResources(
        client: McpClient,
        profile: ModeProfile,
    ) {
        val payload = client.readResource(profile.resourceUri).getOrThrow("resource ${profile.resourceUri}")
        assertResourceContents(payload, profile.resourceText)
    }

    private fun arithmeticArgs(args: ToolTestArgs): JsonObject =
        buildJsonObject {
            put("a", JsonPrimitive(args.a))
            put("b", JsonPrimitive(args.b))
        }

    private fun assertStructuredResult(
        payload: JsonObject,
        expectedOperation: String,
        expectedResult: Double,
    ) {
        val structured =
            payload["structuredContent"]?.jsonObject
                ?: error("structuredContent block is missing: $payload")
        val resultValue =
            structured["result"]?.jsonPrimitive?.doubleOrNull
                ?: error("Tool result missing numeric value: $payload")
        require(structured["operation"]?.jsonPrimitive?.content == expectedOperation) {
            "Unexpected operation: $payload"
        }
        require(resultValue == expectedResult) { "Unexpected result: $payload" }
        val isError = payload["isError"]?.jsonPrimitive?.booleanOrNull ?: false
        require(!isError) { "Tool call should not report errors: $payload" }
    }

    private fun assertPromptContains(
        prompt: JsonObject,
        expectedText: String,
    ) {
        val messages = prompt["messages"]?.jsonArray ?: error("Prompt payload missing messages: $prompt")
        val first = messages.firstOrNull()?.jsonObject ?: error("Prompt payload has empty messages: $prompt")
        val contentElement = first["content"] ?: error("Prompt message missing content: $prompt")
        val text =
            when (contentElement) {
                is JsonArray -> contentElement.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
                is JsonObject -> contentElement["text"]?.jsonPrimitive?.content
                else -> null
            } ?: error("Prompt text missing: $prompt")
        require(text.contains(expectedText)) { "Prompt text should contain '$expectedText' but was '$text'" }
    }

    private fun assertResourceContents(
        payload: JsonObject,
        expectedText: String,
    ) {
        val contents = payload["contents"]?.jsonArray ?: error("Resource payload missing contents: $payload")
        val first = contents.firstOrNull()?.jsonObject ?: error("Resource contents missing text entry: $payload")
        val text = first["text"]?.jsonPrimitive?.content ?: error("Resource text missing: $payload")
        require(text == expectedText) { "Unexpected resource text: $text" }
    }

    private fun <T> Result<T>.getOrThrow(operation: String): T =
        getOrElse { error ->
            throw IllegalStateException(
                "$operation failed: ${error.message ?: error::class.simpleName}",
                error,
            )
        }

    private fun serverExecutable(serverHome: Path): String {
        val scriptName = if (isWindows()) "test-mcp-server.bat" else "test-mcp-server"
        return serverHome.resolve("bin").resolve(scriptName).toAbsolutePath().toString()
    }

    private fun startHttpServerProcess(
        serverHome: Path,
        port: Int,
        mode: String,
    ): ManagedProcess {
        val command =
            listOf(
                serverExecutable(serverHome),
                "--mode",
                mode,
                "--host",
                "127.0.0.1",
                "--port",
                port.toString(),
                "--path",
                HTTP_PATH,
            )
        val process =
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
        return ManagedProcess(process)
    }

    private fun startHttpSseServerProcess(
        serverHome: Path,
        port: Int,
    ): ManagedProcess = startHttpServerProcess(serverHome, port, "http-sse")

    private fun startWebSocketServerProcess(
        serverHome: Path,
        port: Int,
    ): ManagedProcess = startHttpServerProcess(serverHome, port, "ws")

    private suspend fun waitForHttpServer(
        port: Int,
        process: ManagedProcess,
    ) {
        repeat(SERVER_START_ATTEMPTS) {
            if (!process.isAlive()) {
                error("Test server exited early: ${process.logs()}")
            }
            if (isPortOpen(port)) return
            delay(SERVER_START_DELAY_MILLIS)
        }
        error("Test server failed to start on port $port\n${process.logs()}")
    }

    private fun isPortOpen(port: Int): Boolean =
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), SOCKET_CONNECT_TIMEOUT_MILLIS)
            }
        }.isSuccess

    private fun nextFreePort(): Int = ServerSocket(EPHEMERAL_PORT).use { it.localPort }
}

private class ManagedProcess(
    private val process: Process,
) : AutoCloseable {
    private val collector = ProcessOutputCollector(process)

    fun logs(): String = collector.snapshot()

    fun isAlive(): Boolean = process.isAlive

    override fun close() {
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly()
        }
        collector.close()
    }
}

private class ProcessOutputCollector(process: Process) : AutoCloseable {
    private val lines = mutableListOf<String>()
    private val readerThread =
        thread(name = "simple-test-mcp-server-self-check") {
            process.inputStream.bufferedReader().useLines { seq ->
                seq.forEach { line ->
                    synchronized(lines) { lines.add(line) }
                }
            }
        }

    fun snapshot(): String =
        synchronized(lines) {
            if (lines.isEmpty()) "[no output captured]" else lines.joinToString("\n")
        }

    override fun close() {
        readerThread.interrupt()
        runCatching { readerThread.join(READER_JOIN_TIMEOUT_MILLIS) }
    }
}

private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")

data class SelfCheckOptions(
    val serverHome: Path,
    val skipHttp: Boolean,
    val skipSse: Boolean,
    val skipWs: Boolean,
) {
    companion object {
        fun parse(args: Array<String>): SelfCheckOptions {
            var serverHome: Path? = System.getProperty("test.mcpServerHome")?.let { Paths.get(it) }
            var skipHttp = false
            var skipSse = false
            var skipWs = false
            var index = 0
            while (index < args.size) {
                when (val arg = args[index]) {
                    "--server-home" ->
                        serverHome =
                            Paths.get(args.getOrNull(++index) ?: error("Missing value for --server-home"))

                    "--skip-http" -> skipHttp = true
                    "--skip-sse" -> skipSse = true
                    "--skip-ws" -> skipWs = true
                    else -> error("Unknown argument '$arg'")
                }
                index++
            }
            val resolvedHome = serverHome ?: Paths.get("build/install/test-mcp-server").toAbsolutePath()
            return SelfCheckOptions(
                serverHome = resolvedHome,
                skipHttp = skipHttp,
                skipSse = skipSse,
                skipWs = skipWs,
            )
        }
    }
}
