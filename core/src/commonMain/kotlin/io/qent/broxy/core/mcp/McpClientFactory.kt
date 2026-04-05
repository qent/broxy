package io.qent.broxy.core.mcp

import io.qent.broxy.core.mcp.auth.AuthorizationStatusListener
import io.qent.broxy.core.mcp.auth.OAuthState
import io.qent.broxy.core.models.AuthConfig
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger

/**
 * Factory that delegates creation to an injected provider.
 * Use [defaultMcpClientProvider] on each platform to obtain the default provider.
 */
class McpClientFactory(
    private val provider: McpClientProvider,
) {
    @Suppress("LongParameterList")
    fun create(
        serverId: String,
        config: TransportConfig,
        env: Map<String, String> = emptyMap(),
        ignoreHttpsCertificateErrors: Boolean = false,
        logger: Logger = ConsoleLogger,
        auth: AuthConfig? = null,
        authState: OAuthState? = null,
        authorizationStatusListener: AuthorizationStatusListener? = null,
    ): McpClient =
        provider.create(
            serverId,
            config,
            env,
            ignoreHttpsCertificateErrors,
            logger,
            auth,
            authState,
            authorizationStatusListener,
        )
}

expect fun defaultMcpClientProvider(): McpClientProvider
