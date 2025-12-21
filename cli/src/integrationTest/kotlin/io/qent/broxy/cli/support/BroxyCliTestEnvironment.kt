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
    suspend fun startScenario(inboundScenario: InboundScenario): ScenarioHandle {
        val servers = startTestServers()
        val configDir =
            BroxyCliIntegrationFiles.prepareConfigDir(
                servers.streamable.url,
                servers.sse.url,
                servers.ws.url,
            )
        return try {
            when (inboundScenario) {
                InboundScenario.STDIO -> createStdioHandle(configDir, servers)
                InboundScenario.HTTP_STREAMABLE -> createHttpStreamableHandle(configDir, servers)
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
    ): ScenarioHandle {
        val command =
            BroxyCliIntegrationFiles.buildCliCommand(configDir, listOf("--inbound", "stdio"))
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
            )
        } catch (error: Throwable) {
            client.disconnect()
            throw error
        }
    }

    private suspend fun createHttpStreamableHandle(
        configDir: Path,
        servers: TestServers,
    ): ScenarioHandle {
        var lastError: Throwable? = null
        repeat(BroxyCliIntegrationConfig.HTTP_INBOUND_ATTEMPTS) loop@{ attempt ->
            val port = nextFreePort()
            val url = "http://127.0.0.1:$port${BroxyCliIntegrationConfig.HTTP_INBOUND_PATH}"
            val command =
                BroxyCliIntegrationFiles.buildCliCommand(
                    configDir,
                    listOf("--inbound", "http", "--url", url),
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

    private suspend fun startTestServers(): TestServers {
        val started = mutableListOf<TestServerEndpoint>()
        return try {
            val streamable = startTestServer("streamable-http", "http")
            started.add(streamable)
            val sse = startTestServer("http-sse", "http")
            started.add(sse)
            val ws = startTestServer("ws", "ws")
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
            }
        BroxyCliIntegrationConfig.log("Launching test MCP server ($mode) at $url")
        val process = BroxyCliProcesses.startTestServerProcess(command)
        return try {
            waitForHttpServer(BroxyCliIntegrationConfig.TEST_SERVER_HTTP_HOST, port)
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
        repeat(BroxyCliIntegrationConfig.CONNECT_ATTEMPTS) { attempt ->
            BroxyCliIntegrationConfig.log(
                "Connecting attempt ${attempt + 1} of ${BroxyCliIntegrationConfig.CONNECT_ATTEMPTS}",
            )
            val result = client.connect()
            if (result.isSuccess) {
                BroxyCliIntegrationConfig.log("Connected successfully on attempt ${attempt + 1}")
                return
            }
            lastError = result.exceptionOrNull()
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
        val message =
            buildString {
                append(
                    "Failed to connect after ${BroxyCliIntegrationConfig.CONNECT_ATTEMPTS} attempts: " +
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
        repeat(BroxyCliIntegrationConfig.HTTP_SERVER_ATTEMPTS) {
            if (isPortOpen(host, port)) {
                return
            }
            delay(BroxyCliIntegrationConfig.HTTP_SERVER_DELAY_MILLIS)
        }
        fail("Test MCP HTTP server did not start on $host:$port")
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
