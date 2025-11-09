package io.qent.broxy.cli

import io.qent.broxy.core.mcp.McpClient
import io.qent.broxy.core.mcp.PromptDescriptor
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.TimeoutConfigurableMcpClient
import io.qent.broxy.core.mcp.clients.KtorMcpClient
import io.qent.broxy.core.mcp.clients.StdioMcpClient
import io.qent.broxy.core.utils.FilteredLogger
import io.qent.broxy.core.utils.LogLevel
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.exists
import kotlin.io.path.pathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class BroxyCliJarIntegrationTest {
    @Test
    fun stdio_toolsCapabilitiesMatchPreset() = stdioTest("tool capabilities") { client ->
        val caps = awaitFilteredCapabilities(client)
        assertExpectedToolCapabilities(caps)
    }

    @Test
    fun stdio_toolCallsSucceed() = stdioTest("tool invocation") { client ->
        awaitFilteredCapabilities(client)
        callExpectedTools(client)
    }

    @Test
    fun stdio_promptsCapabilitiesMatchPreset() = stdioTest("prompt capabilities") { client ->
        val caps = awaitFilteredCapabilities(client)
        assertExpectedPromptCapabilities(caps)
    }

    @Test
    fun stdio_promptFetchesSucceed() = stdioTest("prompt fetch") { client ->
        val caps = awaitFilteredCapabilities(client)
        fetchExpectedPrompts(client, caps)
    }

    @Test
    fun stdio_resourcesCapabilitiesMatchPreset() = stdioTest("resource capabilities") { client ->
        val caps = awaitFilteredCapabilities(client)
        assertExpectedResourceCapabilities(caps)
    }

    @Test
    fun stdio_resourceReadsSucceed() = stdioTest("resource read") { client ->
        awaitFilteredCapabilities(client)
        readExpectedResources(client)
    }

    @Test
    fun httpSse_toolsCapabilitiesMatchPreset() = httpSseTest("tool capabilities") { client ->
        val caps = awaitFilteredCapabilities(client)
        assertExpectedToolCapabilities(caps)
    }

    @Test
    fun httpSse_toolCallsSucceed() = httpSseTest("tool invocation") { client ->
        awaitFilteredCapabilities(client)
        callExpectedTools(client)
    }

    @Test
    fun httpSse_promptsCapabilitiesMatchPreset() = httpSseTest("prompt capabilities") { client ->
        val caps = awaitFilteredCapabilities(client)
        assertExpectedPromptCapabilities(caps)
    }

    @Test
    fun httpSse_promptFetchesSucceed() = httpSseTest("prompt fetch") { client ->
        val caps = awaitFilteredCapabilities(client)
        fetchExpectedPrompts(client, caps)
    }

    @Test
    fun httpSse_resourcesCapabilitiesMatchPreset() = httpSseTest("resource capabilities") { client ->
        val caps = awaitFilteredCapabilities(client)
        assertExpectedResourceCapabilities(caps)
    }

    @Test
    fun httpSse_resourceReadsSucceed() = httpSseTest("resource read") { client ->
        awaitFilteredCapabilities(client)
        readExpectedResources(client)
    }

    private fun stdioTest(description: String, block: suspend (McpClient) -> Unit) =
        runInboundTest(InboundScenario.STDIO, description, block)

    private fun httpSseTest(description: String, block: suspend (McpClient) -> Unit) =
        runInboundTest(InboundScenario.HTTP_SSE, description, block)

    private fun runInboundTest(
        inboundScenario: InboundScenario,
        description: String,
        block: suspend (McpClient) -> Unit
    ) = runBlocking {
        withTimeout(TEST_TIMEOUT_MILLIS) {
            withHttpTestServer { testServerUrl ->
                val runner: suspend (suspend (McpClient) -> Unit) -> Unit = when (inboundScenario) {
                    InboundScenario.STDIO -> { clientBlock -> withStdioClient(testServerUrl, clientBlock) }
                    InboundScenario.HTTP_SSE -> { clientBlock -> withHttpSseClient(testServerUrl, clientBlock) }
                }
                runner { client ->
                    log("Running ${inboundScenario.description} scenario: $description")
                    block(client)
                }
            }
        }
    }

    private suspend fun withHttpTestServer(block: suspend (String) -> Unit) {
        val port = nextFreePort()
        val url = "http://$TEST_SERVER_HTTP_HOST:$port$TEST_SERVER_HTTP_PATH"
        val command = buildList {
            add(resolveTestServerCommand())
            add("--mode")
            add("http-sse")
            add("--host")
            add(TEST_SERVER_HTTP_HOST)
            add("--port")
            add(port.toString())
            add("--path")
            add(TEST_SERVER_HTTP_PATH)
        }
        log("Launching test MCP server (HTTP SSE) at $url")
        val process = startTestServerProcess(command)
        try {
            waitForHttpServer(TEST_SERVER_HTTP_HOST, port)
            block(url)
        } catch (t: Throwable) {
            log("Test MCP server failed to start. Logs:\n${process.logs()}")
            throw t
        } finally {
            process.close()
            log("HTTP SSE test server cleanup complete")
        }
    }

    private suspend fun withStdioClient(httpServerUrl: String, block: suspend (McpClient) -> Unit) {
        val configDir = prepareConfigDir(httpServerUrl)
        val command = buildCliCommand(configDir, listOf("--inbound", "stdio"))
        log("Launching broxy CLI (STDIO) with config ${configDir.pathString}")
        val client = StdioMcpClient(
            command = command.first(),
            args = command.drop(1),
            env = emptyMap(),
            logger = TEST_LOGGER
        )
        configureTimeouts(client)
        try {
            connectWithRetries(client)
            block(client)
        } finally {
            client.disconnect()
            configDir.toFile().deleteRecursively()
            log("STDIO scenario cleanup complete")
        }
    }

    private suspend fun withHttpSseClient(httpServerUrl: String, block: suspend (McpClient) -> Unit) {
        val configDir = prepareConfigDir(httpServerUrl)
        val port = nextFreePort()
        val url = "http://127.0.0.1:$port/mcp"
        val command = buildCliCommand(
            configDir,
            listOf("--inbound", "http", "--url", url)
        )
        log("Launching broxy CLI (HTTP SSE) listening at $url")
        val process = startCliProcess(command)
        val client = KtorMcpClient(
            mode = KtorMcpClient.Mode.Sse,
            url = url,
            logger = TEST_LOGGER
        )
        configureTimeouts(client)
        try {
            connectWithRetries(client, serverLogs = { process.logs() })
            block(client)
        } finally {
            client.disconnect()
            process.close()
            configDir.toFile().deleteRecursively()
            log("HTTP SSE scenario cleanup complete")
        }
    }

    private suspend fun awaitFilteredCapabilities(client: McpClient): ServerCapabilities {
        var lastSnapshot: ServerCapabilities? = null
        repeat(60) { attempt ->
            log("Fetching filtered capabilities attempt ${attempt + 1}")
            val result = client.fetchCapabilities()
            if (result.isSuccess) {
                val caps = result.getOrThrow()
                lastSnapshot = caps
                if (hasExpectedCapabilities(caps)) {
                    log("Received expected filtered capabilities")
                    return caps
                }
            }
            delay(1_000)
        }
        val snapshotMsg = buildString {
            append("Timed out waiting for filtered capabilities.")
            lastSnapshot?.let {
                append(" Last snapshot tools=${it.tools.map { t -> t.name }} prompts=${it.prompts.map { p -> p.name }} resources=${it.resources.map { r -> r.uri ?: r.name }}")
            }
        }
        log(snapshotMsg)
        fail(
            buildString {
                append(snapshotMsg)
            }
        )
    }

    private fun assertExpectedToolCapabilities(caps: ServerCapabilities) {
        assertEquals(EXPECTED_TOOLS, caps.tools.map { it.name }.toSet(), "Tool list should match preset")
    }

    private fun assertExpectedPromptCapabilities(caps: ServerCapabilities) {
        assertEquals(EXPECTED_PROMPTS, caps.prompts.map { it.name }.toSet(), "Prompt list should match preset")
    }

    private fun assertExpectedResourceCapabilities(caps: ServerCapabilities) {
        assertEquals(
            EXPECTED_RESOURCES,
            caps.resources.map { it.uri ?: it.name }.toSet(),
            "Resource list should match preset"
        )
    }

    private suspend fun callExpectedTools(client: McpClient) {
        EXPECTED_TOOLS.forEach { tool ->
            log("Invoking tool $tool")
            val args = when (tool) {
                "$STDIO_SERVER_ID:$ADD_TOOL_NAME" -> buildArithmeticArguments()
                "$HTTP_SERVER_ID:$SUBTRACT_TOOL_NAME" -> buildArithmeticArguments(a = 5, b = 2)
                else -> JsonObject(emptyMap())
            }
            val payload = client.callTool(tool, args).getOrFail("callTool $tool").asJsonObject("callTool $tool")
            val isError = payload["isError"]?.jsonPrimitive?.booleanOrNull ?: false
            assertTrue(!isError, "Tool $tool returned error payload: $payload")
        }
    }

    private suspend fun fetchExpectedPrompts(client: McpClient, caps: ServerCapabilities) {
        val descriptors = caps.prompts.associateBy { it.name }
        EXPECTED_PROMPTS.forEach { prompt ->
            log("Fetching prompt $prompt")
            val arguments = buildPromptArguments(descriptors[prompt])
            val response = client.getPrompt(prompt, arguments).getOrFail("getPrompt $prompt")
            assertTrue(
                response["messages"] != null,
                "Prompt $prompt should include 'messages' field: $response"
            )
        }
    }

    private fun buildPromptArguments(descriptor: PromptDescriptor?): Map<String, String> {
        if (descriptor == null) return emptyMap()
        val requiredArgs = descriptor.arguments.orEmpty().filter { it.required == true }
        if (requiredArgs.isEmpty()) return emptyMap()
        return requiredArgs.associate { arg -> arg.name to PROMPT_ARGUMENT_PLACEHOLDER }
    }

    private fun buildArithmeticArguments(a: Int = 2, b: Int = 3): JsonObject = buildJsonObject {
        put("a", JsonPrimitive(a))
        put("b", JsonPrimitive(b))
    }

    private suspend fun readExpectedResources(client: McpClient) {
        EXPECTED_RESOURCES.forEach { uri ->
            log("Reading resource $uri")
            val response = client.readResource(uri).getOrFail("readResource $uri")
            val hasResourceObject = response["resource"] != null
            val hasContents = response["contents"] != null
            assertTrue(
                hasResourceObject || hasContents,
                "Resource $uri should include 'resource' or 'contents' field: $response"
            )
        }
    }

    private suspend fun connectWithRetries(
        client: McpClient,
        serverLogs: (() -> String)? = null,
        attempts: Int = 30,
        delayMillis: Long = 1_000
    ) {
        var lastError: Throwable? = null
        repeat(attempts) { attempt ->
            log("Connecting attempt ${attempt + 1} of $attempts")
            val result = client.connect()
            if (result.isSuccess) {
                log("Connected successfully on attempt ${attempt + 1}")
                return
            }
            lastError = result.exceptionOrNull()
            delay(delayMillis)
        }
        val message = buildString {
            append("Failed to connect after $attempts attempts: ${lastError?.message ?: "unknown error"}")
            if (serverLogs != null) {
                append("\nServer output:\n")
                append(serverLogs())
            }
        }
        log(message)
        fail(message)
    }

    private fun hasExpectedCapabilities(caps: ServerCapabilities): Boolean {
        val toolNames = caps.tools.map { it.name }.toSet()
        val promptNames = caps.prompts.map { it.name }.toSet()
        val resourceKeys = caps.resources.map { it.uri ?: it.name }.toSet()
        return toolNames == EXPECTED_TOOLS &&
            promptNames == EXPECTED_PROMPTS &&
            resourceKeys == EXPECTED_RESOURCES
    }

    private fun buildCliCommand(configDir: Path, inboundArgs: List<String>): List<String> = buildList {
        add(javaExecutable())
        add("-jar")
        add(jarPath().pathString)
        add("proxy")
        add("--config-dir")
        add(configDir.pathString)
        add("--preset-id")
        add(PRESET_ID)
        add("--log-level")
        add("warn")
        addAll(inboundArgs)
    }

    private fun prepareConfigDir(httpServerUrl: String): Path {
        val dir = Files.createTempDirectory("broxy-cli-it-")
        writeTestServerConfig(dir.resolve("mcp.json"), httpServerUrl)
        copyResource("/integration/preset_test.json", dir.resolve("preset_test.json"))
        log("Wrote integration config to ${dir.pathString}")
        return dir
    }

    private fun writeTestServerConfig(destination: Path, httpServerUrl: String) {
        val template = readResource("/integration/mcp.json")
        val command = jsonEscape(resolveTestServerCommand())
        val resolved = template
            .replace(TEST_SERVER_COMMAND_PLACEHOLDER, command)
            .replace(TEST_SERVER_HTTP_URL_PLACEHOLDER, jsonEscape(httpServerUrl))
        Files.writeString(destination, resolved)
    }

    private fun readResource(resource: String): String {
        val stream: InputStream = requireNotNull(javaClass.getResourceAsStream(resource)) {
            "Missing classpath resource $resource"
        }
        return stream.use { it.bufferedReader().readText() }
    }

    private fun copyResource(resource: String, destination: Path) {
        val stream: InputStream = requireNotNull(javaClass.getResourceAsStream(resource)) {
            "Missing classpath resource $resource"
        }
        stream.use {
            Files.copy(it, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun resolveTestServerCommand(): String {
        val home = System.getProperty(TEST_SERVER_HOME_PROPERTY)?.takeIf { it.isNotBlank() }
            ?: fail("System property '$TEST_SERVER_HOME_PROPERTY' must point to the test MCP server install dir")
        val binDir = Paths.get(home, "bin")
        val scriptName = if (isWindows()) "test-mcp-server.bat" else "test-mcp-server"
        val path = binDir.resolve(scriptName)
        if (!path.exists()) {
            fail("Test MCP server executable not found at ${path.pathString}")
        }
        return path.toAbsolutePath().pathString
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")

    private fun jsonEscape(value: String): String = buildString {
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }

    private fun configureTimeouts(client: McpClient) {
        (client as? TimeoutConfigurableMcpClient)?.updateTimeouts(
            60_000L,
            60_000L
        )
    }

    private fun startCliProcess(command: List<String>): RunningProcess {
        val process = ProcessBuilder(command)
            .directory(jarPath().parent?.toFile())
            .redirectErrorStream(true)
            .start()
        log("Started broxy CLI process pid=${process.pid()}")
        return RunningProcess(process)
    }

    private fun startTestServerProcess(command: List<String>): RunningProcess {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        log("Started test MCP server process pid=${process.pid()}")
        return RunningProcess(process)
    }

    private class RunningProcess(
        private val process: Process
    ) : AutoCloseable {
        private val collector = ProcessOutputCollector(process)

        fun logs(): String = collector.snapshot()

        override fun close() {
            log("Stopping broxy CLI process pid=${process.pid()}")
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
            collector.close()
        }
    }

    private class ProcessOutputCollector(process: Process) : AutoCloseable {
        private val lines = mutableListOf<String>()
        private val readerThread = thread(name = "broxy-cli-it") {
            process.inputStream.bufferedReader().useLines { seq ->
                seq.forEach { line ->
                    synchronized(lines) { lines.add(line) }
                }
            }
        }

        fun snapshot(): String = synchronized(lines) { lines.joinToString("\n") }

        override fun close() {
            readerThread.interrupt()
            runCatching { readerThread.join(500) }
        }
    }

    private suspend fun waitForHttpServer(host: String, port: Int, attempts: Int = 50, delayMillis: Long = 200) {
        repeat(attempts) {
            if (isPortOpen(host, port)) {
                return
            }
            delay(delayMillis.toLong())
        }
        fail("Test MCP HTTP server did not start on $host:$port")
    }

    private fun isPortOpen(host: String, port: Int): Boolean =
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 250)
            }
        }.isSuccess

    private fun nextFreePort(): Int = ServerSocket(0).use { it.localPort }

    private fun jarPath(): Path = Paths.get(
        System.getProperty("broxy.cliJar")
            ?: fail("System property 'broxy.cliJar' must point to the assembled CLI jar")
    ).toAbsolutePath()

    private fun javaExecutable(): String {
        val javaHome = System.getProperty("java.home") ?: return "java"
        val exeName = if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
        val candidate = Paths.get(javaHome, "bin", exeName)
        return if (candidate.exists()) candidate.pathString else "java"
    }

    private fun <T> Result<T>.getOrFail(operation: String): T =
        getOrElse { error -> fail("$operation failed: ${error.message ?: error::class.simpleName}") }

    private fun JsonElement.asJsonObject(operation: String): JsonObject =
        this as? JsonObject ?: fail("$operation should return JsonObject but was ${this::class.simpleName}")

    private enum class InboundScenario(val description: String) {
        STDIO("STDIO inbound"),
        HTTP_SSE("HTTP SSE inbound")
    }

    companion object {
        private const val PRESET_ID = "test"
        private const val TEST_TIMEOUT_MILLIS = 120_000L
        private const val TEST_SERVER_HOME_PROPERTY = "broxy.testMcpServerHome"
        private const val TEST_SERVER_COMMAND_PLACEHOLDER = "__TEST_MCP_SERVER_COMMAND__"
        private const val TEST_SERVER_HTTP_URL_PLACEHOLDER = "__TEST_MCP_SERVER_HTTP_URL__"
        private const val STDIO_SERVER_ID = "test-arithmetic"
        private const val HTTP_SERVER_ID = "test-arithmetic-http"
        private const val TEST_SERVER_HTTP_HOST = "127.0.0.1"
        private const val TEST_SERVER_HTTP_PATH = "/mcp"
        private const val ADD_TOOL_NAME = "add"
        private const val SUBTRACT_TOOL_NAME = "subtract"
        private const val HELLO_PROMPT = "hello"
        private const val BYE_PROMPT = "bye"
        private const val RESOURCE_ALPHA = "test://resource/alpha"
        private const val RESOURCE_BETA = "test://resource/beta"
        private val EXPECTED_TOOLS = setOf(
            "$STDIO_SERVER_ID:$ADD_TOOL_NAME",
            "$HTTP_SERVER_ID:$SUBTRACT_TOOL_NAME"
        )
        private val EXPECTED_PROMPTS = setOf(HELLO_PROMPT, BYE_PROMPT)
        private val EXPECTED_RESOURCES = setOf(RESOURCE_ALPHA, RESOURCE_BETA)
        private const val PROMPT_ARGUMENT_PLACEHOLDER = "integration-test"
        private val TEST_LOGGER = FilteredLogger(LogLevel.WARN)

        private fun log(message: String) {
            println("[BroxyIT] $message")
        }
    }
}
