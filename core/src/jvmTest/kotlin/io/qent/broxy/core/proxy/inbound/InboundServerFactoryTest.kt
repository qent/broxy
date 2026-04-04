package io.qent.broxy.core.proxy.inbound

import io.qent.broxy.core.mcp.ServerStatus
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.proxy.ProxyMcpServer
import io.qent.broxy.core.utils.Logger
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InboundServerFactoryTest {
    @Test
    fun http_inbound_reports_port_in_use() {
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port = socket.localPort
        try {
            val proxy = ProxyMcpServer(emptyList(), logger = NoopLogger)
            val inbound =
                InboundServerFactory.create(
                    transport = TransportConfig.StreamableHttpTransport("http://127.0.0.1:$port/mcp"),
                    proxy = proxy,
                    logger = NoopLogger,
                )

            val status = inbound.start()

            assertTrue(status is ServerStatus.Error)
            assertTrue(status.message?.isNotBlank() == true)
        } finally {
            socket.close()
        }
    }

    @Test
    fun stdio_inbound_refresh_fails_when_not_running() {
        val proxy = ProxyMcpServer(emptyList(), logger = NoopLogger)
        val inbound =
            InboundServerFactory.create(
                transport = TransportConfig.StdioTransport(command = "noop"),
                proxy = proxy,
                logger = NoopLogger,
            )

        val result = inbound.refreshCapabilities()

        assertTrue(result.isFailure)
        assertEquals(ServerStatus.Stopped, inbound.stop())
    }

    private object NoopLogger : Logger {
        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(
            message: String,
            throwable: Throwable?,
        ) = Unit

        override fun error(
            message: String,
            throwable: Throwable?,
        ) = Unit
    }
}
