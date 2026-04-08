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
        val transportType = inferTransportType(id, fileServer)
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
            else -> errors.fail("Server '$id': unsupported transport '$transportType'")
        }
    }

    private fun inferTransportType(
        id: String,
        fileServer: FileMcpServer,
    ): String {
        val explicit =
            fileServer.type
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.lowercase()
        val inferred =
            when {
                explicit != null -> explicit
                !fileServer.command.isNullOrBlank() -> "stdio"
                !fileServer.url.isNullOrBlank() -> "http"
                else -> null
            }
        return inferred
            ?: errors.fail(
                "Server '$id': 'type' is missing and cannot be inferred; provide 'type', or 'command'/'url'",
            )
    }

    fun mapToFile(
        server: McpServerConfig,
        env: Map<String, String>,
        auth: AuthConfig?,
        rawTransport: RawSnapshotMerger.RawTransportFields?,
    ): FileMcpServer =
        when (val t = server.transport) {
            is TransportConfig.StdioTransport ->
                FileMcpServer(
                    name = server.name,
                    enabled = server.enabled,
                    type = "stdio",
                    command = rawTransport?.command ?: t.command,
                    args = rawTransport?.args ?: t.args,
                    env = env,
                    envFile = server.envFile?.takeIf { it.isNotBlank() },
                    oauth = auth,
                    iconPath = server.iconPath,
                )
            is TransportConfig.HttpTransport ->
                FileMcpServer(
                    name = server.name,
                    enabled = server.enabled,
                    type = "sse",
                    url = rawTransport?.url ?: t.url,
                    headers = rawTransport?.headers ?: t.headers,
                    env = env,
                    oauth = auth,
                    iconPath = server.iconPath,
                )
            is TransportConfig.StreamableHttpTransport ->
                FileMcpServer(
                    name = server.name,
                    enabled = server.enabled,
                    type = "http",
                    url = rawTransport?.url ?: t.url,
                    headers = rawTransport?.headers ?: t.headers,
                    env = env,
                    oauth = auth,
                    iconPath = server.iconPath,
                )
            is TransportConfig.WebSocketTransport ->
                FileMcpServer(
                    name = server.name,
                    enabled = server.enabled,
                    type = "ws",
                    url = rawTransport?.url ?: t.url,
                    headers = rawTransport?.headers ?: t.headers,
                    env = env,
                    oauth = auth,
                    iconPath = server.iconPath,
                )
        }
}
