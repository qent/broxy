package io.qent.broxy.core.proxy

import io.qent.broxy.core.mcp.McpServerConnection
import io.qent.broxy.core.mcp.MultiServerClient
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.DownstreamContext
import io.qent.broxy.core.utils.LogEventBuilder
import io.qent.broxy.core.utils.LogRequestContext
import io.qent.broxy.core.utils.LogRequestType
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

data class ToolCallRequest(
    val name: String,
    val arguments: JsonObject = JsonObject(emptyMap()),
)

/**
 * Dispatches incoming proxy requests to the appropriate downstream server.
 * - Parses prefixed tool names and forwards calls without prefix
 * - Enforces allowed tool set (empty set means allow all by default)
 * - Supports batch tool calls in parallel
 * - Resolves prompts/resources using provided resolvers or capability scan fallback
 */
interface RequestDispatcher {
    suspend fun dispatchToolCall(request: ToolCallRequest): Result<JsonElement>

    suspend fun dispatchBatch(requests: List<ToolCallRequest>): List<Result<JsonElement>>

    suspend fun dispatchPrompt(
        name: String,
        arguments: Map<String, String>? = null,
    ): Result<JsonObject>

    suspend fun dispatchResource(uri: String): Result<JsonObject>
}

@Suppress("LongParameterList", "TooManyFunctions")
class DefaultRequestDispatcher(
    private val servers: List<McpServerConnection>,
    private val allowedPrefixedTools: () -> Set<String> = { emptySet() },
    private val allowAllWhenNoAllowedTools: Boolean = true,
    private val promptServerResolver: (suspend (String) -> String?)? = null,
    private val resourceServerResolver: (suspend (String) -> String?)? = null,
    private val namespace: NamespaceManager = DefaultNamespaceManager(),
    private val logger: Logger = ConsoleLogger,
) : RequestDispatcher {
    private val multi = MultiServerClient(servers)

    override suspend fun dispatchToolCall(request: ToolCallRequest): Result<JsonElement> {
        val allowed = allowedPrefixedTools()
        val name = request.name
        if (shouldEnforceAllowList(allowed, allowAllWhenNoAllowedTools) && name !in allowed) {
            val msg = "Tool '$name' is not allowed by current preset"
            LogEventBuilder.toolDenied(LogRequestContext(logger, LogRequestType.TOOL, name), msg)
            return Result.failure(IllegalArgumentException(msg))
        }
        return runCatching {
            val (facadeServerId, tool) = namespace.parsePrefixedToolName(name)
            val server =
                resolveServerByFacadeId(facadeServerId)
                    ?: return@runCatching Result.failure(
                        IllegalArgumentException("Unknown server: $facadeServerId"),
                    )
            logToolRequest(logger, request, server.serverId, tool)
            val downstreamResult = server.callTool(tool, request.arguments)
            logToolResponse(logger, request, server.serverId, tool, downstreamResult)
            downstreamResult
        }.onFailure { failure ->
            if (failure is CancellationException) {
                throw failure
            }
        }.getOrElse { Result.failure(it) }
    }

    override suspend fun dispatchBatch(requests: List<ToolCallRequest>): List<Result<JsonElement>> =
        coroutineScope {
            requests.map { req -> async { dispatchToolCall(req) } }.awaitAll()
        }

    override suspend fun dispatchPrompt(
        name: String,
        arguments: Map<String, String>?,
    ): Result<JsonObject> {
        val server =
            resolveServerForPrompt(name)
                ?: return Result.failure(IllegalArgumentException("Unknown prompt: $name"))
        val requestContext = LogRequestContext(logger, LogRequestType.PROMPT, name)
        val downstreamContext = DownstreamContext(server.serverId, name)
        val argumentPayload = promptArgumentsToJson(arguments)
        LogEventBuilder.facadeToDownstreamRequest(
            request = requestContext,
            downstream = downstreamContext,
            arguments = argumentPayload,
        )
        val result = server.getPrompt(name, arguments)
        if (result.isSuccess) {
            LogEventBuilder.downstreamResponse(
                request = requestContext,
                downstream = downstreamContext,
                response = result.getOrNull(),
            )
        } else {
            val failure = result.exceptionOrNull()
            LogEventBuilder.downstreamError(
                request = requestContext,
                downstream = downstreamContext,
                errorMessage = failure?.message ?: "getPrompt failed",
                failure = failure,
            )
        }
        return result
    }

    override suspend fun dispatchResource(uri: String): Result<JsonObject> {
        val server =
            resolveServerForResource(uri)
                ?: return Result.failure(IllegalArgumentException("Unknown resource: $uri"))
        val requestContext = LogRequestContext(logger, LogRequestType.RESOURCE, uri)
        val downstreamContext = DownstreamContext(server.serverId, uri)
        LogEventBuilder.facadeToDownstreamRequest(
            request = requestContext,
            downstream = downstreamContext,
        )
        val result = server.readResource(uri)
        if (result.isSuccess) {
            LogEventBuilder.downstreamResponse(
                request = requestContext,
                downstream = downstreamContext,
                response = result.getOrNull(),
            )
        } else {
            val failure = result.exceptionOrNull()
            LogEventBuilder.downstreamError(
                request = requestContext,
                downstream = downstreamContext,
                errorMessage = failure?.message ?: "readResource failed",
                failure = failure,
            )
        }
        return result
    }

    private fun resolveServerByFacadeId(facadeServerId: String): McpServerConnection? =
        servers.firstOrNull { it.serverId == facadeServerId }
            ?: servers.firstOrNull { toFacadeNamespaceServerId(it.serverId) == facadeServerId }

    private suspend fun resolveServerForPrompt(name: String): McpServerConnection? {
        val routed = resolvePromptServerFromRoutingMap(name)
        return routed ?: resolvePromptServerFromCapabilities(name)
    }

    private suspend fun resolveServerForResource(uri: String): McpServerConnection? {
        val routed = resolveResourceServerFromRoutingMap(uri)
        return routed ?: resolveResourceServerFromCapabilities(uri)
    }

    private suspend fun resolvePromptServerFromRoutingMap(name: String): McpServerConnection? {
        val id = promptServerResolver?.invoke(name) ?: return null
        return servers.firstOrNull { it.serverId == id }
    }

    private suspend fun resolvePromptServerFromCapabilities(name: String): McpServerConnection? {
        val all = multi.fetchAllCapabilities()
        val serverId = all.entries.firstOrNull { (_, caps) -> caps.prompts.any { it.name == name } }?.key
        return serverId?.let { sid -> servers.firstOrNull { it.serverId == sid } }
    }

    private suspend fun resolveResourceServerFromRoutingMap(uri: String): McpServerConnection? {
        val id = resourceServerResolver?.invoke(uri) ?: return null
        return servers.firstOrNull { it.serverId == id }
    }

    private suspend fun resolveResourceServerFromCapabilities(uri: String): McpServerConnection? {
        val all: Map<String, ServerCapabilities> = multi.fetchAllCapabilities()
        val serverId = all.entries.firstOrNull { (_, caps) -> caps.resources.any { (it.uri ?: it.name) == uri } }?.key
        return serverId?.let { sid -> servers.firstOrNull { it.serverId == sid } }
    }
}

