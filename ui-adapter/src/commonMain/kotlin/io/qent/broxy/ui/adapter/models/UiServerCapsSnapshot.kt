package io.qent.broxy.ui.adapter.models

data class UiServerCapsSnapshot(
    val serverId: String,
    val name: String,
    val tools: List<String> = emptyList(),
    val prompts: List<String> = emptyList(),
    val resources: List<String> = emptyList()
)

