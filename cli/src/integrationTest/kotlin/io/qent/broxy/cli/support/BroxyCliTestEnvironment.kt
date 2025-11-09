package io.qent.broxy.cli.support

import io.qent.broxy.core.mcp.McpClient
import io.qent.broxy.core.mcp.TimeoutConfigurableMcpClient
import io.qent.broxy.core.mcp.clients.KtorMcpClient
import io.qent.broxy.core.mcp.clients.StdioMcpClient
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import kotlin.io.path.pathString
import kotlin.test.fail
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

internal object BroxyCliTestEnvironment {
    suspend fun startScenario(inboundScenario: InboundScenario): ScenarioHandle {
        val server = startTestServer()
        val configDir = BroxyCliIntegrationFiles.prepareConfigDir(server.url)
        return try {
            when (inboundScenario) {
                InboundScenario.STDIO -> createStdioHandle(configDir, server)
                InboundScenario.HTTP_SSE -> createHttpSseHandle(configDir, server)
            }
        } catch (error: Throwable) {
            configDir.toFile().deleteRecursively()
            server.close()
            throw error
        }
    }

    private suspend fun createStdioHandle(configDir: Path, server: TestServerInstance): ScenarioHandle {
        val command =
            BroxyCliIntegrationFiles.buildCliCommand(configDir, listOf("--inbound", "stdio"))
        BroxyCliIntegrationConfig.log(
            "Launching broxy CLI (STDIO) with config ${configDir.pathString}"
        )
        val client = StdioMcpClient(
            command = command.first(),
            args = command.drop(1),
            env = emptyMap(),
            logger = BroxyCliIntegrationConfig.TEST_LOGGER
        )
        configureTimeouts(client)
        return try {
            connectWithRetries(client)
            ScenarioHandle(
                inboundScenario = InboundScenario.STDIO,
                client = client,
                configDir = configDir,
                cliProcess = null,
                testServerProcess = server.process
            )
        } catch (error: Throwable) {
            client.disconnect()
            throw error
        }
    }

    private suspend fun createHttpSseHandle(configDir: Path, server: TestServerInstance): ScenarioHandle {
        val port = nextFreePort()
        val url = "http://127.0.0.1:$port${BroxyCliIntegrationConfig.HTTP_INBOUND_PATH}"
        val command = BroxyCliIntegrationFiles.buildCliCommand(
            configDir,
            listOf("--inbound", "http", "--url", url)
        )
        BroxyCliIntegrationConfig.log("Launching broxy CLI (HTTP SSE) listening at $url")
        val cliProcess = BroxyCliProcesses.startCliProcess(command)
        val client = KtorMcpClient(
            mode = KtorMcpClient.Mode.Sse,
            url = url,
            logger = BroxyCliIntegrationConfig.TEST_LOGGER
        )
        configureTimeouts(client)
        return try {
            connectWithRetries(client) { cliProcess.logs() }
            ScenarioHandle(
                inboundScenario = InboundScenario.HTTP_SSE,
                client = client,
                configDir = configDir,
                cliProcess = cliProcess,
                testServerProcess = server.process
            )
        } catch (error: Throwable) {
            client.disconnect()
            cliProcess.close()
            throw error
        }
    }

    private suspend fun startTestServer(): TestServerInstance {
        val port = nextFreePort()
        val url =
            "http://${BroxyCliIntegrationConfig.TEST_SERVER_HTTP_HOST}:$port${BroxyCliIntegrationConfig.TEST_SERVER_HTTP_PATH}"
        val command = buildList {
            add(BroxyCliIntegrationFiles.resolveTestServerCommand())
            add("--mode")
            add("http-sse")
            add("--host")
            add(BroxyCliIntegrationConfig.TEST_SERVER_HTTP_HOST)
            add("--port")
            add(port.toString())
            add("--path")
            add(BroxyCliIntegrationConfig.TEST_SERVER_HTTP_PATH)
        }
        BroxyCliIntegrationConfig.log("Launching test MCP server (HTTP SSE) at $url")
        val process = BroxyCliProcesses.startTestServerProcess(command)
        return try {
            waitForHttpServer(BroxyCliIntegrationConfig.TEST_SERVER_HTTP_HOST, port)
            TestServerInstance(url, process)
        } catch (error: Throwable) {
            BroxyCliIntegrationConfig.log("Test MCP server failed to start. Logs:\n${process.logs()}")
            process.close()
            throw error
        }
    }

    private suspend fun connectWithRetries(
        client: McpClient,
        serverLogs: (() -> String)? = null
    ) {
        var lastError: Throwable? = null
        repeat(BroxyCliIntegrationConfig.CONNECT_ATTEMPTS) { attempt ->
            BroxyCliIntegrationConfig.log(
                "Connecting attempt ${attempt + 1} of ${BroxyCliIntegrationConfig.CONNECT_ATTEMPTS}"
            )
            val result = client.connect()
            if (result.isSuccess) {
                BroxyCliIntegrationConfig.log("Connected successfully on attempt ${attempt + 1}")
                return
            }
            lastError = result.exceptionOrNull()
            delay(BroxyCliIntegrationConfig.CONNECT_DELAY_MILLIS)
        }
        val message = buildString {
            append(
                "Failed to connect after ${BroxyCliIntegrationConfig.CONNECT_ATTEMPTS} attempts: " +
                    (lastError?.message ?: "unknown error")
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
            60_000L
        )
    }

    private suspend fun waitForHttpServer(host: String, port: Int) {
        repeat(BroxyCliIntegrationConfig.HTTP_SERVER_ATTEMPTS) {
            if (isPortOpen(host, port)) {
                return
            }
            delay(BroxyCliIntegrationConfig.HTTP_SERVER_DELAY_MILLIS)
        }
        fail("Test MCP HTTP server did not start on $host:$port")
    }

    private fun isPortOpen(host: String, port: Int): Boolean =
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
    private val testServerProcess: RunningProcess
) : AutoCloseable {
    suspend fun run(description: String, block: suspend (McpClient) -> Unit) {
        BroxyCliIntegrationConfig.log("Running ${inboundScenario.description} scenario: $description")
        block(client)
    }

    override fun close() {
        runBlocking { client.disconnect() }
        cliProcess?.close()
        testServerProcess.close()
        configDir.toFile().deleteRecursively()
        BroxyCliIntegrationConfig.log("${inboundScenario.description} scenario cleanup complete")
    }
}

private data class TestServerInstance(
    val url: String,
    val process: RunningProcess
) : AutoCloseable {
    override fun close() {
        process.close()
    }
}
