package io.qent.broxy.core.mcp

import io.qent.broxy.core.mcp.auth.AuthorizationStatusListener
import io.qent.broxy.core.mcp.auth.OAuthState
import io.qent.broxy.core.mcp.clients.KtorMcpClient
import io.qent.broxy.core.mcp.clients.StdioMcpClient
import io.qent.broxy.core.models.AuthConfig
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.utils.Logger

private object DefaultJvmMcpClientProvider : McpClientProvider {
    override fun create(
        serverId: String,
        config: TransportConfig,
        env: Map<String, String>,
        ignoreHttpsCertificateErrors: Boolean,
        logger: Logger,
        auth: AuthConfig?,
        authState: OAuthState?,
        authorizationStatusListener: AuthorizationStatusListener?,
    ): McpClient =
        when (config) {
            is TransportConfig.StdioTransport ->
                StdioMcpClient(
                    serverId = serverId,
                    command = config.command,
                    args = config.args,
                    env = env,
                    logger = logger,
                    authConfig = auth as? AuthConfig.OAuth,
                )

            is TransportConfig.HttpTransport ->
                KtorMcpClient(
                    mode = KtorMcpClient.Mode.Sse,
                    url = config.url,
                    headersMap = config.headers,
                    logger = logger,
                    authConfig = auth,
                    authState = authState,
                    ignoreHttpsCertificateErrors = ignoreHttpsCertificateErrors,
                    authorizationStatusListener = authorizationStatusListener,
                )

            is TransportConfig.StreamableHttpTransport ->
                KtorMcpClient(
                    mode = KtorMcpClient.Mode.StreamableHttp,
                    url = config.url,
                    headersMap = config.headers,
                    logger = logger,
                    authConfig = auth,
                    authState = authState,
                    ignoreHttpsCertificateErrors = ignoreHttpsCertificateErrors,
                    authorizationStatusListener = authorizationStatusListener,
                )

            is TransportConfig.WebSocketTransport ->
                KtorMcpClient(
                    mode = KtorMcpClient.Mode.WebSocket,
                    url = config.url,
                    headersMap = config.headers,
                    logger = logger,
                    authConfig = auth,
                    authState = authState,
                    ignoreHttpsCertificateErrors = ignoreHttpsCertificateErrors,
                    authorizationStatusListener = authorizationStatusListener,
                )
        }
}

actual fun defaultMcpClientProvider(): McpClientProvider = DefaultJvmMcpClientProvider
