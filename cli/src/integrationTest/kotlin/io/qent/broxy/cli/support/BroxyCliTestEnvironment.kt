package io.qent.broxy.cli.support

import io.qent.broxy.core.mcp.McpClient
import io.qent.broxy.core.mcp.TimeoutConfigurableMcpClient
import io.qent.broxy.core.mcp.clients.KtorMcpClient
import io.qent.broxy.core.mcp.clients.StdioMcpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import kotlin.io.path.pathString
import kotlin.test.fail

internal object BroxyCliTestEnvironment {
    suspend fun startScenario(
        inboundScenario: InboundScenario,
        scenarioConfig: BroxyCliIntegrationConfig.ScenarioConfig = BroxyCliIntegrationConfig.DEFAULT_SCENARIO,
    ): ScenarioHandle {
        val servers = startTestServers(scenarioConfig)
        val configDir =
            BroxyCliIntegrationFiles.prepareConfigDir(
                servers.streamable.url,
                servers.sse.url,
                servers.ws.url,
                scenario = scenarioConfig,
            )
        return try {
            when (inboundScenario) {
                InboundScenario.STDIO -> createStdioHandle(configDir, servers, scenarioConfig)
                InboundScenario.HTTP_STREAMABLE -> createHttpStreamableHandle(configDir, servers, scenarioConfig)
                InboundScenario.HTTP_SSE -> createHttpSseHandle(configDir, servers, scenarioConfig)
            }
        } catch (error: Throwable) {
            configDir.toFile().deleteRecursively()
            servers.close()
            throw error
        }
    }

    private suspend fun createStdioHandle(
        configDir: Path,
        servers: TestServers,
        scenarioConfig: BroxyCliIntegrationConfig.ScenarioConfig,
    ): ScenarioHandle {
        val command =
            BroxyCliIntegrationFiles.buildCliCommand(
                configDir,
                listOf("--inbound", "stdio"),
                presetId = scenarioConfig.presetId,
            )
        BroxyCliIntegrationConfig.log(
            "Launching broxy CLI (STDIO) with config ${configDir.pathString}",
        )
        val client =
            StdioMcpClient(
                command = command.first(),
                args = command.drop(1),
                env = emptyMap(),
                logger = BroxyCliIntegrationConfig.TEST_LOGGER,
            )
        configureTimeouts(client)
        return try {
            connectWithRetries(client)
            ScenarioHandle(
                inboundScenario = InboundScenario.STDIO,
                client = client,
                configDir = configDir,
                cliProcess = null,
                testServers = servers,
                scenarioConfig = scenarioConfig,
            )
        } catch (error: Throwable) {
            client.disconnect()
            throw error
        }
    }

    private suspend fun createHttpStreamableHandle(
        configDir: Path,
        servers: TestServers,
        scenarioConfig: BroxyCliIntegrationConfig.ScenarioConfig,
    ): ScenarioHandle {
        var lastError: Throwable? = null
        repeat(BroxyCliIntegrationConfig.HTTP_INBOUND_ATTEMPTS) loop@{ attempt ->
            val port = nextFreePort()
            val url = "http://127.0.0.1:$port${BroxyCliIntegrationConfig.HTTP_INBOUND_PATH}"
            val command =
                BroxyCliIntegrationFiles.buildCliCommand(
                    configDir,
                    listOf("--inbound", "http", "--url", url),
                    presetId = scenarioConfig.presetId,
                )
            BroxyCliIntegrationConfig.log(
                "Launching broxy CLI (HTTP Streamable) listening at $url (attempt ${attempt + 1})",
            )
            val cliProcess = BroxyCliProcesses.startCliProcess(command)
            val client =
                KtorMcpClient(
                    mode = KtorMcpClient.Mode.StreamableHttp,
                    url = url,
                    logger = BroxyCliIntegrationConfig.TEST_LOGGER,
                )
            configureTimeouts(client)
            try {
                connectWithRetries(client, serverLogs = { cliProcess.logs() }, serverProcess = cliProcess)
                return ScenarioHandle(
                    inboundScenario = InboundScenario.HTTP_STREAMABLE,
                    client = client,
                    configDir = configDir,
                    cliProcess = cliProcess,
                    testServers = servers,
                    scenarioConfig = scenarioConfig,
                )
            } catch (error: Throwable) {
                lastError = error
                val cliLogs = cliProcess.logs()
                client.disconnect()
                cliProcess.close()
                val isPortInUse =
                    error.message?.contains("already in use", ignoreCase = true) == true ||
                        cliLogs.contains("already in use", ignoreCase = true)
                val hasAttemptsRemaining = attempt + 1 < BroxyCliIntegrationConfig.HTTP_INBOUND_ATTEMPTS
                if (isPortInUse && hasAttemptsRemaining) {
                    BroxyCliIntegrationConfig.log("Inbound port $port unavailable, retrying with a new port")
                    delay(BroxyCliIntegrationConfig.HTTP_SERVER_DELAY_MILLIS)
                    return@loop
                }
                throw error
            }
        }
        throw lastError ?: IllegalStateException("Failed to launch HTTP Streamable scenario after retries")
    }

