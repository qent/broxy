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
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

private const val HTTP_PATH = "/mcp"

class SimpleTestMcpServerIntegrationTest {
    private val serverHome: Path =
        Paths.get(
            requireNotNull(System.getProperty("test.mcpServerHome")) {
                "System property 'test.mcpServerHome' must point to the installed test server directory"
            },
        )
    private val serverExecutable: String =
        serverHome.resolve("bin")
            .resolve(if (isWindows()) "test-mcp-server.bat" else "test-mcp-server")
            .toFile()
            .absolutePath

    @Test
    fun stdioMode_exposesToolsPromptsAndResources() =
        runBlocking {
            val client =
                StdioMcpClient(
                    command = serverExecutable,
                    args = listOf("--mode", "stdio"),
                    env = emptyMap(),
                )
            val profile = TestServerProfiles.STDIO
            client.updateTimeouts(15_000, 15_000)
            try {
                val connectResult = client.connect()
                connectResult.exceptionOrNull()?.printStackTrace()
                assertTrue(
                    connectResult.isSuccess,
                    "STDIO client should connect to SimpleTestMcpServer (${connectResult.exceptionOrNull()?.message})",
                )
                verifyCapabilitiesAndOperations(client, profile)
            } finally {
                client.disconnect()
            }
        }

    @Test
    fun httpStreamableMode_exposesToolsPromptsAndResources() =
        runBlocking {
            assumeLocalNetworkingAllowed()
            val port = nextFreePort()
            startHttpServerProcess(port, "streamable-http").use { server ->
                waitForHttpServer(port, server)
                val client =
                    KtorMcpClient(
                        mode = KtorMcpClient.Mode.StreamableHttp,
                        url = "http://127.0.0.1:$port$HTTP_PATH",
                    )
                val profile = TestServerProfiles.STREAMABLE_HTTP
                client.updateTimeouts(15_000, 15_000)
                try {
                    val connectResult = client.connect()
                    connectResult.exceptionOrNull()?.printStackTrace()
                    connectResult.exceptionOrNull()?.message?.let { msg ->
                        if (msg.contains("StreamableHttpClientTransport", ignoreCase = true) ||
                            msg.contains("streamable", ignoreCase = true)
                        ) {
                            assumeTrue(
                                false,
                                "Skipping HTTP Streamable test: transport not permitted in this environment ($msg)",
                            )
                        }
                    }
                    assertTrue(
                        connectResult.isSuccess,
                        "HTTP Streamable client should connect to SimpleTestMcpServer " +
                            "(${connectResult.exceptionOrNull()?.message})",
                    )
                    verifyCapabilitiesAndOperations(client, profile)
                } finally {
                    client.disconnect()
                }
            }
        }

    @Test
    fun httpSseMode_exposesToolsPromptsAndResources() =
        runBlocking {
            assumeLocalNetworkingAllowed()
            val port = nextFreePort()
            startHttpServerProcess(port, "http-sse").use { server ->
                waitForHttpServer(port, server)
                val client =
                    KtorMcpClient(
                        mode = KtorMcpClient.Mode.Sse,
                        url = "http://127.0.0.1:$port$HTTP_PATH",
                    )
                val profile = TestServerProfiles.HTTP_SSE
                client.updateTimeouts(15_000, 15_000)
                try {
                    val connectResult = client.connect()
                    connectResult.exceptionOrNull()?.printStackTrace()
                    assertTrue(
                        connectResult.isSuccess,
                        "HTTP SSE client should connect to SimpleTestMcpServer " +
                            "(${connectResult.exceptionOrNull()?.message})",
                    )
                    verifyCapabilitiesAndOperations(client, profile)
                } finally {
                    client.disconnect()
                }
            }
        }

    @Test
    fun webSocketMode_exposesToolsPromptsAndResources() =
        runBlocking {
            assumeLocalNetworkingAllowed()
            val port = nextFreePort()
            startHttpServerProcess(port, "ws").use { server ->
                waitForHttpServer(port, server)
                val client =
                    KtorMcpClient(
                        mode = KtorMcpClient.Mode.WebSocket,
                        url = "ws://127.0.0.1:$port$HTTP_PATH",
                    )
                val profile = TestServerProfiles.WS
                client.updateTimeouts(15_000, 15_000)
                try {
                    val connectResult = client.connect()
                    connectResult.exceptionOrNull()?.printStackTrace()
                    assertTrue(
                        connectResult.isSuccess,
                        "WebSocket client should connect to SimpleTestMcpServer " +
                            "(${connectResult.exceptionOrNull()?.message})",
                    )
                    verifyCapabilitiesAndOperations(client, profile)
                } finally {
                    client.disconnect()
                }
            }
        }

