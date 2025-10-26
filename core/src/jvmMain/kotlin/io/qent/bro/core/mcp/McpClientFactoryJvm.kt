package io.qent.bro.core.mcp

import io.qent.bro.core.mcp.clients.HttpMcpClient
import io.qent.bro.core.mcp.clients.StdioMcpClient
import io.qent.bro.core.mcp.clients.WebSocketMcpClient
import io.qent.bro.core.models.TransportConfig

actual object McpClientFactory {
    actual fun create(config: TransportConfig, env: Map<String, String>): McpClient = when (config) {
        else -> {
            // Allow test-time override via hook
            McpClientFactoryHooks.provider?.invoke(config, env) ?: when (config) {
                is TransportConfig.StdioTransport -> StdioMcpClient(
                    command = config.command,
                    args = config.args,
                    env = env
                )
                is TransportConfig.HttpTransport -> HttpMcpClient(
                    url = config.url,
                    defaultHeaders = config.headers
                )
                is TransportConfig.WebSocketTransport -> WebSocketMcpClient(
                    url = config.url
                )
            }
        }
    }
}
