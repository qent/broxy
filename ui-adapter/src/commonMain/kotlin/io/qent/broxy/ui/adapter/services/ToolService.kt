package io.qent.broxy.ui.adapter.services

import io.qent.broxy.core.utils.Logger
import io.qent.broxy.ui.adapter.models.UiMcpServerConfig
import io.qent.broxy.ui.adapter.models.UiHttpDraft
import io.qent.broxy.ui.adapter.models.UiServerDraft
import io.qent.broxy.ui.adapter.models.UiStdioDraft
import io.qent.broxy.ui.adapter.models.UiWebSocketDraft
import io.qent.broxy.ui.adapter.models.UiStreamableHttpDraft
import io.qent.broxy.ui.adapter.models.UiHttpTransport
import io.qent.broxy.ui.adapter.models.UiStdioTransport
import io.qent.broxy.ui.adapter.models.UiStreamableHttpTransport
import io.qent.broxy.ui.adapter.models.UiWebSocketTransport
import io.qent.broxy.ui.adapter.models.UiServerCapabilities

/**
 * Provides access to server tools/capabilities for UI components.
 * Implementations live per-platform.
 */
expect suspend fun fetchServerCapabilities(config: UiMcpServerConfig, timeoutSeconds: Int, logger: Logger? = null): Result<UiServerCapabilities>

/** Validates connectivity by attempting to fetch capabilities for a draft config. */
suspend fun validateServerConnection(draft: UiServerDraft, logger: Logger? = null): Result<Unit> {
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
    return fetchServerCapabilities(cfg, timeoutSeconds = 5, logger = logger).map { }
}