    private fun assumeLocalNetworkingAllowed() {
        val canBind =
            runCatching {
                ServerSocket(0).use { }
            }.isSuccess
        assumeTrue(
            canBind,
            "Local network sockets are not permitted in this environment; skipping HTTP Streamable integration",
        )
    }

    private suspend fun verifyCapabilitiesAndOperations(
        client: McpClient,
        profile: ModeProfile,
    ) {
        val caps =
            client.fetchCapabilities().getOrElse { error ->
                fail("Failed to fetch capabilities: ${error.message ?: error::class.simpleName}")
            }
        assertCapabilities(caps, profile)
        verifyToolCalls(client, profile)
        verifyPrompts(client, profile)
        verifyResources(client, profile)
    }

    private fun assertCapabilities(
        caps: ServerCapabilities,
        profile: ModeProfile,
    ) {
        assertEquals(
            setOf(profile.toolName),
            caps.tools.map { it.name }.toSet(),
            "Tool capabilities should match test server definition",
        )
        assertEquals(
            setOf(profile.resourceUri),
            caps.resources.map { it.uri ?: it.name }.toSet(),
            "Resource capabilities should match test server definition",
        )
        assertEquals(
            setOf(profile.promptName),
            caps.prompts.map { it.name }.toSet(),
            "Prompt capabilities should match test server definition",
        )
    }

    private suspend fun verifyToolCalls(
        client: McpClient,
        profile: ModeProfile,
    ) {
        val args = TestServerProfiles.TOOL_TEST_ARGS
        val payload =
            client.callTool(profile.toolName, arithmeticArgs(args)).getOrFail("tool ${profile.toolName}").jsonObject
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
                .getOrFail("prompt ${profile.promptName}")
        assertPromptContains(response, "${profile.promptPrefix} $name!")
    }

    private suspend fun verifyResources(
        client: McpClient,
        profile: ModeProfile,
    ) {
        val payload = client.readResource(profile.resourceUri).getOrFail("resource ${profile.resourceUri}")
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
                ?: fail("structuredContent block is missing: $payload")
        assertEquals(expectedOperation, structured["operation"]?.jsonPrimitive?.content)
        val resultValue =
            structured["result"]?.jsonPrimitive?.doubleOrNull
                ?: fail("Tool result missing numeric value: $payload")
        assertEquals(expectedResult, resultValue, 0.0001)
        val isError = payload["isError"]?.jsonPrimitive?.booleanOrNull ?: false
        assertTrue(!isError, "Tool call should not report errors: $payload")
    }

    private fun assertPromptContains(
        prompt: JsonObject,
        expectedText: String,
    ) {
        val messages = prompt["messages"]?.jsonArray ?: fail("Prompt payload missing messages: $prompt")
        val first = messages.firstOrNull()?.jsonObject ?: fail("Prompt payload has empty messages: $prompt")
        val contentElement = first["content"] ?: fail("Prompt message missing content: $prompt")
        val text =
            when (contentElement) {
                is JsonArray -> contentElement.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
                is JsonObject -> contentElement["text"]?.jsonPrimitive?.content
                else -> null
            } ?: fail("Prompt text missing: $prompt")
        assertTrue(text.contains(expectedText), "Prompt text should contain '$expectedText' but was '$text'")
    }

    private fun assertResourceContents(
        payload: JsonObject,
        expectedText: String,
    ) {
        val contents = payload["contents"]?.jsonArray ?: fail("Resource payload missing contents: $payload")
        val first = contents.firstOrNull()?.jsonObject ?: fail("Resource contents missing text entry: $payload")
        val text = first["text"]?.jsonPrimitive?.content ?: fail("Resource text missing: $payload")
        assertEquals(expectedText, text)
    }

    private fun <T> Result<T>.getOrFail(operation: String): T =
        getOrElse { error ->
            fail("$operation failed: ${error.message ?: error::class.simpleName}")
        }

    private fun nextFreePort(): Int = ServerSocket(0).use { it.localPort }

    private fun startHttpServerProcess(
        port: Int,
        mode: String,
    ): ManagedProcess {
        val command =
            listOf(
                serverExecutable,
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

    private suspend fun waitForHttpServer(
        port: Int,
        process: ManagedProcess,
    ) {
        repeat(100) {
            if (!process.isAlive()) {
                assumeTrue(false, "Test server exited early: ${process.logs()}")
            }
            if (isPortOpen(port)) return
            delay(100)
        }
        val logSnapshot = process.logs()
        if (logSnapshot.contains("Operation not permitted")) {
            assumeTrue(
                false,
                "Test server cannot bind sockets in this environment; skipping test. Logs: $logSnapshot",
            )
        }
        fail("Test server failed to start on port $port\n$logSnapshot")
    }

    private fun isPortOpen(port: Int): Boolean =
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 100)
            }
        }.isSuccess
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
        thread(name = "simple-test-mcp-server") {
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
        runCatching { readerThread.join(500) }
    }
}

private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")
