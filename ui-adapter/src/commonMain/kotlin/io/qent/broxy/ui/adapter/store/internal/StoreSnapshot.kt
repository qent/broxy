package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.core.capabilities.CapabilityCache
import io.qent.broxy.core.capabilities.ServerStatusTracker
import io.qent.broxy.ui.adapter.models.*
import io.qent.broxy.ui.adapter.remote.defaultRemoteState
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
    val inboundSsePort: Int = 3335,
    val requestTimeoutSeconds: Int = 60,
    val capabilitiesTimeoutSeconds: Int = 10,
    val capabilitiesRefreshIntervalSeconds: Int = 300,
    val showTrayIcon: Boolean = true,
    val remote: io.qent.broxy.ui.adapter.models.UiRemoteConnectionState = defaultRemoteState()
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
    cache: CapabilityCache,
    statuses: ServerStatusTracker
): UIState {
    if (isLoading) return UIState.Loading
    val uiServers = servers.map { server ->
        val snapshot = cache.snapshot(server.id)
        val derivedStatus = when {
            !server.enabled -> UiServerConnStatus.Disabled
            snapshot != null -> UiServerConnStatus.Available
            else -> statuses.statusFor(server.id)?.toUiStatus() ?: UiServerConnStatus.Connecting
        }
        val connectingSince = if (derivedStatus == UiServerConnStatus.Connecting) {
            statuses.connectingSince(server.id)
        } else {
            null
        }
        UiServer(
            id = server.id,
            name = server.name,
            transportLabel = server.transport.transportLabel(),
            enabled = server.enabled,
            status = derivedStatus,
            connectingSinceEpochMillis = connectingSince,
            toolsCount = snapshot?.tools?.size,
            promptsCount = snapshot?.prompts?.size,
            resourcesCount = snapshot?.resources?.size
        )
    }
    return UIState.Ready(
        servers = uiServers,
        presets = presets,
        selectedPresetId = selectedPresetId,
        inboundSsePort = inboundSsePort,
        proxyStatus = proxyStatus,
        requestTimeoutSeconds = requestTimeoutSeconds,
        capabilitiesTimeoutSeconds = capabilitiesTimeoutSeconds,
        capabilitiesRefreshIntervalSeconds = capabilitiesRefreshIntervalSeconds,
        showTrayIcon = showTrayIcon,
        intents = intents,
        remote = remote
    )
}

private fun UiTransportConfig.transportLabel(): String = when (this) {
    is UiStdioTransport -> "STDIO"
    is UiHttpTransport -> "HTTP"
    is UiStreamableHttpTransport -> "HTTP (Streamable)"
    is UiWebSocketTransport -> "WebSocket"
}
