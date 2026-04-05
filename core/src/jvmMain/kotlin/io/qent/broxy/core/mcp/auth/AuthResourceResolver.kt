package io.qent.broxy.core.mcp.auth

import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.TransportConfig
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private const val STDIO_AUTH_RESOURCE_PREFIX = "broxy://stdio/"

fun resolveAuthResourceUrl(config: McpServerConfig): String? = resolveAuthResourceUrl(config.id, config.transport)

fun resolveAuthResourceUrl(
    serverId: String,
    transport: TransportConfig,
): String? =
    when (transport) {
        is TransportConfig.StdioTransport -> resolveStdioAuthResourceUrl(serverId)
        is TransportConfig.HttpTransport -> resolveOAuthResourceUrl(transport.url)
        is TransportConfig.StreamableHttpTransport -> resolveOAuthResourceUrl(transport.url)
        is TransportConfig.WebSocketTransport -> resolveOAuthResourceUrl(transport.url)
    }

fun resolveStdioAuthResourceUrl(serverId: String): String {
    val encoded =
        URLEncoder
            .encode(serverId, StandardCharsets.UTF_8)
            .replace("+", "%20")
    return "$STDIO_AUTH_RESOURCE_PREFIX$encoded"
}
