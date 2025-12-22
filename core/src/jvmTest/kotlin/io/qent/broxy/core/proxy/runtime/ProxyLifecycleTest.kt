package io.qent.broxy.core.proxy.runtime

import io.qent.broxy.core.capabilities.ServerCapsSnapshot
import io.qent.broxy.core.capabilities.ServerConnectionUpdate
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.proxy.ProxyMcpServer
import io.qent.broxy.core.utils.LogEvent
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProxyLifecycleTest {
    @Test
    fun start_and_stop_update_running_state() {
        val controller = FakeProxyController()
        val lifecycle = ProxyLifecycle(controller, TestLogger())

        val config =
            McpServersConfig(
                servers = listOf(testServer("s1")),
                requestTimeoutSeconds = 5,
                capabilitiesTimeoutSeconds = 3,
                connectionRetryCount = 2,
            )
        val preset = Preset(id = "p1", name = "Preset")
        val inbound = TransportConfig.StdioTransport(command = "noop")

        assertTrue(lifecycle.start(config, preset, inbound).isSuccess)
        assertTrue(lifecycle.isRunning())

        assertTrue(lifecycle.stop().isSuccess)
        assertTrue(!lifecycle.isRunning())
    }

    @Test
    fun restartWithPreset_uses_current_config_and_inbound() {
        val controller = FakeProxyController()
        val lifecycle = ProxyLifecycle(controller, TestLogger())

        val config =
            McpServersConfig(
                servers = listOf(testServer("s1")),
                requestTimeoutSeconds = 5,
                capabilitiesTimeoutSeconds = 3,
                connectionRetryCount = 2,
            )
        val preset = Preset(id = "p1", name = "Preset")
        val inbound = TransportConfig.StreamableHttpTransport(url = "http://localhost:8080/mcp")

        lifecycle.start(config, preset, inbound)

        val nextPreset = Preset(id = "p2", name = "Next")
        assertTrue(lifecycle.restartWithPreset(nextPreset).isSuccess)

        assertEquals(2, controller.startCalls.size)
        val restarted = controller.startCalls.last()
        assertEquals(nextPreset, restarted.preset)
        assertEquals(inbound, restarted.inbound)
        assertEquals(config.servers, restarted.servers)
    }

    @Test
    fun restartWithConfig_fails_when_not_running() {
        val controller = FakeProxyController()
        val lifecycle = ProxyLifecycle(controller, TestLogger())

        val config =
            McpServersConfig(
                servers = listOf(testServer("s1")),
                requestTimeoutSeconds = 5,
                capabilitiesTimeoutSeconds = 3,
                connectionRetryCount = 2,
            )

        assertTrue(lifecycle.restartWithConfig(config).isFailure)
    }
}

private class FakeProxyController : ProxyController {
    data class StartCall(
        val servers: List<McpServerConfig>,
        val preset: Preset,
        val inbound: TransportConfig,
        val callTimeoutSeconds: Int,
        val capabilitiesTimeoutSeconds: Int,
        val connectionRetryCount: Int,
        val capabilitiesRefreshIntervalSeconds: Int,
    )

    override val logs: Flow<LogEvent> = emptyFlow()
    override val capabilityUpdates: Flow<List<ServerCapsSnapshot>> = emptyFlow()
    override val serverStatusUpdates: Flow<ServerConnectionUpdate> = emptyFlow()

    val startCalls = mutableListOf<StartCall>()

    override fun start(
        servers: List<McpServerConfig>,
        preset: Preset,
        inbound: TransportConfig,
        callTimeoutSeconds: Int,
        capabilitiesTimeoutSeconds: Int,
        connectionRetryCount: Int,
        capabilitiesRefreshIntervalSeconds: Int,
    ): Result<Unit> {
        startCalls +=
            StartCall(
                servers,
                preset,
                inbound,
                callTimeoutSeconds,
                capabilitiesTimeoutSeconds,
                connectionRetryCount,
                capabilitiesRefreshIntervalSeconds,
            )
        return Result.success(Unit)
    }

    override fun stop(): Result<Unit> = Result.success(Unit)

    override fun applyPreset(preset: Preset): Result<Unit> = Result.success(Unit)

    override fun updateServers(
        servers: List<McpServerConfig>,
        callTimeoutSeconds: Int,
        capabilitiesTimeoutSeconds: Int,
        connectionRetryCount: Int,
        capabilitiesRefreshIntervalSeconds: Int,
    ): Result<Unit> = Result.success(Unit)

    override fun updateCallTimeout(seconds: Int) {}

    override fun updateCapabilitiesTimeout(seconds: Int) {}

    override fun updateConnectionRetryCount(count: Int) {}

    override fun currentProxy(): ProxyMcpServer? = null
}

private fun testServer(id: String): McpServerConfig =
    McpServerConfig(
        id = id,
        name = "Server $id",
        transport = TransportConfig.StdioTransport(command = "noop"),
    )

private class TestLogger : Logger {
    override fun debug(message: String) {}

    override fun info(message: String) {}

    override fun warn(
        message: String,
        throwable: Throwable?,
    ) {}

    override fun error(
        message: String,
        throwable: Throwable?,
    ) {}
}
