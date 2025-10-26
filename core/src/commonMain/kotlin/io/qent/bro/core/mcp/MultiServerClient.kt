package io.qent.bro.core.mcp

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class MultiServerClient(
    private val servers: List<McpServerConnection>
) {
    suspend fun fetchAllCapabilities(): Map<String, ServerCapabilities> = coroutineScope {
        servers.map { server ->
            async {
                val caps = server.getCapabilities()
                if (caps.isSuccess) server.serverId to caps.getOrThrow() else null
            }
        }.awaitAll().filterNotNull().toMap()
    }

    fun listPrefixedTools(allCaps: Map<String, ServerCapabilities>): List<ToolDescriptor> {
        return allCaps.flatMap { (serverId, caps) ->
            caps.tools.map { t ->
                ToolDescriptor(name = prefixToolName(serverId, t.name), description = t.description)
            }
        }
    }

    suspend fun callPrefixedTool(name: String, arguments: JsonObject = JsonObject(emptyMap())): Result<JsonElement> {
        val (serverId, tool) = parsePrefixedName(name)
        val server = servers.firstOrNull { it.serverId == serverId }
            ?: return Result.failure(IllegalArgumentException("Unknown server: $serverId"))
        return server.callTool(tool, arguments)
    }

    fun prefixToolName(serverId: String, toolName: String): String = "$serverId:$toolName"

    fun parsePrefixedName(name: String): Pair<String, String> {
        val idx = name.indexOf(':')
        require(idx > 0 && idx < name.length - 1) { "Tool name must be in 'serverId:toolName' format" }
        val serverId = name.substring(0, idx)
        val tool = name.substring(idx + 1)
        return serverId to tool
    }
}

