package io.qent.bro.core.mcp

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class MultiServerClient(
    private val servers: List<McpServerConnection>,
    private val namespace: io.qent.bro.core.proxy.NamespaceManager = io.qent.bro.core.proxy.DefaultNamespaceManager()
) {
    suspend fun fetchAllCapabilities(): Map<String, ServerCapabilities> = coroutineScope {
        servers.map { server ->
            async {
                val caps = server.getCapabilities()
                if (caps.isSuccess) server.serverId to caps.getOrThrow() else null
            }
        }.awaitAll().filterNotNull().toMap()
    }

    fun listPrefixedTools(allCaps: Map<String, ServerCapabilities>): List<ToolDescriptor> =
        allCaps.flatMap { (serverId, caps) ->
            caps.tools.map { t ->
                ToolDescriptor(name = namespace.prefixToolName(serverId, t.name), description = t.description)
            }
        }

    suspend fun callPrefixedTool(name: String, arguments: JsonObject = JsonObject(emptyMap())): Result<JsonElement> {
        val (serverId, tool) = namespace.parsePrefixedToolName(name)
        val server = servers.firstOrNull { it.serverId == serverId }
            ?: return Result.failure(IllegalArgumentException("Unknown server: $serverId"))
        return server.callTool(tool, arguments)
    }

    // Backwards-compat helper methods: delegate to NamespaceManager
    fun prefixToolName(serverId: String, toolName: String): String = namespace.prefixToolName(serverId, toolName)
    fun parsePrefixedName(name: String): Pair<String, String> = namespace.parsePrefixedToolName(name)
}
