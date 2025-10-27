package io.qent.bro.ui.adapter.models

data class UiToolRef(
    val serverId: String,
    val toolName: String,
    val enabled: Boolean = true
)

data class UiPresetDraft(
    val id: String,
    val name: String,
    val description: String? = null,
    val tools: List<UiToolRef> = emptyList()
)

