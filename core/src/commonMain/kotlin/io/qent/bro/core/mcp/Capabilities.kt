package io.qent.bro.core.mcp

import io.modelcontextprotocol.kotlin.sdk.Annotations
import io.modelcontextprotocol.kotlin.sdk.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.ToolAnnotations
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
    val description: String? = null,
    val title: String? = null,
    val inputSchema: Tool.Input? = null,
    val outputSchema: Tool.Output? = null,
    val annotations: ToolAnnotations? = null
)

@Serializable
data class ResourceDescriptor(
    val name: String,
    val uri: String? = null,
    val description: String? = null,
    val mimeType: String? = null,
    val title: String? = null,
    val size: Long? = null,
    val annotations: Annotations? = null
)

@Serializable
data class PromptDescriptor(
    val name: String,
    val description: String? = null,
    val arguments: List<PromptArgument>? = null
)
