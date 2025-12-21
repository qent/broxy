package io.qent.broxy.core.mcp.clients

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequest
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ListPromptsRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListResourcesRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.Prompt
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.RequestMeta
import io.modelcontextprotocol.kotlin.sdk.types.Resource
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.qent.broxy.core.mcp.PromptDescriptor
import io.qent.broxy.core.mcp.ResourceDescriptor
import io.qent.broxy.core.mcp.ToolDescriptor
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger
import kotlinx.serialization.json.JsonObject

class RealSdkClientFacade(
    private val client: Client,
    private val logger: Logger = ConsoleLogger,
) : SdkClientFacade {
    private enum class Capability(val label: String, val listOperation: String) {
        Tools("tools", "listTools"),
        Resources("resources", "listResources"),
        Prompts("prompts", "listPrompts"),
    }

    @Volatile
    private var toolsSupported: Boolean? = null

    @Volatile
    private var resourcesSupported: Boolean? = null

    @Volatile
    private var promptsSupported: Boolean? = null

    override suspend fun getTools(): List<ToolDescriptor> =
        if (!isCapabilitySupported(Capability.Tools)) {
            emptyList()
        } else {
            runCatching {
                client.listTools(ListToolsRequest()).tools.map(::mapTool)
            }.onFailure { ex ->
                handleListFailure(Capability.Tools, ex)
            }.getOrDefault(emptyList())
        }

    override suspend fun getResources(): List<ResourceDescriptor> =
        if (!isCapabilitySupported(Capability.Resources)) {
            emptyList()
        } else {
            runCatching {
                client.listResources(ListResourcesRequest()).resources.map(::mapResource)
            }.onFailure { ex ->
                handleListFailure(Capability.Resources, ex)
            }.getOrDefault(emptyList())
        }

    override suspend fun getPrompts(): List<PromptDescriptor> =
        if (!isCapabilitySupported(Capability.Prompts)) {
            emptyList()
        } else {
            runCatching {
                client.listPrompts(ListPromptsRequest()).prompts.map(::mapPrompt)
            }.onFailure { ex ->
                handleListFailure(Capability.Prompts, ex)
            }.getOrDefault(emptyList())
        }

    override suspend fun callTool(
        name: String,
        arguments: JsonObject,
    ): CallToolResult? =
        runCatching {
            client.request<CallToolResult>(
                CallToolRequest(
                    CallToolRequestParams(
                        name = name,
                        arguments = arguments,
                        meta = RequestMeta(JsonObject(emptyMap())),
                    ),
                ),
            )
        }.onFailure { ex ->
            logger.warn("Failed to call tool '$name': ${ex.message}", ex)
        }.getOrNull()

    override suspend fun getPrompt(
        name: String,
        arguments: Map<String, String>?,
    ): io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult =
        client.getPrompt(
            GetPromptRequest(
                GetPromptRequestParams(
                    name = name,
                    arguments = arguments,
                    meta = RequestMeta(JsonObject(emptyMap())),
                ),
            ),
        )

    override suspend fun readResource(uri: String): io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult =
        client.readResource(ReadResourceRequest(ReadResourceRequestParams(uri)))

    override suspend fun close() {
        client.close()
    }

    private fun mapTool(tool: Tool): ToolDescriptor =
        ToolDescriptor(
            name = tool.name,
            description = tool.description,
            title = tool.title,
            inputSchema = tool.inputSchema,
            outputSchema = tool.outputSchema,
            annotations = tool.annotations,
        )

    private fun mapResource(resource: Resource): ResourceDescriptor =
        ResourceDescriptor(
            name = resource.name,
            uri = resource.uri,
            description = resource.description,
            mimeType = resource.mimeType,
            title = resource.title,
            size = resource.size,
            annotations = resource.annotations,
        )

    private fun mapPrompt(prompt: Prompt): PromptDescriptor =
        PromptDescriptor(
            name = prompt.name,
            description = prompt.description,
            arguments = prompt.arguments,
        )

    private fun isCapabilitySupported(capability: Capability): Boolean {
        cachedSupport(capability)?.let { return it }
        val serverCaps = client.serverCapabilities ?: return true
        val supported =
            when (capability) {
                Capability.Tools -> serverCaps.tools != null
                Capability.Resources -> serverCaps.resources != null
                Capability.Prompts -> serverCaps.prompts != null
            }
        recordSupport(capability, supported)
        if (!supported) {
            logger.info("Skipping ${capability.listOperation}: server does not support ${capability.label}.")
        }
        return supported
    }

    private fun handleListFailure(
        capability: Capability,
        ex: Throwable,
    ) {
        if (recordUnsupportedFromError(capability, ex)) return
        logger.warn("Failed to list ${capability.label}: ${ex.message}", ex)
    }

    private fun recordUnsupportedFromError(
        capability: Capability,
        ex: Throwable,
    ): Boolean {
        val message = ex.message ?: return false
        if (!message.startsWith("Server does not support ${capability.label}")) return false
        val changed = recordSupport(capability, false)
        if (changed) {
            logger.info("Skipping ${capability.listOperation}: server does not support ${capability.label}.")
        }
        return true
    }

    private fun cachedSupport(capability: Capability): Boolean? =
        when (capability) {
            Capability.Tools -> toolsSupported
            Capability.Resources -> resourcesSupported
            Capability.Prompts -> promptsSupported
        }

    private fun recordSupport(
        capability: Capability,
        supported: Boolean,
    ): Boolean {
        val previous =
            when (capability) {
                Capability.Tools -> toolsSupported
                Capability.Resources -> resourcesSupported
                Capability.Prompts -> promptsSupported
            }
        if (previous == supported) return false
        when (capability) {
            Capability.Tools -> toolsSupported = supported
            Capability.Resources -> resourcesSupported = supported
            Capability.Prompts -> promptsSupported = supported
        }
        return true
    }
}
