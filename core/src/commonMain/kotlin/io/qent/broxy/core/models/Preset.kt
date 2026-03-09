package io.qent.broxy.core.models

import kotlinx.serialization.Serializable

@Serializable
data class Preset(
    val id: String,
    val name: String,
    val tools: List<ToolReference> = emptyList(),
    val prompts: List<PromptReference>? = null,
    val resources: List<ResourceReference>? = null,
    val createdAtEpochMillis: Long? = null,
) {
    companion object {
        const val EMPTY_PRESET_ID: String = "__empty__"
        const val ALL_ENABLED_PRESET_ID: String = "__all_enabled__"

        fun empty(): Preset =
            Preset(
                id = EMPTY_PRESET_ID,
                name = "No preset",
                tools = emptyList(),
                prompts = emptyList(),
                resources = emptyList(),
            )

        fun allEnabled(): Preset =
            Preset(
                id = ALL_ENABLED_PRESET_ID,
                name = "All enabled servers",
                tools = emptyList(),
                prompts = null,
                resources = null,
            )
    }
}