private fun promptArgumentsToJson(arguments: Map<String, String>?): JsonObject? {
    if (arguments.isNullOrEmpty()) return null
    return buildJsonObject { arguments.forEach { (key, value) -> put(key, JsonPrimitive(value)) } }
}

private fun shouldEnforceAllowList(
    allowed: Set<String>,
    allowAllWhenNoAllowedTools: Boolean,
): Boolean = allowed.isNotEmpty() || !allowAllWhenNoAllowedTools

private fun logToolRequest(
    logger: Logger,
    request: ToolCallRequest,
    serverId: String,
    tool: String,
) {
    val requestContext = LogRequestContext(logger, LogRequestType.TOOL, request.name)
    LogEventBuilder.facadeToDownstreamRequest(
        request = requestContext,
        downstream = DownstreamContext(serverId, tool),
        arguments = request.arguments,
    )
}

private fun logToolResponse(
    logger: Logger,
    request: ToolCallRequest,
    serverId: String,
    tool: String,
    downstreamResult: Result<JsonElement>,
) {
    val requestContext = LogRequestContext(logger, LogRequestType.TOOL, request.name)
    val downstreamContext = DownstreamContext(serverId, tool)
    if (downstreamResult.isSuccess) {
        LogEventBuilder.downstreamResponse(
            request = requestContext,
            downstream = downstreamContext,
            response = downstreamResult.getOrNull(),
        )
    } else {
        val failure = downstreamResult.exceptionOrNull()
        LogEventBuilder.downstreamError(
            request = requestContext,
            downstream = downstreamContext,
            errorMessage = failure?.message ?: "callTool failed",
            failure = failure,
        )
    }
}
