package io.qent.broxy.ui.adapter.services

import io.qent.broxy.core.mcp.DefaultMcpServerConnection
import io.qent.broxy.core.mcp.auth.OAuthState
import io.qent.broxy.core.mcp.auth.OAuthStateStore
import io.qent.broxy.core.mcp.auth.resolveOAuthResourceUrl
import io.qent.broxy.core.mcp.auth.restoreFrom
import io.qent.broxy.core.mcp.auth.toSnapshot
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger
import io.qent.broxy.ui.adapter.models.UiHttpTransport
import io.qent.broxy.ui.adapter.models.UiMcpServerConfig
import io.qent.broxy.ui.adapter.models.UiServerCapabilities
import io.qent.broxy.ui.adapter.models.UiStreamableHttpTransport
import io.qent.broxy.ui.adapter.models.UiWebSocketTransport

actual suspend fun fetchServerCapabilities(
    config: UiMcpServerConfig,
    timeoutSeconds: Int,
    logger: Logger?,
): Result<UiServerCapabilities> {
    // No outer timeout - let the internal timeouts handle it.
    // The timeoutSeconds parameter is used to configure internal timeouts.
    val connLogger = logger ?: ConsoleLogger
    val timeoutMillis = timeoutSeconds.coerceAtLeast(1).toLong() * 1_000L
    val authStore = OAuthStateStore(logger = connLogger)
    val resourceUrl = resolveAuthResourceUrl(config)
    val authState =
        resourceUrl?.let {
            OAuthState().also { state ->
                authStore.load(config.id, it)?.let(state::restoreFrom)
            }
        }
    val conn =
        DefaultMcpServerConnection(
            config = config,
            logger = connLogger,
            authState = authState,
            authStateObserver = { state ->
                if (resourceUrl != null) {
                    authStore.save(config.id, state.toSnapshot(resourceUrl))
                }
            },
            maxRetries = 1,
            initialCallTimeoutMillis = timeoutMillis,
            initialCapabilitiesTimeoutMillis = timeoutMillis,
            initialConnectTimeoutMillis = timeoutMillis,
        )
    return try {
        conn.getCapabilities(forceRefresh = true)
    } finally {
        runCatching { conn.disconnect() }
    }
}

private fun resolveAuthResourceUrl(config: UiMcpServerConfig): String? =
    when (val transport = config.transport) {
        is UiHttpTransport -> resolveOAuthResourceUrl(transport.url)
        is UiStreamableHttpTransport -> resolveOAuthResourceUrl(transport.url)
        is UiWebSocketTransport -> resolveOAuthResourceUrl(transport.url)
        else -> null
    }
