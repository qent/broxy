package io.qent.broxy.core.proxy.inbound

import io.modelcontextprotocol.kotlin.sdk.LIB_VERSION
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.shared.IMPLEMENTATION_NAME
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.qent.broxy.core.proxy.ProxyMcpServer
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.LogEventBuilder
import io.qent.broxy.core.utils.LogRequestContext
import io.qent.broxy.core.utils.LogRequestType
import io.qent.broxy.core.utils.LogTargetContext
import io.qent.broxy.core.utils.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import io.qent.broxy.core.mcp.ServerCapabilities as ProxyServerCapabilities

/**
 * Builds an MCP Server (SDK) instance backed by our ProxyMcpServer for filtering and routing.
 */
fun buildSdkServer(
    proxy: ProxyMcpServer,
    logger: Logger = ConsoleLogger,
): Server {
    val options =
        ServerOptions(
            capabilities =
                ServerCapabilities(
                    // Explicit booleans keep initialize responses schema-valid
                    prompts = ServerCapabilities.Prompts(listChanged = true),
                    resources = ServerCapabilities.Resources(listChanged = true, subscribe = false),
                    tools = ServerCapabilities.Tools(listChanged = true),
                    logging = ServerCapabilities.Logging,
                ),
        )

    val server =
        Server(
            serverInfo = Implementation(name = IMPLEMENTATION_NAME, version = LIB_VERSION),
            options = options,
        )

    // Initial sync from current filtered capabilities. Later preset updates can re-sync without server restart.
    syncSdkServer(server, proxy, logger)

    return server
}

internal data class ProxyBackend(
    val callTool: suspend (toolName: String, arguments: JsonObject) -> Result<JsonElement>,
    val getPrompt: suspend (name: String, arguments: Map<String, String>?) -> Result<JsonObject>,
    val readResource: suspend (uri: String) -> Result<JsonObject>,
)

fun syncSdkServer(
    server: Server,
    proxy: ProxyMcpServer,
    logger: Logger = ConsoleLogger,
) {
    val backend =
        ProxyBackend(
            callTool = { toolName, arguments -> proxy.callTool(toolName, arguments) },
            getPrompt = { name, arguments -> proxy.getPrompt(name, arguments) },
            readResource = { uri -> proxy.readResource(uri) },
        )
    syncSdkServer(
        server = server,
        capabilities = proxy.capabilities,
        backend = backend,
        logger = logger,
        fallbackPromptsAndResourcesToTools = proxy.fallbackPromptsAndResourcesToTools,
        adapterMode = proxy.adapterMode,
        capabilitiesProvider = { proxy.capabilities },
    )
}

@Suppress("LongParameterList")
internal fun syncSdkServer(
    server: Server,
    capabilities: ProxyServerCapabilities,
    backend: ProxyBackend,
    logger: Logger = ConsoleLogger,
    fallbackPromptsAndResourcesToTools: Boolean = false,
    adapterMode: Boolean = false,
    capabilitiesProvider: () -> ProxyServerCapabilities = { capabilities },
) {
    val json = Json { ignoreUnknownKeys = true }

    if (adapterMode) {
        val adapterTools = AdapterModeToolFactory.buildAdapterTools(capabilitiesProvider, backend, logger, json)
        syncTools(server, adapterTools)
        syncPrompts(server, emptyList())
        syncResources(server, emptyList())
        return
    }

    val toolsToAdd = buildToolRegistrations(capabilities, backend, logger, json)
    val fallbackTools = buildFallbackTools(capabilities, backend, logger, fallbackPromptsAndResourcesToTools)
    val allTools = (toolsToAdd + fallbackTools).distinctBy { it.tool.name }
    syncTools(server, allTools)

    val promptsToAdd = buildPromptRegistrations(capabilities, backend, logger, json).distinctBy { it.prompt.name }
    syncPrompts(server, promptsToAdd)

    val resourcesToAdd = buildResourceRegistrations(capabilities, backend, logger).distinctBy { it.resource.uri }
    syncResources(server, resourcesToAdd)
}

