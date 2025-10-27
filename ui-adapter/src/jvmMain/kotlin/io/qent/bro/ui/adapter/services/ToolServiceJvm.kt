package io.qent.bro.ui.adapter.services

import io.qent.bro.core.mcp.DefaultMcpServerConnection
import io.qent.bro.ui.adapter.models.UiMcpServerConfig
import io.qent.bro.ui.adapter.models.UiServerCapabilities

actual suspend fun fetchServerCapabilities(config: UiMcpServerConfig): Result<UiServerCapabilities> {
    val conn = DefaultMcpServerConnection(config)
    val r = conn.connect()
    if (r.isFailure) {
        val ex = r.exceptionOrNull() ?: IllegalStateException("Failed to connect")
        runCatching { conn.disconnect() }
        return Result.failure(ex)
    }
    val caps = conn.getCapabilities(forceRefresh = true)
    runCatching { conn.disconnect() }
    return caps
}

