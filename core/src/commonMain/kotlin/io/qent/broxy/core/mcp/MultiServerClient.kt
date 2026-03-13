package io.qent.broxy.core.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class MultiServerClient(
    private val servers: List<McpServerConnection>,
    private val namespace: io.qent.broxy.core.proxy.NamespaceManager =
        io.qent.broxy.core.proxy
            .DefaultNamespaceManager(),
) {
    suspend fun fetchAllCapabilities(): Map<String, ServerCapabilities> = collectCapabilities(servers)

    fun listPrefixedTools(allCaps: Map<String, ServerCapabilities>): List<ToolDescriptor> =
        allCaps.flatMap { (serverId, caps) ->
            caps.tools.map { t ->
                t.copy(name = namespace.prefixToolName(serverId, t.name))
            }
        }

    suspend fun callPrefixedTool(
        name: String,
        arguments: JsonObject = JsonObject(emptyMap()),
    ): Result<JsonElement> {
        val (serverId, tool) =
            runCatching { namespace.parsePrefixedToolName(name) }
                .getOrElse { return Result.failure(it) }
        val server = servers.firstOrNull { it.serverId == serverId }
        return if (server == null) {
            Result.failure(IllegalArgumentException("Unknown server: $serverId"))
        } else {
            server.callTool(tool, arguments)
        }
    }

    // Backwards-compat helper methods: delegate to NamespaceManager
    fun prefixToolName(
        serverId: String,
        toolName: String,
    ): String = namespace.prefixToolName(serverId, toolName)

    fun parsePrefixedName(name: String): Pair<String, String> = namespace.parsePrefixedToolName(name)
}
