package io.qent.bro.ui.adapter.services

import io.qent.bro.ui.adapter.models.UiMcpServerConfig
import io.qent.bro.ui.adapter.models.UiHttpDraft
import io.qent.bro.ui.adapter.models.UiServerCapsSnapshot
import io.qent.bro.ui.adapter.models.UiServerDraft
import io.qent.bro.ui.adapter.models.UiStdioDraft
import io.qent.bro.ui.adapter.models.UiWebSocketDraft
import io.qent.bro.ui.adapter.models.UiStreamableHttpDraft
import io.qent.bro.ui.adapter.models.UiHttpTransport
import io.qent.bro.ui.adapter.models.UiStdioTransport
import io.qent.bro.ui.adapter.models.UiStreamableHttpTransport
import io.qent.bro.ui.adapter.models.UiWebSocketTransport
import io.qent.bro.ui.adapter.models.UiServerCapabilities

/**
 * Provides access to server tools/capabilities for UI components.
 * Implementations live per-platform.
 */
expect suspend fun fetchServerCapabilities(config: UiMcpServerConfig): Result<UiServerCapabilities>

/** Validates connectivity by attempting to fetch capabilities for a draft config. */
suspend fun validateServerConnection(draft: UiServerDraft): Result<Unit> {
    val transport = when (val t = draft.transport) {
        is UiStdioDraft -> UiStdioTransport(command = t.command, args = t.args)
        is UiHttpDraft -> UiHttpTransport(url = t.url, headers = t.headers)
        is UiStreamableHttpDraft -> UiStreamableHttpTransport(url = t.url, headers = t.headers)
        is UiWebSocketDraft -> UiWebSocketTransport(url = t.url)
        else -> UiStdioTransport(command = "")
    }
    val cfg = UiMcpServerConfig(
        id = draft.id,
        name = draft.name,
        enabled = draft.enabled,
        transport = transport,
        env = draft.env
    )
    return fetchServerCapabilities(cfg).map { }
}
