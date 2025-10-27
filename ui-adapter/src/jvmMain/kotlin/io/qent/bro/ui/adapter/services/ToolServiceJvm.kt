package io.qent.bro.ui.adapter.services

import io.qent.bro.core.mcp.DefaultMcpServerConnection
import io.qent.bro.core.mcp.errors.McpError
import io.qent.bro.ui.adapter.models.UiMcpServerConfig
import io.qent.bro.ui.adapter.models.UiServerCapabilities
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

actual suspend fun fetchServerCapabilities(config: UiMcpServerConfig): Result<UiServerCapabilities> {
    // For interactive UI validation we want a quick fail: single attempt + short timeout.
    val conn = DefaultMcpServerConnection(config, maxRetries = 1)
    return try {
        withTimeout(5_000L) {
            val connect = conn.connect()
            if (connect.isFailure) {
                val ex = connect.exceptionOrNull() ?: IllegalStateException("Failed to connect")
                return@withTimeout Result.failure(ex)
            }
            conn.getCapabilities(forceRefresh = true)
        }
    } catch (t: TimeoutCancellationException) {
        Result.failure(McpError.TimeoutError("Connection timed out after 5s", t))
    } finally {
        runCatching { conn.disconnect() }
    }
}
