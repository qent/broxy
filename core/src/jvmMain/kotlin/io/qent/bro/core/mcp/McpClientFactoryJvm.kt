package io.qent.bro.core.mcp

import io.qent.bro.core.mcp.clients.StdioMcpClient
import io.qent.bro.core.mcp.clients.KtorMcpClient
import io.qent.bro.core.models.TransportConfig

private object DefaultJvmMcpClientProvider : McpClientProvider {
    override fun create(config: TransportConfig, env: Map<String, String>): McpClient = when (config) {
        is TransportConfig.StdioTransport -> StdioMcpClient(
            command = config.command,
            args = config.args,
            env = env
        )
        is TransportConfig.HttpTransport -> KtorMcpClient(
            mode = KtorMcpClient.Mode.Sse,
            url = config.url,
            headersMap = config.headers
        )
        is TransportConfig.StreamableHttpTransport -> KtorMcpClient(
            mode = KtorMcpClient.Mode.StreamableHttp,
            url = config.url,
            headersMap = config.headers
        )
        is TransportConfig.WebSocketTransport -> KtorMcpClient(
            mode = KtorMcpClient.Mode.WebSocket,
            url = config.url
        )
    }
}

actual fun defaultMcpClientProvider(): McpClientProvider = DefaultJvmMcpClientProvider
