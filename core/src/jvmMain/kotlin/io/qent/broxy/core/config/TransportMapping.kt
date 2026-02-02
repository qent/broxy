package io.qent.broxy.core.config

import io.qent.broxy.core.models.AuthConfig
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.TransportConfig

internal class TransportMapping(
    private val errors: ConfigErrorHandler,
) {
    fun mapTransport(
        id: String,
        fileServer: FileMcpServer,
    ): TransportConfig {
        val transportType = fileServer.transport.lowercase()
        return when (transportType) {
            "stdio" -> {
                val cmd =
                    fileServer.command?.takeIf { it.isNotBlank() }
                        ?: errors.fail("Server '$id' (stdio): 'command' is required")
                TransportConfig.StdioTransport(command = cmd, args = fileServer.args ?: emptyList())
            }
            "http" -> {
                val url =
                    fileServer.url?.takeIf { it.isNotBlank() }
                        ?: errors.fail("Server '$id' (http): 'url' is required")
                TransportConfig.StreamableHttpTransport(url = url, headers = fileServer.headers ?: emptyMap())
            }
            "sse" -> {
                val url =
                    fileServer.url?.takeIf { it.isNotBlank() }
                        ?: errors.fail("Server '$id' (sse): 'url' is required")
                TransportConfig.HttpTransport(url = url, headers = fileServer.headers ?: emptyMap())
            }
            "ws" -> {
                val url =
                    fileServer.url?.takeIf { it.isNotBlank() }
                        ?: errors.fail("Server '$id' (ws): 'url' is required")
                TransportConfig.WebSocketTransport(url = url, headers = fileServer.headers ?: emptyMap())
            }
            else -> errors.fail("Server '$id': unsupported transport '${fileServer.transport}'")
        }
    }

    fun mapToFile(
        server: McpServerConfig,
        env: Map<String, String>,
        auth: AuthConfig?,
    ): FileMcpServer =
        when (val t = server.transport) {
            is TransportConfig.StdioTransport ->
                FileMcpServer(
                    name = server.name,
                    enabled = server.enabled,
                    transport = "stdio",
                    command = t.command,
                    args = t.args,
                    env = env,
                    auth = auth,
                    iconPath = server.iconPath,
                )
            is TransportConfig.HttpTransport ->
                FileMcpServer(
                    name = server.name,
                    enabled = server.enabled,
                    transport = "sse",
                    url = t.url,
                    headers = t.headers,
                    env = env,
                    auth = auth,
                    iconPath = server.iconPath,
                )
            is TransportConfig.StreamableHttpTransport ->
                FileMcpServer(
                    name = server.name,
                    enabled = server.enabled,
                    transport = "http",
                    url = t.url,
                    headers = t.headers,
                    env = env,
                    auth = auth,
                    iconPath = server.iconPath,
                )
            is TransportConfig.WebSocketTransport ->
                FileMcpServer(
                    name = server.name,
                    enabled = server.enabled,
                    transport = "ws",
                    url = t.url,
                    headers = t.headers,
                    env = env,
                    auth = auth,
                    iconPath = server.iconPath,
                )
        }
}
