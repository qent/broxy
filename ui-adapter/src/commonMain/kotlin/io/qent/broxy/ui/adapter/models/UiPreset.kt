package io.qent.broxy.ui.adapter.models

data class UiPreset(
    val id: String,
    val name: String,
    val toolsCount: Int = 0,
    val promptsCount: Int = 0,
    val resourcesCount: Int = 0
)
