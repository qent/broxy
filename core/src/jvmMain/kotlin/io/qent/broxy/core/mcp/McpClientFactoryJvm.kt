package io.qent.broxy.core.mcp

import io.qent.broxy.core.mcp.auth.OAuthState
import io.qent.broxy.core.mcp.clients.KtorMcpClient
import io.qent.broxy.core.mcp.clients.StdioMcpClient
import io.qent.broxy.core.models.AuthConfig
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.utils.Logger

private object DefaultJvmMcpClientProvider : McpClientProvider {
    override fun create(
        config: TransportConfig,
        env: Map<String, String>,
        logger: Logger,
        auth: AuthConfig?,
        authState: OAuthState?,
    ): McpClient =
        when (config) {
            is TransportConfig.StdioTransport ->
                StdioMcpClient(
                    command = config.command,
                    args = config.args,
                    env = env,
                    logger = logger,
                )

            is TransportConfig.HttpTransport ->
                KtorMcpClient(
                    mode = KtorMcpClient.Mode.Sse,
                    url = config.url,
                    headersMap = config.headers,
                    logger = logger,
                    authConfig = auth,
                    authState = authState,
                )

            is TransportConfig.StreamableHttpTransport ->
                KtorMcpClient(
                    mode = KtorMcpClient.Mode.StreamableHttp,
                    url = config.url,
                    headersMap = config.headers,
                    logger = logger,
                    authConfig = auth,
                    authState = authState,
                )

            is TransportConfig.WebSocketTransport ->
                KtorMcpClient(
                    mode = KtorMcpClient.Mode.WebSocket,
                    url = config.url,
                    logger = logger,
                    authConfig = auth,
                    authState = authState,
                )
        }
}

actual fun defaultMcpClientProvider(): McpClientProvider = DefaultJvmMcpClientProvider
