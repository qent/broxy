package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.ui.adapter.capabilities.CapabilityCache
import io.qent.broxy.ui.adapter.capabilities.ServerStatusTracker
import io.qent.broxy.ui.adapter.icons.ServerIconResolver
import io.qent.broxy.ui.adapter.models.UiAiClient
import io.qent.broxy.ui.adapter.models.UiAuthorizationPopup
import io.qent.broxy.ui.adapter.models.UiHttpTransport
import io.qent.broxy.ui.adapter.models.UiMcpServerConfig
import io.qent.broxy.ui.adapter.models.UiPreset
import io.qent.broxy.ui.adapter.models.UiPresetCore
import io.qent.broxy.ui.adapter.models.UiProxyStatus
import io.qent.broxy.ui.adapter.models.UiRemoteConnectionState
import io.qent.broxy.ui.adapter.models.UiServer
import io.qent.broxy.ui.adapter.models.UiServerConnStatus
import io.qent.broxy.ui.adapter.models.UiStdioTransport
import io.qent.broxy.ui.adapter.models.UiStreamableHttpTransport
import io.qent.broxy.ui.adapter.models.UiTransportConfig
import io.qent.broxy.ui.adapter.models.UiWebSocketTransport
import io.qent.broxy.ui.adapter.models.toUiStatus
import io.qent.broxy.ui.adapter.remote.defaultRemoteState
import io.qent.broxy.ui.adapter.remote.isRemoteIntegrationEnabled
import io.qent.broxy.ui.adapter.store.Intents
import io.qent.broxy.ui.adapter.store.UIState

internal data class StoreSnapshot(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val servers: List<UiMcpServerConfig> = emptyList(),
    val presets: List<UiPreset> = emptyList(),
    val clients: List<UiAiClient> = emptyList(),
    val proxyStatus: UiProxyStatus = UiProxyStatus.Stopped,
    val selectedPresetId: String? = null,
    val activeProxyPresetId: String? = null,
    val activeInbound: UiTransportConfig? = null,
    val inboundHttpPort: Int = 3335,
    val requestTimeoutSeconds: Int = 60,
    val capabilitiesTimeoutSeconds: Int = 10,
    val authorizationTimeoutSeconds: Int = 120,
    val connectionRetryCount: Int = 3,
    val capabilitiesRefreshIntervalSeconds: Int = 300,
    val showTrayIcon: Boolean = true,
    val fallbackPromptsAndResourcesToTools: Boolean = false,
    val adapterMode: Boolean = false,
    val remote: UiRemoteConnectionState = defaultRemoteState(),
    val remoteEnabled: Boolean = isRemoteIntegrationEnabled(),
    val authorizationPopup: UiAuthorizationPopup? = null,
    val refreshingServerIds: Set<String> = emptySet(),
)

internal fun StoreSnapshot.withPresets(newPresets: List<UiPreset>): StoreSnapshot {
    val builtinIds = setOf(UiPresetCore.EMPTY_PRESET_ID, UiPresetCore.ALL_ENABLED_PRESET_ID)
    if (newPresets.isEmpty()) {
        return copy(
            presets = emptyList(),
            selectedPresetId = selectedPresetId?.takeIf { it in builtinIds },
            activeProxyPresetId = activeProxyPresetId?.takeIf { it in builtinIds },
        )
    }
    val validIds = newPresets.map { it.id }.toSet() + builtinIds
    return copy(
        presets = newPresets,
        selectedPresetId = selectedPresetId?.takeIf { it in validIds },
        activeProxyPresetId = activeProxyPresetId?.takeIf { it in validIds },
    )
}

internal fun StoreSnapshot.toUiState(
    intents: Intents,
    cache: CapabilityCache,
    statuses: ServerStatusTracker,
): UIState {
    if (errorMessage != null) return UIState.Error(errorMessage)
    if (isLoading) return UIState.Loading
    val uiServers =
        servers.map { server ->
            val snapshot = cache.snapshot(server.id)
            val trackedStatus = statuses.statusFor(server.id)?.toUiStatus()
            val derivedStatus =
                when {
                    !server.enabled -> UiServerConnStatus.Disabled
                    trackedStatus == UiServerConnStatus.Error -> UiServerConnStatus.Error
                    snapshot != null -> UiServerConnStatus.Available
                    trackedStatus == UiServerConnStatus.Authorization -> UiServerConnStatus.Authorization
                    trackedStatus == UiServerConnStatus.Connecting -> UiServerConnStatus.Connecting
                    trackedStatus != null -> trackedStatus
                    else -> UiServerConnStatus.Connecting
                }
            val isPendingStatus =
                derivedStatus == UiServerConnStatus.Authorization || derivedStatus == UiServerConnStatus.Connecting
            val connectingSince =
                if (isPendingStatus) {
                    statuses.connectingSince(server.id)
                } else {
                    null
                }
            val canToggle = true
            val errorMessage =
                if (derivedStatus == UiServerConnStatus.Error) {
                    statuses.errorMessageFor(server.id)
                } else {
                    null
                }
            val icon = ServerIconResolver.resolve(server)
            UiServer(
                id = server.id,
                name = server.name,
                transportLabel = server.transport.transportLabel(),
                enabled = server.enabled,
                canToggle = canToggle,
                status = derivedStatus,
                icon = icon,
                errorMessage = errorMessage,
                connectingSinceEpochMillis = connectingSince,
                toolsCount = snapshot?.tools?.size,
                promptsCount = snapshot?.prompts?.size,
                resourcesCount = snapshot?.resources?.size,
                isRefreshingCapabilities = server.enabled && server.id in refreshingServerIds,
            )
        }
    return UIState.Ready(
        servers = uiServers,
        presets = presets,
        clients = clients,
        selectedPresetId = selectedPresetId,
        inboundHttpPort = inboundHttpPort,
        proxyStatus = proxyStatus,
        requestTimeoutSeconds = requestTimeoutSeconds,
        capabilitiesTimeoutSeconds = capabilitiesTimeoutSeconds,
        connectionRetryCount = connectionRetryCount,
        capabilitiesRefreshIntervalSeconds = capabilitiesRefreshIntervalSeconds,
        showTrayIcon = showTrayIcon,
        fallbackPromptsAndResourcesToTools = fallbackPromptsAndResourcesToTools,
        adapterMode = adapterMode,
        intents = intents,
        remote = remote,
        remoteEnabled = remoteEnabled,
        authorizationPopup = authorizationPopup,
    )
}

private fun UiTransportConfig.transportLabel(): String =
    when (this) {
        is UiStdioTransport -> if (isDockerCommand()) "Docker" else "STDIO"
        is UiHttpTransport -> "SSE"
        is UiStreamableHttpTransport -> "HTTP"
        is UiWebSocketTransport -> "WebSocket"
    }

private fun UiStdioTransport.isDockerCommand(): Boolean {
    val trimmed = command.trim()
    if (trimmed.isEmpty()) {
        return false
    }
    val baseName = trimmed.substringAfterLast('/').substringAfterLast('\\')
    return baseName.equals("docker", ignoreCase = true)
}
