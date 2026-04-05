package io.qent.broxy.core.config

import io.qent.broxy.core.models.AuthConfig
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.TransportConfig
import java.net.URI
import java.net.URISyntaxException

@Suppress("TooManyFunctions")
internal class ConfigValidator(
    private val errors: ConfigErrorHandler,
) {
    fun validate(config: McpServersConfig) {
        if (config.mcpFilePath.isBlank()) {
            errors.fail("mcpFilePath cannot be blank")
        }
        validateServers(config.servers)
    }

    private fun validateServers(servers: List<McpServerConfig>) {
        if (servers.isEmpty()) return
        val ids = servers.map { it.id }
        val dup = ids.groupBy { it }.filterValues { it.size > 1 }.keys
        if (dup.isNotEmpty()) errors.fail("Duplicate server IDs: ${dup.joinToString()}")
        servers.forEach { s ->
            validateServerBasics(s)
            validateTransport(s.id, s.transport)
            validateAuth(s)
        }
    }

    private fun validateAuthConfig(
        serverId: String,
        transport: TransportConfig,
        auth: AuthConfig,
    ) {
        when (auth) {
            is AuthConfig.OAuth -> validateOAuthConfig(serverId, transport, auth)
        }
    }

    private fun validateOAuthConfig(
        serverId: String,
        transport: TransportConfig,
        auth: AuthConfig.OAuth,
    ) {
        validateCallbackPort(serverId, auth.callbackPort)
        validateRedirectUri(serverId, auth.redirectUri)
        validateClientIdMetadataUrl(serverId, auth.clientIdMetadataUrl)
        validateAuthServerMetadataUrl(serverId, auth.authServerMetadataUrl)
        validateAuthorizationServer(serverId, auth.authorizationServer)
        validateTokenEndpointAuthMethod(errors, serverId, auth.tokenEndpointAuthMethod)
        validateStdioBootstrap(serverId, transport, auth.stdioBootstrap)
    }

    private fun validateServerBasics(server: McpServerConfig) {
        if (server.id.isBlank()) errors.fail("Server id cannot be blank")
        if (server.name.isBlank()) errors.fail("Server '${server.id}': name cannot be blank")
    }

    private fun validateTransport(
        serverId: String,
        transport: TransportConfig,
    ) {
        when (transport) {
            is TransportConfig.StdioTransport ->
                if (transport.command.isBlank()) {
                    errors.fail("Server '$serverId': stdio.command cannot be blank")
                }
            is TransportConfig.HttpTransport ->
                if (transport.url.isBlank()) {
                    errors.fail("Server '$serverId': sse.url cannot be blank")
                }
            is TransportConfig.StreamableHttpTransport ->
                if (transport.url.isBlank()) {
                    errors.fail("Server '$serverId': http.url cannot be blank")
                }
            is TransportConfig.WebSocketTransport ->
                if (transport.url.isBlank()) {
                    errors.fail("Server '$serverId': ws.url cannot be blank")
                }
        }
    }

    private fun validateAuth(server: McpServerConfig) {
        val auth = server.auth ?: return
        validateAuthConfig(server.id, server.transport, auth)
    }

    private fun validateStdioBootstrap(
        serverId: String,
        transport: TransportConfig,
        stdioBootstrap: AuthConfig.StdioBootstrap?,
    ) {
        if (stdioBootstrap == null) return
        if (transport !is TransportConfig.StdioTransport) {
            errors.fail("Server '$serverId': oauth.stdioBootstrap is supported only for stdio transport")
        }
        if (stdioBootstrap.tool.isBlank()) {
            errors.fail("Server '$serverId': oauth.stdioBootstrap.tool cannot be blank")
        }
        stdioBootstrap.args.keys.forEach { key ->
            if (key.isBlank()) {
                errors.fail("Server '$serverId': oauth.stdioBootstrap.args keys cannot be blank")
            }
        }
    }

    private fun validateRedirectUri(
        serverId: String,
        redirectUri: String?,
    ) {
        if (redirectUri == null) return
        val uri = parseUri(errors, serverId, "oauth.redirectUri", redirectUri)
        val scheme = uri.scheme?.lowercase() ?: errors.fail("Server '$serverId': oauth.redirectUri missing scheme")
        val host = uri.host ?: errors.fail("Server '$serverId': oauth.redirectUri missing host")
        val isLoopback = host == "localhost" || host == "127.0.0.1"
        val isSupportedScheme = scheme == "http" || scheme == "https"
        if (!isSupportedScheme || !isLoopback) {
            errors.fail(
                "Server '$serverId': oauth.redirectUri must use http://localhost, https://localhost, " +
                    "http://127.0.0.1, or https://127.0.0.1",
            )
        }
        if (uri.port == -1) {
            errors.fail("Server '$serverId': oauth.redirectUri must include an explicit port")
        }
    }

    private fun validateClientIdMetadataUrl(
        serverId: String,
        clientIdMetadataUrl: String?,
    ) {
        if (clientIdMetadataUrl == null) return
        val uri = parseUri(errors, serverId, "oauth.clientIdMetadataUrl", clientIdMetadataUrl)
        val scheme =
            uri.scheme?.lowercase()
                ?: errors.fail("Server '$serverId': oauth.clientIdMetadataUrl missing scheme")
        if (scheme != "https") {
            errors.fail("Server '$serverId': oauth.clientIdMetadataUrl must use https")
        }
        if (uri.path.isNullOrBlank() || uri.path == "/") {
            errors.fail("Server '$serverId': oauth.clientIdMetadataUrl must include a path")
        }
    }

    private fun validateAuthServerMetadataUrl(
        serverId: String,
        authServerMetadataUrl: String?,
    ) {
        if (authServerMetadataUrl == null) return
        val uri = parseUri(errors, serverId, "oauth.authServerMetadataUrl", authServerMetadataUrl)
        val scheme =
            uri.scheme?.lowercase()
                ?: errors.fail("Server '$serverId': oauth.authServerMetadataUrl missing scheme")
        if (scheme != "https") {
            errors.fail("Server '$serverId': oauth.authServerMetadataUrl must use https")
        }
    }

    private fun validateAuthorizationServer(
        serverId: String,
        authorizationServer: String?,
    ) {
        if (authorizationServer == null) return
        val uri = parseUri(errors, serverId, "oauth.authorizationServer", authorizationServer)
        val scheme =
            uri.scheme?.lowercase()
                ?: errors.fail("Server '$serverId': oauth.authorizationServer missing scheme")
        val host = uri.host ?: errors.fail("Server '$serverId': oauth.authorizationServer missing host")
        val isLoopback = host == "localhost" || host == "127.0.0.1"
        if (scheme != "https" && !(scheme == "http" && isLoopback)) {
            errors.fail("Server '$serverId': oauth.authorizationServer must use https or localhost http")
        }
    }

    private fun validateCallbackPort(
        serverId: String,
        callbackPort: Int?,
    ) {
        if (callbackPort == null) return
        if (callbackPort !in MIN_PORT..MAX_PORT) {
            errors.fail("Server '$serverId': oauth.callbackPort must be between $MIN_PORT and $MAX_PORT")
        }
    }

    private companion object {
        private const val MIN_PORT = 1
        private const val MAX_PORT = 65535
    }
}

private fun validateTokenEndpointAuthMethod(
    errors: ConfigErrorHandler,
    serverId: String,
    tokenEndpointAuthMethod: String?,
) {
    if (tokenEndpointAuthMethod == null) return
    val normalized = tokenEndpointAuthMethod.lowercase()
    val allowed = setOf("none", "client_secret_basic", "client_secret_post")
    if (normalized !in allowed) {
        errors.fail("Server '$serverId': oauth.tokenEndpointAuthMethod must be one of ${allowed.joinToString()}")
    }
}

private fun parseUri(
    errors: ConfigErrorHandler,
    serverId: String,
    label: String,
    value: String,
): URI =
    try {
        URI(value)
    } catch (e: URISyntaxException) {
        errors.fail("Server '$serverId': $label is not a valid URI", e)
    }
