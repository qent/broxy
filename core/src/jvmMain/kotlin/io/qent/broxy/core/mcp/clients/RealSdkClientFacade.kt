package io.qent.broxy.core.mcp.clients

import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResultBase
import io.modelcontextprotocol.kotlin.sdk.ListPromptsRequest
import io.modelcontextprotocol.kotlin.sdk.ListResourcesRequest
import io.modelcontextprotocol.kotlin.sdk.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.ReadResourceRequest
import io.modelcontextprotocol.kotlin.sdk.Prompt
import io.modelcontextprotocol.kotlin.sdk.Resource
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.qent.broxy.core.mcp.PromptDescriptor
import io.qent.broxy.core.mcp.ResourceDescriptor
import io.qent.broxy.core.mcp.ToolDescriptor
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

    override suspend fun callTool(name: String, arguments: JsonObject): CallToolResultBase? =
        client.callTool(CallToolRequest(name, arguments, JsonObject(emptyMap())))

    override suspend fun getPrompt(name: String): io.modelcontextprotocol.kotlin.sdk.GetPromptResult =
        client.getPrompt(io.modelcontextprotocol.kotlin.sdk.GetPromptRequest(name, arguments = null, _meta = JsonObject(emptyMap())))

    override suspend fun readResource(uri: String): io.modelcontextprotocol.kotlin.sdk.ReadResourceResult =
        client.readResource(ReadResourceRequest(uri))

    override suspend fun close() {
        client.close()
    }

    private fun mapTool(tool: Tool): ToolDescriptor = ToolDescriptor(
        name = tool.name,
        description = tool.description,
        title = tool.title,
        inputSchema = tool.inputSchema,
        outputSchema = tool.outputSchema,
        annotations = tool.annotations
    )

    private fun mapResource(resource: Resource): ResourceDescriptor = ResourceDescriptor(
        name = resource.name,
        uri = resource.uri,
        description = resource.description,
        mimeType = resource.mimeType,
        title = resource.title,
        size = resource.size,
        annotations = resource.annotations
    )

    private fun mapPrompt(prompt: Prompt): PromptDescriptor = PromptDescriptor(
        name = prompt.name,
        description = prompt.description,
        arguments = prompt.arguments
    )
}