    private suspend fun createHttpSseHandle(
        configDir: Path,
        servers: TestServers,
        scenarioConfig: BroxyCliIntegrationConfig.ScenarioConfig,
    ): ScenarioHandle {
        var lastError: Throwable? = null
        repeat(BroxyCliIntegrationConfig.HTTP_INBOUND_ATTEMPTS) loop@{ attempt ->
            val port = nextFreePort()
            val streamableUrl = "http://127.0.0.1:$port${BroxyCliIntegrationConfig.HTTP_INBOUND_PATH}"
            val sseUrl = "http://127.0.0.1:$port${BroxyCliIntegrationConfig.SSE_INBOUND_PATH}"
            val command =
                BroxyCliIntegrationFiles.buildCliCommand(
                    configDir,
                    listOf("--inbound", "http", "--url", streamableUrl),
                    presetId = scenarioConfig.presetId,
                )
            BroxyCliIntegrationConfig.log(
                "Launching broxy CLI (HTTP SSE) listening at $sseUrl (attempt ${attempt + 1})",
            )
            val cliProcess = BroxyCliProcesses.startCliProcess(command)
            val client =
                KtorMcpClient(
                    mode = KtorMcpClient.Mode.Sse,
                    url = sseUrl,
                    logger = BroxyCliIntegrationConfig.TEST_LOGGER,
                )
            configureTimeouts(client)
            try {
                connectWithRetries(client, serverLogs = { cliProcess.logs() }, serverProcess = cliProcess)
                return ScenarioHandle(
                    inboundScenario = InboundScenario.HTTP_SSE,
                    client = client,
                    configDir = configDir,
                    cliProcess = cliProcess,
                    testServers = servers,
                    scenarioConfig = scenarioConfig,
                )
            } catch (error: Throwable) {
                lastError = error
                val cliLogs = cliProcess.logs()
                client.disconnect()
                cliProcess.close()
                val isPortInUse =
                    error.message?.contains("already in use", ignoreCase = true) == true ||
                        cliLogs.contains("already in use", ignoreCase = true)
                val hasAttemptsRemaining = attempt + 1 < BroxyCliIntegrationConfig.HTTP_INBOUND_ATTEMPTS
                if (isPortInUse && hasAttemptsRemaining) {
                    BroxyCliIntegrationConfig.log("Inbound port $port unavailable, retrying with a new port")
                    delay(BroxyCliIntegrationConfig.HTTP_SERVER_DELAY_MILLIS)
                    return@loop
                }
                throw error
            }
        }
        throw lastError ?: IllegalStateException("Failed to launch HTTP SSE scenario after retries")
    }

    private suspend fun startTestServers(scenarioConfig: BroxyCliIntegrationConfig.ScenarioConfig): TestServers {
        val started = mutableListOf<TestServerEndpoint>()
        return try {
            val streamable =
                startTestServer(
                    BroxyCliIntegrationConfig.TEST_SERVER_MODE_HTTP,
                    "http",
                    KtorMcpClient.Mode.StreamableHttp,
                    scenarioConfig.testServerArgsByMode[BroxyCliIntegrationConfig.TEST_SERVER_MODE_HTTP].orEmpty(),
                )
            started.add(streamable)
            val sse =
                startTestServer(
                    BroxyCliIntegrationConfig.TEST_SERVER_MODE_SSE,
                    "http",
                    KtorMcpClient.Mode.Sse,
                    scenarioConfig.testServerArgsByMode[BroxyCliIntegrationConfig.TEST_SERVER_MODE_SSE].orEmpty(),
                )
            started.add(sse)
            val ws =
                startTestServer(
                    BroxyCliIntegrationConfig.TEST_SERVER_MODE_WS,
                    "ws",
                    KtorMcpClient.Mode.WebSocket,
                    scenarioConfig.testServerArgsByMode[BroxyCliIntegrationConfig.TEST_SERVER_MODE_WS].orEmpty(),
                )
            started.add(ws)
            TestServers(streamable = streamable, sse = sse, ws = ws)
        } catch (error: Throwable) {
            started.forEach { it.process.close() }
            throw error
        }
    }

