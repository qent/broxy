package io.qent.bro.core.mcp.clients

import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.ListPromptsRequest
import io.modelcontextprotocol.kotlin.sdk.ListResourcesRequest
import io.modelcontextprotocol.kotlin.sdk.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.Prompt
import io.modelcontextprotocol.kotlin.sdk.Resource
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.qent.bro.core.mcp.PromptDescriptor
import io.qent.bro.core.mcp.ResourceDescriptor
import io.qent.bro.core.mcp.ToolDescriptor
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class RealSdkClientFacade(
    private val client: io.modelcontextprotocol.kotlin.sdk.client.Client
) : SdkClientFacade {
    override suspend fun getTools(): List<ToolDescriptor> =
        client.listTools(ListToolsRequest()).tools.map(::mapTool)

    override suspend fun getResources(): List<ResourceDescriptor> =
        client.listResources(ListResourcesRequest()).resources.map(::mapResource)

    override suspend fun getPrompts(): List<PromptDescriptor> =
        client.listPrompts(ListPromptsRequest()).prompts.map(::mapPrompt)

    override suspend fun callTool(name: String, arguments: JsonObject): JsonElement? =
        client.callTool(CallToolRequest(name, arguments, JsonObject(emptyMap())))?.structuredContent

    override suspend fun close() {
        client.close()
    }

    private fun mapTool(tool: Tool): ToolDescriptor = ToolDescriptor(
        name = tool.name,
        description = tool.description
    )

    private fun mapResource(resource: Resource): ResourceDescriptor = ResourceDescriptor(
        name = resource.name,
        uri = resource.uri,
        description = resource.description
    )

    private fun mapPrompt(prompt: Prompt): PromptDescriptor = PromptDescriptor(
        name = prompt.name,
        description = prompt.description
    )
}

