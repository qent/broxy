package io.qent.broxy.ui.adapter.models

data class UiPreset(
    val id: String,
    val name: String,
    val description: String? = null,
    val toolsCount: Int = 0
)

