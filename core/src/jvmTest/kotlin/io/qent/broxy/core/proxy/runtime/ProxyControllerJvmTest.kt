package io.qent.broxy.core.proxy.runtime

import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.core.utils.Logger
import kotlin.test.Test
import kotlin.test.assertTrue

class ProxyControllerJvmTest {
    @Test
    fun start_stop_with_empty_servers_streamable_http() {
        val controller = createProxyController(CollectingLogger(delegate = NoopLogger))
        val inbound = TransportConfig.StreamableHttpTransport(url = "http://127.0.0.1:0/mcp")
        val preset = Preset.empty()

        val startResult =
            controller.start(
                servers = emptyList(),
                preset = preset,
                inbound = inbound,
                callTimeoutSeconds = 1,
                capabilitiesTimeoutSeconds = 1,
                capabilitiesRefreshIntervalSeconds = 30,
            )

        try {
            assertTrue(startResult.isSuccess)
            assertTrue(controller.updateServers(emptyList(), 1, 1, 30).isSuccess)
            controller.updateCallTimeout(2)
            controller.updateCapabilitiesTimeout(2)
        } finally {
            controller.stop()
        }
    }

    @Test
    fun updateServers_fails_when_not_running() {
        val controller = createProxyController(CollectingLogger(delegate = NoopLogger))
        val result =
            controller.updateServers(
                servers = listOf(testServer("s1")),
                callTimeoutSeconds = 1,
                capabilitiesTimeoutSeconds = 1,
                capabilitiesRefreshIntervalSeconds = 30,
            )

        assertTrue(result.isFailure)
    }
}

private fun testServer(id: String): McpServerConfig =
    McpServerConfig(
        id = id,
        name = "Server $id",
        transport = TransportConfig.StdioTransport(command = "noop"),
    )

private object NoopLogger : Logger {
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
