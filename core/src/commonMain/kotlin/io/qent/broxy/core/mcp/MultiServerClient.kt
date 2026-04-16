package io.qent.broxy.core.mcp

import io.qent.broxy.core.proxy.DefaultNamespaceManager
import io.qent.broxy.core.proxy.NamespaceManager

class MultiServerClient(
    private val servers: List<McpServerConnection>,
    private val namespace: NamespaceManager = DefaultNamespaceManager(),
) {
    suspend fun fetchAllCapabilities(): Map<String, ServerCapabilities> = collectCapabilities(servers)

    // Backwards-compat helper methods: delegate to NamespaceManager
    fun prefixToolName(
        serverId: String,
        toolName: String,
    ): String = namespace.prefixToolName(serverId, toolName)
}
