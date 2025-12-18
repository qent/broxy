package io.qent.broxy.core.mcp

import io.qent.broxy.core.mcp.clients.KtorMcpClient
import io.qent.broxy.core.mcp.clients.StdioMcpClient
import io.qent.broxy.core.models.TransportConfig
import kotlin.test.Test
import kotlin.test.assertTrue

class McpClientFactoryJvmTest {
    @Test
    fun creates_stdio_http_ws_clients_for_transport_configs() {
        val factory = McpClientFactory(defaultMcpClientProvider())

        val stdio = factory.create(
            TransportConfig.StdioTransport(command = "cmd", args = listOf("--x")),
            env = mapOf("K" to "V")
        )
        assertTrue(stdio is StdioMcpClient)

        val http = factory.create(
            TransportConfig.HttpTransport(
                url = "http://localhost:1234/mcp",
                headers = mapOf("h" to "v")
            )
        )
        assertTrue(http is KtorMcpClient)

        val stream = factory.create(
            TransportConfig.StreamableHttpTransport(
                url = "http://localhost:1234/mcp",
                headers = mapOf("h" to "v")
            )
        )
        assertTrue(stream is KtorMcpClient)

        val ws = factory.create(TransportConfig.WebSocketTransport(url = "ws://localhost:1235/mcp"))
        assertTrue(ws is KtorMcpClient)
    }
}
