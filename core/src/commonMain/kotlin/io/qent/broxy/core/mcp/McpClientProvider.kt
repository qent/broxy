package io.qent.broxy.core.mcp

import io.qent.broxy.core.mcp.auth.OAuthState
import io.qent.broxy.core.models.AuthConfig
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.utils.Logger

/**
 * Abstraction for providing platform-specific McpClient instances.
 */
fun interface McpClientProvider {
    fun create(
        config: TransportConfig,
        env: Map<String, String>,
        logger: Logger,
        auth: AuthConfig?,
        authState: OAuthState?,
    ): McpClient
}
