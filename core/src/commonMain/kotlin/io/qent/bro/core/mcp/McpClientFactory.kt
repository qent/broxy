package io.qent.bro.core.mcp

import io.qent.bro.core.models.TransportConfig

/**
 * Factory that delegates creation to an injected provider.
 * Use [defaultMcpClientProvider] on each platform to obtain the default provider.
 */
class McpClientFactory(private val provider: McpClientProvider) {
    fun create(config: TransportConfig, env: Map<String, String> = emptyMap()): McpClient =
        provider.create(config, env)
}

expect fun defaultMcpClientProvider(): McpClientProvider
