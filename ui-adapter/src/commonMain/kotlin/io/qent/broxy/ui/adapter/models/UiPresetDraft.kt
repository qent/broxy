package io.qent.broxy.ui.adapter.models

data class UiToolRef(
    val serverId: String,
    val toolName: String,
    val enabled: Boolean = true,
)

data class UiPromptRef(
    val serverId: String,
    val promptName: String,
    val enabled: Boolean = true,
)

data class UiResourceRef(
    val serverId: String,
    val resourceKey: String,
    val enabled: Boolean = true,
)

data class UiPresetDraft(
    val id: String,
    val name: String,
    val tools: List<UiToolRef> = emptyList(),
    val prompts: List<UiPromptRef> = emptyList(),
    val resources: List<UiResourceRef> = emptyList(),
    val promptsConfigured: Boolean = true,
    val resourcesConfigured: Boolean = true,
    val originalId: String? = null,
)
