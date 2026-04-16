package io.qent.broxy.core.mcp

import io.modelcontextprotocol.kotlin.sdk.types.Annotations
import io.modelcontextprotocol.kotlin.sdk.types.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.Serializable

@Serializable
data class ServerCapabilities(
    val tools: List<ToolDescriptor> = emptyList(),
    val resources: List<ResourceDescriptor> = emptyList(),
    val prompts: List<PromptDescriptor> = emptyList(),
)

@Serializable
data class ToolDescriptor(
    val name: String,
    val description: String? = null,
    val title: String? = null,
    val inputSchema: ToolSchema? = null,
    val outputSchema: ToolSchema? = null,
    val annotations: ToolAnnotations? = null,
)

@Serializable
data class ResourceDescriptor(
    val name: String,
    val uri: String? = null,
    val description: String? = null,
    val mimeType: String? = null,
    val title: String? = null,
    val size: Long? = null,
    val annotations: Annotations? = null,
)

@Serializable
data class PromptDescriptor(
    val name: String,
    val description: String? = null,
    val arguments: List<PromptArgument>? = null,
)
