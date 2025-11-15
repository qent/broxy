package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.core.capabilities.CapabilityCache
import io.qent.broxy.core.capabilities.ServerStatusTracker
import io.qent.broxy.ui.adapter.models.UiLogEntry
import io.qent.broxy.ui.adapter.models.UiMcpServerConfig
import io.qent.broxy.ui.adapter.models.UiPreset
import io.qent.broxy.ui.adapter.models.UiProxyStatus
import io.qent.broxy.ui.adapter.models.UiServer
import io.qent.broxy.ui.adapter.models.UiServerConnStatus
import io.qent.broxy.ui.adapter.models.UiTransportConfig
import io.qent.broxy.ui.adapter.models.UiStdioTransport
import io.qent.broxy.ui.adapter.models.UiHttpTransport
import io.qent.broxy.ui.adapter.models.UiStreamableHttpTransport
import io.qent.broxy.ui.adapter.models.UiWebSocketTransport
import io.qent.broxy.ui.adapter.store.Intents
import io.qent.broxy.ui.adapter.store.UIState

internal data class StoreSnapshot(
    val isLoading: Boolean = true,
    val servers: List<UiMcpServerConfig> = emptyList(),
    val presets: List<UiPreset> = emptyList(),
    val proxyStatus: UiProxyStatus = UiProxyStatus.Stopped,
    val selectedPresetId: String? = null,
    val activeProxyPresetId: String? = null,
    val activeInbound: UiTransportConfig? = null,
    val requestTimeoutSeconds: Int = 60,
    val capabilitiesTimeoutSeconds: Int = 30,
    val capabilitiesRefreshIntervalSeconds: Int = 300,
    val showTrayIcon: Boolean = true
)

internal fun StoreSnapshot.withPresets(newPresets: List<UiPreset>): StoreSnapshot {
    if (newPresets.isEmpty()) {
        return copy(
            presets = emptyList(),
            selectedPresetId = null,
            activeProxyPresetId = null
        )
    }
    val validIds = newPresets.map { it.id }.toSet()
    return copy(
        presets = newPresets,
        selectedPresetId = selectedPresetId?.takeIf { it in validIds },
        activeProxyPresetId = activeProxyPresetId?.takeIf { it in validIds }
    )
}

internal fun StoreSnapshot.toUiState(
    intents: Intents,
    logs: List<UiLogEntry>,
    cache: CapabilityCache,
    statuses: ServerStatusTracker
): UIState {
    if (isLoading) return UIState.Loading
    val uiServers = servers.map { server ->
        val snapshot = cache.snapshot(server.id)
        val derivedStatus = when {
            !server.enabled -> UiServerConnStatus.Disabled
            snapshot != null -> UiServerConnStatus.Available
            else -> statuses.statusFor(server.id) ?: UiServerConnStatus.Connecting
        }
        UiServer(
            id = server.id,
            name = server.name,
            transportLabel = server.transport.transportLabel(),
            enabled = server.enabled,
            status = derivedStatus,
            toolsCount = snapshot?.tools?.size,
            promptsCount = snapshot?.prompts?.size,
            resourcesCount = snapshot?.resources?.size
        )
    }
    return UIState.Ready(
        servers = uiServers,
        presets = presets,
        selectedPresetId = selectedPresetId,
        proxyStatus = proxyStatus,
        requestTimeoutSeconds = requestTimeoutSeconds,
        capabilitiesTimeoutSeconds = capabilitiesTimeoutSeconds,
        capabilitiesRefreshIntervalSeconds = capabilitiesRefreshIntervalSeconds,
        showTrayIcon = showTrayIcon,
        logs = logs,
        intents = intents
    )
}

private fun UiTransportConfig.transportLabel(): String = when (this) {
    is UiStdioTransport -> "STDIO"
    is UiHttpTransport -> "HTTP"
    is UiStreamableHttpTransport -> "HTTP (Streamable)"
    is UiWebSocketTransport -> "WebSocket"
}
