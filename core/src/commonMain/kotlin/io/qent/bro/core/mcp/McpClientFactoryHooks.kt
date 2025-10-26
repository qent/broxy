package io.qent.bro.core.mcp

import io.qent.bro.core.models.TransportConfig

object McpClientFactoryHooks {
    @Volatile
    var provider: ((config: TransportConfig, env: Map<String, String>) -> McpClient?)? = null
}

