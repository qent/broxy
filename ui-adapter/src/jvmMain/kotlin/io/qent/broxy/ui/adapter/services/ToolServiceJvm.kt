package io.qent.broxy.ui.adapter.services

import io.qent.broxy.core.mcp.DefaultMcpServerConnection
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger
import io.qent.broxy.ui.adapter.models.UiMcpServerConfig
import io.qent.broxy.ui.adapter.models.UiServerCapabilities

actual suspend fun fetchServerCapabilities(
    config: UiMcpServerConfig,
    timeoutSeconds: Int,
    logger: Logger?
): Result<UiServerCapabilities> {
    // No outer timeout - let the internal timeouts handle it.
    // The timeoutSeconds parameter is used to configure internal timeouts.
    val connLogger = logger ?: ConsoleLogger
    val timeoutMillis = timeoutSeconds.coerceAtLeast(1).toLong() * 1_000L
    val conn = DefaultMcpServerConnection(
        config = config,
        logger = connLogger,
        maxRetries = 1,
        initialCallTimeoutMillis = timeoutMillis,
        initialCapabilitiesTimeoutMillis = timeoutMillis,
        initialConnectTimeoutMillis = timeoutMillis
    )
    return try {
        conn.getCapabilities(forceRefresh = true)
    } finally {
        runCatching { conn.disconnect() }
    }
}
