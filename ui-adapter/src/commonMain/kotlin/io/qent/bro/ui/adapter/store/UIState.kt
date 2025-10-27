package io.qent.bro.ui.adapter.store

import io.qent.bro.ui.adapter.models.UiServer
import io.qent.bro.ui.adapter.models.UiPreset
import io.qent.bro.ui.adapter.models.UiProxyStatus
import io.qent.bro.ui.adapter.models.UiServerDraft
import io.qent.bro.ui.adapter.models.UiPresetDraft

// Sealed UI state for the entire app. UI collects this via Flow and renders.
sealed class UIState {
    data object Loading : UIState()
    data class Error(val message: String) : UIState()
    data class Ready(
        val servers: List<UiServer>,
        val presets: List<UiPreset>,
        val proxyStatus: UiProxyStatus,
        val intents: Intents
    ) : UIState()
}

// Functions that UI may call (Intents). Implemented by ui-adapter only.
interface Intents {
    fun refresh()
    fun addOrUpdateServerUi(ui: UiServer)
    fun addServerBasic(id: String, name: String)
    fun upsertServer(draft: UiServerDraft)
    fun removeServer(id: String)
    fun toggleServer(id: String, enabled: Boolean)

    fun addOrUpdatePreset(preset: UiPreset)
    fun upsertPreset(draft: UiPresetDraft)
    fun removePreset(id: String)

    fun startProxySimple(presetId: String)
    fun stopProxy()
}
