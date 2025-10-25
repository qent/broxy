package io.qent.bro.core.mcp

import io.qent.bro.core.models.McpServerConfig

interface McpServerConnection {
    val serverId: String
    val config: McpServerConfig
    val status: ServerStatus
}
