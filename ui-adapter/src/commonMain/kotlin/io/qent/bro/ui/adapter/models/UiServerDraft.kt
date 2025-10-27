package io.qent.bro.ui.adapter.models

data class UiServerDraft(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val transport: UiTransportDraft
)

