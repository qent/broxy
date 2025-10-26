package io.qent.bro.core.proxy

import io.qent.bro.core.mcp.McpServerConnection
import io.qent.bro.core.mcp.MultiServerClient
import io.qent.bro.core.mcp.ToolDescriptor
import io.qent.bro.core.utils.ConsoleLogger
import io.qent.bro.core.utils.Logger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Routes tool calls to appropriate downstream server connection.
 * - Accepts prefixed tool names: `serverId:toolName`
 * - Validates that the tool is allowed by current filter
 * - Removes prefix before forwarding the call downstream
 */
interface RequestRouter {
    suspend fun call(toolName: String, arguments: JsonObject = JsonObject(emptyMap())): Result<JsonElement>
}

class DefaultRequestRouter(
    private val servers: List<McpServerConnection>,
    private val allowedPrefixedTools: () -> Set<String>,
    private val logger: Logger = ConsoleLogger
) : RequestRouter {
    private val multi = MultiServerClient(servers)

    override suspend fun call(toolName: String, arguments: JsonObject): Result<JsonElement> {
        // Ensure the tool is allowed by current filter
        val allowed = allowedPrefixedTools()
        if (allowed.isNotEmpty() && toolName !in allowed) {
            val msg = "Tool '$toolName' is not allowed by current preset"
            logger.warn(msg)
            return Result.failure(IllegalArgumentException(msg))
        }
        // Route to downstream based on prefix
        return try {
            multi.callPrefixedTool(toolName, arguments)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}