private fun buildToolRegistrations(
    capabilities: ProxyServerCapabilities,
    backend: ProxyBackend,
    logger: Logger,
    json: Json,
): List<RegisteredTool> =
    capabilities.tools.map { td ->
        RegisteredTool(
            tool =
                Tool(
                    name = td.name,
                    title = td.title,
                    description = td.description ?: td.title ?: td.name,
                    inputSchema = td.inputSchema ?: ToolSchema(),
                    outputSchema = td.outputSchema,
                    annotations = td.annotations,
                ),
            handler = { req -> handleToolRequest(req, backend, logger, json) },
        )
    }

private fun buildFallbackTools(
    capabilities: ProxyServerCapabilities,
    backend: ProxyBackend,
    logger: Logger,
    fallbackPromptsAndResourcesToTools: Boolean,
): List<RegisteredTool> =
    if (fallbackPromptsAndResourcesToTools) {
        FallbackToolFactory.buildPromptFallbackTools(capabilities.prompts, backend, logger) +
            FallbackToolFactory.buildResourceFallbackTools(capabilities.resources, backend, logger)
    } else {
        emptyList()
    }

private suspend fun handleToolRequest(
    req: CallToolRequest,
    backend: ProxyBackend,
    logger: Logger,
    json: Json,
): CallToolResult {
    LogEventBuilder.llmToFacadeRequest(
        request = LogRequestContext(logger, LogRequestType.TOOL, req.name),
        arguments = req.arguments,
        meta = req.meta?.json,
    )
    val arguments = req.arguments ?: JsonObject(emptyMap())
    val result = backend.callTool(req.name, arguments)
    val target = parseToolTarget(req.name)
    return if (result.isSuccess) {
        val raw = result.getOrNull() ?: JsonNull
        decodeToolResult(json, req.name, target, raw, logger)
    } else {
        val errMsg = result.exceptionOrNull()?.message ?: "Tool error"
        LogEventBuilder.facadeToLlmError(
            request = LogRequestContext(logger, LogRequestType.TOOL, req.name),
            errorMessage = errMsg,
            target = LogTargetContext(target.serverId, target.downstreamName),
            failure = result.exceptionOrNull(),
        )
        CallToolResult(
            content = emptyList(),
            isError = true,
            structuredContent = JsonObject(mapOf("error" to JsonPrimitive(errMsg))),
            meta = JsonObject(emptyMap()),
        )
    }
}

private data class ToolTarget(
    val serverId: String,
    val downstreamName: String,
)

private fun parseToolTarget(name: String): ToolTarget {
    val sepIdx = name.indexOf('_')
    val serverId = if (sepIdx > 0) name.substring(0, sepIdx) else "unknown"
    val downstreamTool = if (sepIdx > 0 && sepIdx < name.length - 1) name.substring(sepIdx + 1) else name
    return ToolTarget(serverId, downstreamTool)
}

private fun decodeToolResult(
    json: Json,
    requestName: String,
    target: ToolTarget,
    raw: JsonElement,
    logger: Logger,
): CallToolResult {
    val decoded =
        runCatching { decodeCallToolResult(json, raw) }
            .onFailure { failure ->
                LogEventBuilder.decodeFailed(
                    request = LogRequestContext(logger, LogRequestType.TOOL, requestName),
                    target = LogTargetContext(target.serverId, target.downstreamName),
                    rawResponse = raw,
                    failure = failure,
                )
            }.getOrElse {
                fallbackCallToolResult(raw)
            }
    val responseJson = Json.encodeToJsonElement(CallToolResult.serializer(), decoded)
    LogEventBuilder.facadeToLlmResponse(
        request = LogRequestContext(logger, LogRequestType.TOOL, requestName),
        response = responseJson,
        target = LogTargetContext(target.serverId, target.downstreamName),
    )
    return decoded
}
