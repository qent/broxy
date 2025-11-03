package io.qent.broxy.core.models

import kotlinx.serialization.Serializable

@Serializable
data class Preset(
    val id: String,
    val name: String,
    val description: String,
    val tools: List<ToolReference> = emptyList()
)
