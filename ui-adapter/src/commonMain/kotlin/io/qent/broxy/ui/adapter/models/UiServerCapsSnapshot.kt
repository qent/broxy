package io.qent.broxy.ui.adapter.models

data class UiServerCapsSnapshot(
    val serverId: String,
    val name: String,
    val tools: List<UiToolSummary> = emptyList(),
    val prompts: List<UiPromptSummary> = emptyList(),
    val resources: List<UiResourceSummary> = emptyList()
)

data class UiToolSummary(
    val name: String,
    val description: String? = null,
    val arguments: List<UiCapabilityArgument> = emptyList()
)

data class UiPromptSummary(
    val name: String,
    val description: String? = null,
    val arguments: List<UiCapabilityArgument> = emptyList()
)

data class UiResourceSummary(
    val key: String,
    val name: String,
    val description: String? = null,
    val arguments: List<UiCapabilityArgument> = emptyList()
)

data class UiCapabilityArgument(
    val name: String,
    val type: String = "unspecified",
    val required: Boolean = false
)
