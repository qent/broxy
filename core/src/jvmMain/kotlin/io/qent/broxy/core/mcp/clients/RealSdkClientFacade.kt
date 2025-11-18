package io.qent.broxy.core.mcp.clients

import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResultBase
import io.modelcontextprotocol.kotlin.sdk.GetPromptRequest
import io.modelcontextprotocol.kotlin.sdk.ListPromptsRequest
import io.modelcontextprotocol.kotlin.sdk.ListResourcesRequest
import io.modelcontextprotocol.kotlin.sdk.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.Prompt
import io.modelcontextprotocol.kotlin.sdk.ReadResourceRequest
import io.modelcontextprotocol.kotlin.sdk.Resource
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.qent.broxy.core.mcp.PromptDescriptor
import io.qent.broxy.core.mcp.ResourceDescriptor
import io.qent.broxy.core.mcp.ToolDescriptor
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger
import kotlinx.serialization.json.JsonObject

class RealSdkClientFacade(
    private val client: io.modelcontextprotocol.kotlin.sdk.client.Client,
    private val logger: Logger = ConsoleLogger
) : SdkClientFacade {
    override suspend fun getTools(): List<ToolDescriptor> = runCatching {
        client.listTools(ListToolsRequest()).tools.map(::mapTool)
    }.onFailure { ex ->
        logger.warn("Failed to list tools: ${ex.message}", ex)
    }.getOrDefault(emptyList())

    override suspend fun getResources(): List<ResourceDescriptor> = runCatching {
        client.listResources(ListResourcesRequest()).resources.map(::mapResource)
    }.onFailure { ex ->
        logger.warn("Failed to list resources: ${ex.message}", ex)
    }.getOrDefault(emptyList())

    override suspend fun getPrompts(): List<PromptDescriptor> = runCatching {
        client.listPrompts(ListPromptsRequest()).prompts.map(::mapPrompt)
    }.onFailure { ex ->
        logger.warn("Failed to list prompts: ${ex.message}", ex)
    }.getOrDefault(emptyList())

    override suspend fun callTool(name: String, arguments: JsonObject): CallToolResultBase? =
        client.callTool(CallToolRequest(name, arguments, JsonObject(emptyMap())))

    override suspend fun getPrompt(name: String, arguments: Map<String, String>?): io.modelcontextprotocol.kotlin.sdk.GetPromptResult =
        client.getPrompt(GetPromptRequest(name, arguments = arguments, _meta = JsonObject(emptyMap())))

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
