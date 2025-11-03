package io.qent.broxy.core.mcp

import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger

/**
 * Factory that delegates creation to an injected provider.
 * Use [defaultMcpClientProvider] on each platform to obtain the default provider.
 */
class McpClientFactory(private val provider: McpClientProvider) {
    fun create(
        config: TransportConfig,
        env: Map<String, String> = emptyMap(),
        logger: Logger = ConsoleLogger
    ): McpClient = provider.create(config, env, logger)
}

expect fun defaultMcpClientProvider(): McpClientProvider
