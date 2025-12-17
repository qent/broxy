package io.qent.broxy.core.models

import kotlinx.serialization.Serializable

@Serializable
data class Preset(
    val id: String,
    val name: String,
    val description: String,
    val tools: List<ToolReference> = emptyList(),
    val prompts: List<PromptReference>? = null,
    val resources: List<ResourceReference>? = null
) {
    companion object {
        const val EMPTY_PRESET_ID: String = "__empty__"

        fun empty(): Preset = Preset(
            id = EMPTY_PRESET_ID,
            name = "No preset",
            description = "No active preset selected",
            tools = emptyList(),
            prompts = emptyList(),
            resources = emptyList()
        )
    }
}
