package io.qent.bro.core.mcp

import kotlinx.serialization.Serializable

@Serializable
data class ServerCapabilities(
    val tools: List<ToolDescriptor> = emptyList(),
    val resources: List<ResourceDescriptor> = emptyList(),
    val prompts: List<PromptDescriptor> = emptyList()
)

@Serializable
data class ToolDescriptor(
    val name: String,
    val description: String? = null
)

@Serializable
data class ResourceDescriptor(
    val name: String,
    val uri: String? = null,
    val description: String? = null
)

@Serializable
data class PromptDescriptor(
    val name: String,
    val description: String? = null
)

