package io.qent.bro.core.mcp

import io.qent.bro.core.models.TransportConfig

expect object McpClientFactory {
    fun create(config: TransportConfig, env: Map<String, String> = emptyMap()): McpClient
}

