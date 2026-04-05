package io.qent.broxy.ui.adapter.services

import io.qent.broxy.core.mcp.auth.resolveOAuthResourceUrl
import io.qent.broxy.core.mcp.auth.resolveStdioAuthResourceUrl
import io.qent.broxy.ui.adapter.models.UiHttpTransport
import io.qent.broxy.ui.adapter.models.UiMcpServerConfig
import io.qent.broxy.ui.adapter.models.UiStdioTransport
import io.qent.broxy.ui.adapter.models.UiStreamableHttpTransport
import io.qent.broxy.ui.adapter.models.UiWebSocketTransport

actual fun resolveAuthResourceUrl(config: UiMcpServerConfig): String? =
    when (val transport = config.transport) {
        is UiStdioTransport -> resolveStdioAuthResourceUrl(config.id)
        is UiHttpTransport -> resolveOAuthResourceUrl(transport.url)
        is UiStreamableHttpTransport -> resolveOAuthResourceUrl(transport.url)
        is UiWebSocketTransport -> resolveOAuthResourceUrl(transport.url)
        else -> null
    }
