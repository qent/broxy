package io.qent.broxy.ui.adapter.models

data class UiPreset(
    val id: String,
    val name: String,
    val toolsCount: Int = 0,
    val allCapabilitiesDisabled: Boolean = false,
    val promptsCount: Int = 0,
    val resourcesCount: Int = 0,
    val toolsServerIds: Set<String> = emptySet(),
    val promptsServerIds: Set<String> = emptySet(),
    val resourcesServerIds: Set<String> = emptySet(),
)
