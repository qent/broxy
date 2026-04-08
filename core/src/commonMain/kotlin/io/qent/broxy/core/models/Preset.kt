package io.qent.broxy.core.models

import kotlinx.serialization.Serializable

@Serializable
data class Preset(
    val id: String,
    val name: String,
    val tools: List<ToolReference> = emptyList(),
    val agentTools: List<AgentToolReference> = emptyList(),
    val prompts: List<PromptReference>? = null,
    val resources: List<ResourceReference>? = null,
    val orderIndex: Int = 0,
) {
    companion object {
        const val EMPTY_PRESET_ID: String = "__empty__"
        const val ALL_ENABLED_PRESET_ID: String = "__all_enabled__"
        const val PRESET_MANAGEMENT_ID: String = "__preset_management__"

        fun empty(): Preset =
            Preset(
                id = EMPTY_PRESET_ID,
                name = "No preset",
                tools = emptyList(),
                agentTools = emptyList(),
                prompts = emptyList(),
                resources = emptyList(),
            )

        fun allEnabled(): Preset =
            Preset(
                id = ALL_ENABLED_PRESET_ID,
                name = "All enabled servers",
                tools = emptyList(),
                agentTools = emptyList(),
                prompts = null,
                resources = null,
            )

        fun presetManagement(): Preset =
            Preset(
                id = PRESET_MANAGEMENT_ID,
                name = "AI Preset management",
                tools = emptyList(),
                prompts = emptyList(),
                resources = emptyList(),
            )
    }
}
