package io.qent.broxy.core.proxy.inbound

import io.qent.broxy.core.mcp.ServerStatus
import io.qent.broxy.core.mcp.clients.KtorMcpClient
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.proxy.ProxyMcpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class HttpStreamableInboundServerTest {
    @Test
    fun `http streamable inbound accepts mcp client connection`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val url = "http://127.0.0.1:$port/mcp"

        val proxy = ProxyMcpServer(downstreams = emptyList())
        val inbound = InboundServerFactory.create(
            transport = TransportConfig.StreamableHttpTransport(url = url),
            proxy = proxy
        )

        assertEquals(ServerStatus.Running, inbound.start())

        val client = KtorMcpClient(mode = KtorMcpClient.Mode.StreamableHttp, url = url)
        try {
            withTimeout(10.seconds) {
                while (true) {
                    val connected = client.connect()
                    if (connected.isSuccess) break
                    delay(100)
                }
            }

            val caps = withTimeout(5.seconds) { client.fetchCapabilities() }
            assertTrue(
                caps.isSuccess,
                "Client should fetch capabilities after connect (${caps.exceptionOrNull()?.message})"
            )
        } finally {
            runCatching { client.disconnect() }
            runCatching { inbound.stop() }
        }
    }
}