    private suspend fun startTestServer(
        mode: String,
        scheme: String,
        clientMode: KtorMcpClient.Mode,
        extraArgs: List<String>,
    ): TestServerEndpoint {
        val port = nextFreePort()
        val url =
            "$scheme://${BroxyCliIntegrationConfig.TEST_SERVER_HTTP_HOST}:$port" +
                BroxyCliIntegrationConfig.TEST_SERVER_HTTP_PATH
        val command =
            buildList {
                add(BroxyCliIntegrationFiles.resolveTestServerCommand())
                add("--mode")
                add(mode)
                add("--host")
                add(BroxyCliIntegrationConfig.TEST_SERVER_HTTP_HOST)
                add("--port")
                add(port.toString())
                add("--path")
                add(BroxyCliIntegrationConfig.TEST_SERVER_HTTP_PATH)
                addAll(extraArgs)
            }
        BroxyCliIntegrationConfig.log("Launching test MCP server ($mode) at $url")
        val process = BroxyCliProcesses.startTestServerProcess(command)
        return try {
            waitForHttpServer(BroxyCliIntegrationConfig.TEST_SERVER_HTTP_HOST, port)
            waitForMcpServer(
                mode = mode,
                clientMode = clientMode,
                url = url,
            )
            TestServerEndpoint(url, process)
        } catch (error: Throwable) {
            BroxyCliIntegrationConfig.log("Test MCP server ($mode) failed to start. Logs:\n${process.logs()}")
            process.close()
            throw error
        }
    }

    private suspend fun connectWithRetries(
        client: McpClient,
        serverLogs: (() -> String)? = null,
        serverProcess: RunningProcess? = null,
    ) {
        var lastError: Throwable? = null
        val startTime = System.nanoTime()
        repeat(BroxyCliIntegrationConfig.CONNECT_ATTEMPTS) { attempt ->
            BroxyCliIntegrationConfig.log(
                "Connecting attempt ${attempt + 1} of ${BroxyCliIntegrationConfig.CONNECT_ATTEMPTS}",
            )
            val result = client.connect()
            if (result.isSuccess) {
                val elapsedMillis = (System.nanoTime() - startTime) / 1_000_000
                BroxyCliIntegrationConfig.log(
                    "Connected successfully on attempt ${attempt + 1} after ${elapsedMillis}ms",
                )
                return
            }
            lastError = result.exceptionOrNull()
            BroxyCliIntegrationConfig.log(
                "Connection attempt ${attempt + 1} failed: ${lastError?.message ?: "unknown error"}",
            )
            if (serverProcess?.isAlive() == false) {
                val message =
                    buildString {
                        append(
                            "Inbound process exited before connection succeeded: " +
                                (lastError?.message ?: "unknown error"),
                        )
                        if (serverLogs != null) {
                            append("\nServer output:\n")
                            append(serverLogs())
                        }
                    }
                BroxyCliIntegrationConfig.log(message)
                fail(message)
            }
            delay(BroxyCliIntegrationConfig.CONNECT_DELAY_MILLIS)
        }
        val elapsedMillis = (System.nanoTime() - startTime) / 1_000_000
        val message =
            buildString {
                append(
                    "Failed to connect after ${BroxyCliIntegrationConfig.CONNECT_ATTEMPTS} attempts: " +
                        (lastError?.message ?: "unknown error"),
                )
                append(" (elapsed ${elapsedMillis}ms)")
                if (serverLogs != null) {
                    append("\nServer output:\n")
                    append(serverLogs())
                }
            }
        BroxyCliIntegrationConfig.log(message)
        fail(message)
    }

    private fun configureTimeouts(client: McpClient) {
        (client as? TimeoutConfigurableMcpClient)?.updateTimeouts(
            60_000L,
            60_000L,
        )
    }

    private suspend fun waitForHttpServer(
        host: String,
        port: Int,
    ) {
        val startTime = System.nanoTime()
        repeat(BroxyCliIntegrationConfig.HTTP_SERVER_ATTEMPTS) { attempt ->
            if (isPortOpen(host, port)) {
                val elapsedMillis = (System.nanoTime() - startTime) / 1_000_000
                BroxyCliIntegrationConfig.log(
                    "Test MCP HTTP server ready after ${elapsedMillis}ms on $host:$port",
                )
                return
            }
            BroxyCliIntegrationConfig.log(
                "Test MCP HTTP server not ready on $host:$port (attempt ${attempt + 1})",
            )
            delay(BroxyCliIntegrationConfig.HTTP_SERVER_DELAY_MILLIS)
        }
        val elapsedMillis = (System.nanoTime() - startTime) / 1_000_000
        val message = "Test MCP HTTP server did not start on $host:$port after ${elapsedMillis}ms"
        BroxyCliIntegrationConfig.log(message)
        fail(message)
    }

