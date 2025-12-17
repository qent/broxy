package io.qent.broxy.core.mcp.clients

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.*
import io.qent.broxy.core.mcp.PromptDescriptor
import io.qent.broxy.core.mcp.ResourceDescriptor
import io.qent.broxy.core.mcp.ToolDescriptor
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger
import kotlinx.serialization.json.JsonObject

class RealSdkClientFacade(
    private val client: Client,
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

    override suspend fun callTool(name: String, arguments: JsonObject): CallToolResult? = runCatching {
        client.request<CallToolResult>(
            CallToolRequest(
                CallToolRequestParams(
                    name = name,
                    arguments = arguments,
                    meta = RequestMeta(JsonObject(emptyMap()))
                )
            )
        )
    }.onFailure { ex ->
        logger.warn("Failed to call tool '$name': ${ex.message}", ex)
    }.getOrNull()

    override suspend fun getPrompt(name: String, arguments: Map<String, String>?): io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult =
        client.getPrompt(
            GetPromptRequest(
                GetPromptRequestParams(
                    name = name,
                    arguments = arguments,
                    meta = RequestMeta(JsonObject(emptyMap()))
                )
            )
        )

    override suspend fun readResource(uri: String): io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult =
        client.readResource(ReadResourceRequest(ReadResourceRequestParams(uri)))

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
