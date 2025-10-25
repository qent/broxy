package io.qent.bro.core.mcp

import io.qent.bro.core.models.McpServerConfig

interface McpServerManager {
    fun startServer(config: McpServerConfig): McpServerConnection

    fun stopServer(serverId: String)

    fun getActiveServers(): List<McpServerConnection>
}
