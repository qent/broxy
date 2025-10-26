package io.qent.bro.core.proxy

import io.qent.bro.core.mcp.McpServerConnection
import io.qent.bro.core.mcp.MultiServerClient
import io.qent.bro.core.mcp.ServerCapabilities
import io.qent.bro.core.utils.ConsoleLogger
import io.qent.bro.core.utils.Logger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

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
            logger.warn(msg)
            return Result.failure(IllegalArgumentException(msg))
        }
        return try {
            val (serverId, tool) = namespace.parsePrefixedToolName(name)
            val server = servers.firstOrNull { it.serverId == serverId }
                ?: return Result.failure(IllegalArgumentException("Unknown server: $serverId"))
            server.callTool(tool, request.arguments)
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
        return server.getPrompt(name)
    }

    override suspend fun dispatchResource(uri: String): Result<JsonObject> {
        val server = resolveServerForResource(uri)
            ?: return Result.failure(IllegalArgumentException("Unknown resource: $uri"))
        return server.readResource(uri)
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

