package io.qent.broxy.ui.adapter.store

import io.qent.broxy.ui.adapter.models.UiPreset
import io.qent.broxy.ui.adapter.models.UiPresetDraft
import io.qent.broxy.ui.adapter.models.UiProxyStatus
import io.qent.broxy.ui.adapter.models.UiRemoteConnectionState
import io.qent.broxy.ui.adapter.models.UiServer
import io.qent.broxy.ui.adapter.models.UiServerDraft

// Sealed UI state for the entire app. UI collects this via Flow and renders.
sealed class UIState {
    data object Loading : UIState()

    data class Error(val message: String) : UIState()

    data class Ready(
        val servers: List<UiServer>,
        val presets: List<UiPreset>,
        val selectedPresetId: String?,
        val inboundSsePort: Int,
        val proxyStatus: UiProxyStatus,
        val requestTimeoutSeconds: Int,
        val capabilitiesTimeoutSeconds: Int,
        val capabilitiesRefreshIntervalSeconds: Int,
        val showTrayIcon: Boolean,
        val intents: Intents,
        val remote: UiRemoteConnectionState,
    ) : UIState()
}

// Functions that UI may call (Intents). Implemented by ui-adapter only.
interface Intents {
    fun refresh()

    fun addOrUpdateServerUi(ui: UiServer)

    fun addServerBasic(
        id: String,
        name: String,
    )

    fun upsertServer(draft: UiServerDraft)

    fun removeServer(id: String)

    fun toggleServer(
        id: String,
        enabled: Boolean,
    )

    fun addOrUpdatePreset(preset: UiPreset)

    fun upsertPreset(draft: UiPresetDraft)

    fun removePreset(id: String)

    fun selectProxyPreset(presetId: String?)

    fun updateInboundSsePort(port: Int)

    fun updateRequestTimeout(seconds: Int)

    fun updateCapabilitiesTimeout(seconds: Int)

    fun updateCapabilitiesRefreshInterval(seconds: Int)

    fun updateTrayIconVisibility(visible: Boolean)

    fun openLogsFolder()

    fun updateRemoteServerIdentifier(value: String)

    fun startRemoteAuthorization()

    fun connectRemote()

    fun disconnectRemote()

    fun logoutRemote()
}