    private suspend fun waitForMcpServer(
        mode: String,
        clientMode: KtorMcpClient.Mode,
        url: String,
    ) {
        val startTime = System.nanoTime()
        var lastError: String = "unknown error"
        repeat(BroxyCliIntegrationConfig.HTTP_SERVER_ATTEMPTS) { attempt ->
            val client =
                KtorMcpClient(
                    mode = clientMode,
                    url = url,
                    logger = BroxyCliIntegrationConfig.TEST_LOGGER,
                )
            (client as? TimeoutConfigurableMcpClient)?.updateTimeouts(1_500L, 1_500L)
            try {
                val connected = client.connect()
                if (connected.isSuccess) {
                    val capabilities = client.fetchCapabilities()
                    if (capabilities.isSuccess) {
                        val elapsedMillis = (System.nanoTime() - startTime) / 1_000_000
                        BroxyCliIntegrationConfig.log(
                            "Test MCP server ($mode) MCP-ready after ${elapsedMillis}ms at $url",
                        )
                        return
                    }
                    lastError = capabilities.exceptionOrNull()?.message ?: "fetchCapabilities failed"
                } else {
                    lastError = connected.exceptionOrNull()?.message ?: "connect failed"
                }
            } catch (error: Throwable) {
                lastError = error.message ?: error::class.simpleName ?: "unexpected error"
            } finally {
                runCatching { client.disconnect() }
            }
            BroxyCliIntegrationConfig.log(
                "Test MCP server ($mode) MCP check not ready at $url " +
                    "(attempt ${attempt + 1}): $lastError",
            )
            delay(BroxyCliIntegrationConfig.HTTP_SERVER_DELAY_MILLIS)
        }
        val elapsedMillis = (System.nanoTime() - startTime) / 1_000_000
        fail("Test MCP server ($mode) MCP check timed out after ${elapsedMillis}ms at $url: $lastError")
    }

    private fun isPortOpen(
        host: String,
        port: Int,
    ): Boolean =
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 100)
            }
        }.isSuccess

    private fun nextFreePort(): Int = ServerSocket(0).use { it.localPort }
}

internal class ScenarioHandle(
    private val inboundScenario: InboundScenario,
    private val client: McpClient,
    private val configDir: Path,
    private val cliProcess: RunningProcess?,
    private val testServers: TestServers,
    private val scenarioConfig: BroxyCliIntegrationConfig.ScenarioConfig,
) : AutoCloseable {
    suspend fun run(
        description: String,
        block: suspend (McpClient) -> Unit,
    ) {
        BroxyCliIntegrationConfig.log("Running ${inboundScenario.description} scenario: $description")
        block(client)
    }

    override fun close() {
        runBlocking { client.disconnect() }
        cliProcess?.close()
        testServers.close()
        configDir.toFile().deleteRecursively()
        BroxyCliIntegrationConfig.log("${inboundScenario.description} scenario cleanup complete")
    }

    fun stopDownstream(target: DownstreamTarget) {
        when (target) {
            DownstreamTarget.STDIO -> {
                require(scenarioConfig.stdioCommandMode == BroxyCliIntegrationConfig.StdioCommandMode.OUTAGE_WRAPPER) {
                    "STDIO outage marker requires StdioCommandMode.OUTAGE_WRAPPER"
                }
                val marker = configDir.resolve(BroxyCliIntegrationConfig.STDIO_OUTAGE_MARKER_FILE)
                marker.toFile().writeText("outage")
                BroxyCliIntegrationConfig.log("STDIO outage marker created at ${marker.pathString}")
            }
            DownstreamTarget.HTTP_STREAMABLE -> testServers.streamable.process.close()
            DownstreamTarget.HTTP_SSE -> testServers.sse.process.close()
            DownstreamTarget.WS -> testServers.ws.process.close()
        }
    }
}

internal enum class DownstreamTarget {
    STDIO,
    HTTP_STREAMABLE,
    HTTP_SSE,
    WS,
}

internal data class TestServerEndpoint(
    val url: String,
    val process: RunningProcess,
)

internal data class TestServers(
    val streamable: TestServerEndpoint,
    val sse: TestServerEndpoint,
    val ws: TestServerEndpoint,
) : AutoCloseable {
    override fun close() {
        streamable.process.close()
        sse.process.close()
        ws.process.close()
    }
}
