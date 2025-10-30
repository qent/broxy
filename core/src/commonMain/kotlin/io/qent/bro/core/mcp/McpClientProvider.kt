package io.qent.bro.core.mcp

import io.qent.bro.core.models.TransportConfig
import io.qent.bro.core.utils.Logger

/**
 * Abstraction for providing platform-specific McpClient instances.
 */
fun interface McpClientProvider {
    fun create(config: TransportConfig, env: Map<String, String>, logger: Logger): McpClient
}
