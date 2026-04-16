package io.qent.broxy.ui.adapter.models

data class UiServerDraft(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val transport: UiTransportDraft,
    val env: Map<String, String> = emptyMap(),
    val auth: UiAuthConfig? = null,
    val originalId: String? = null,
    val iconPath: String? = null,
)
