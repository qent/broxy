package io.qent.broxy.core.proxy

import io.qent.broxy.core.mcp.McpServerConnection
import io.qent.broxy.core.mcp.MultiServerClient
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger
import io.qent.broxy.core.utils.errorJson
import io.qent.broxy.core.utils.infoJson
import io.qent.broxy.core.utils.warnJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

data class ToolCallRequest(val name: String, val arguments: JsonObject = JsonObject(emptyMap()))

/**
 * Dispatches incoming proxy requests to the appropriate downstream server.
 * - Parses prefixed tool names and forwards calls without prefix
 * - Enforces allowed tool set (empty set means allow all)
 * - Supports batch tool calls in parallel
 * - Resolves prompts/resources using provided resolvers or capability scan fallback
 */
interface RequestDispatcher {
    suspend fun dispatchToolCall(request: ToolCallRequest): Result<JsonElement>
    suspend fun dispatchBatch(requests: List<ToolCallRequest>): List<Result<JsonElement>>
    suspend fun dispatchPrompt(name: String): Result<JsonObject>
    suspend fun dispatchResource(uri: String): Result<JsonObject>
}

class DefaultRequestDispatcher(
    private val servers: List<McpServerConnection>,
    private val allowedPrefixedTools: () -> Set<String> = { emptySet() },
    private val promptServerResolver: (suspend (String) -> String?)? = null,
    private val resourceServerResolver: (suspend (String) -> String?)? = null,
    private val namespace: NamespaceManager = DefaultNamespaceManager(),
    private val logger: Logger = ConsoleLogger
) : RequestDispatcher {
    private val multi = MultiServerClient(servers)

    override suspend fun dispatchToolCall(request: ToolCallRequest): Result<JsonElement> {
        val allowed = allowedPrefixedTools()
        val name = request.name
        if (allowed.isNotEmpty() && name !in allowed) {
            val msg = "Tool '$name' is not allowed by current preset"
            logger.warnJson("proxy.tool.denied") {
                put("toolName", JsonPrimitive(name))
                put("reason", JsonPrimitive(msg))
            }
            return Result.failure(IllegalArgumentException(msg))
        }
        return try {
            val (serverId, tool) = namespace.parsePrefixedToolName(name)
            val server = servers.firstOrNull { it.serverId == serverId }
                ?: return Result.failure(IllegalArgumentException("Unknown server: $serverId"))
            logger.infoJson("facade_to_downstream.request") {
                put("toolName", JsonPrimitive(request.name))
                put("resolvedServerId", JsonPrimitive(serverId))
                put("downstreamTool", JsonPrimitive(tool))
                put("arguments", request.arguments)
            }
            val result = server.callTool(tool, request.arguments)
            if (result.isSuccess) {
                logger.infoJson("downstream.response") {
                    put("toolName", JsonPrimitive(request.name))
                    put("resolvedServerId", JsonPrimitive(serverId))
                    put("downstreamTool", JsonPrimitive(tool))
                    put("response", result.getOrNull() ?: JsonNull)
                }
            } else {
                val failure = result.exceptionOrNull()
                logger.errorJson("downstream.response.error", failure) {
                    put("toolName", JsonPrimitive(request.name))
                    put("resolvedServerId", JsonPrimitive(serverId))
                    put("downstreamTool", JsonPrimitive(tool))
                    put("errorMessage", JsonPrimitive(failure?.message ?: "callTool failed"))
                }
            }
            result
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override suspend fun dispatchBatch(requests: List<ToolCallRequest>): List<Result<JsonElement>> = coroutineScope {
        requests.map { req -> async { dispatchToolCall(req) } }.awaitAll()
    }

    override suspend fun dispatchPrompt(name: String): Result<JsonObject> {
        val server = resolveServerForPrompt(name)
            ?: return Result.failure(IllegalArgumentException("Unknown prompt: $name"))
        logger.info("Routing prompt '$name' to server '${server.serverId}'")
        val result = server.getPrompt(name)
        if (result.isSuccess) {
            logger.info("Server '${server.serverId}' returned prompt '$name'")
        } else {
            logger.error("Server '${server.serverId}' failed to get prompt '$name'", result.exceptionOrNull())
        }
        return result
    }

    override suspend fun dispatchResource(uri: String): Result<JsonObject> {
        val server = resolveServerForResource(uri)
            ?: return Result.failure(IllegalArgumentException("Unknown resource: $uri"))
        logger.info("Routing resource '$uri' to server '${server.serverId}'")
        val result = server.readResource(uri)
        if (result.isSuccess) {
            logger.info("Server '${server.serverId}' returned resource '$uri'")
        } else {
            logger.error("Server '${server.serverId}' failed to read resource '$uri'", result.exceptionOrNull())
        }
        return result
    }

    private suspend fun resolveServerForPrompt(name: String): McpServerConnection? {
        val id = promptServerResolver?.invoke(name)
        if (id != null) return servers.firstOrNull { it.serverId == id }
        val all = multi.fetchAllCapabilities()
        val serverId = all.entries.firstOrNull { (_, caps) -> caps.prompts.any { it.name == name } }?.key
        return serverId?.let { sid -> servers.firstOrNull { it.serverId == sid } }
    }

    private suspend fun resolveServerForResource(uri: String): McpServerConnection? {
        val id = resourceServerResolver?.invoke(uri)
        if (id != null) return servers.firstOrNull { it.serverId == id }
        val all: Map<String, ServerCapabilities> = multi.fetchAllCapabilities()
        val serverId = all.entries.firstOrNull { (_, caps) -> caps.resources.any { (it.uri ?: it.name) == uri } }?.key
        return serverId?.let { sid -> servers.firstOrNull { it.serverId == sid } }
    }
}
