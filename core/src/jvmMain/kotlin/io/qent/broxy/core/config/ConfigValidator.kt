package io.qent.broxy.core.config

import io.qent.broxy.core.models.AuthConfig
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.TransportConfig
import java.net.URI
import java.net.URISyntaxException

internal class ConfigValidator(
    private val errors: ConfigErrorHandler,
) {
    fun validate(config: McpServersConfig) {
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
        auth: AuthConfig,
    ) {
        when (auth) {
            is AuthConfig.OAuth -> validateOAuthConfig(serverId, auth)
        }
    }

    private fun validateOAuthConfig(
        serverId: String,
        auth: AuthConfig.OAuth,
    ) {
        validateRedirectUri(serverId, auth.redirectUri)
        validateClientIdMetadataUrl(serverId, auth.clientIdMetadataUrl)
        validateAuthorizationServer(serverId, auth.authorizationServer)
        validateTokenEndpointAuthMethod(errors, serverId, auth.tokenEndpointAuthMethod)
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
        if (server.transport is TransportConfig.StdioTransport) {
            errors.fail("Server '${server.id}': auth is not supported for stdio transport")
        }
        validateAuthConfig(server.id, auth)
    }

    private fun validateRedirectUri(
        serverId: String,
        redirectUri: String?,
    ) {
        if (redirectUri == null) return
        val uri = parseUri(errors, serverId, "auth.redirectUri", redirectUri)
        val scheme = uri.scheme?.lowercase() ?: errors.fail("Server '$serverId': auth.redirectUri missing scheme")
        val host = uri.host ?: errors.fail("Server '$serverId': auth.redirectUri missing host")
        val isLoopback = host == "localhost" || host == "127.0.0.1"
        if (scheme != "http" || !isLoopback) {
            errors.fail("Server '$serverId': auth.redirectUri must use http://localhost or http://127.0.0.1")
        }
        if (uri.port == -1) {
            errors.fail("Server '$serverId': auth.redirectUri must include an explicit port")
        }
    }

    private fun validateClientIdMetadataUrl(
        serverId: String,
        clientIdMetadataUrl: String?,
    ) {
        if (clientIdMetadataUrl == null) return
        val uri = parseUri(errors, serverId, "auth.clientIdMetadataUrl", clientIdMetadataUrl)
        val scheme =
            uri.scheme?.lowercase()
                ?: errors.fail("Server '$serverId': auth.clientIdMetadataUrl missing scheme")
        if (scheme != "https") {
            errors.fail("Server '$serverId': auth.clientIdMetadataUrl must use https")
        }
        if (uri.path.isNullOrBlank() || uri.path == "/") {
            errors.fail("Server '$serverId': auth.clientIdMetadataUrl must include a path")
        }
    }

    private fun validateAuthorizationServer(
        serverId: String,
        authorizationServer: String?,
    ) {
        if (authorizationServer == null) return
        val uri = parseUri(errors, serverId, "auth.authorizationServer", authorizationServer)
        val scheme =
            uri.scheme?.lowercase()
                ?: errors.fail("Server '$serverId': auth.authorizationServer missing scheme")
        val host = uri.host ?: errors.fail("Server '$serverId': auth.authorizationServer missing host")
        val isLoopback = host == "localhost" || host == "127.0.0.1"
        if (scheme != "https" && !(scheme == "http" && isLoopback)) {
            errors.fail("Server '$serverId': auth.authorizationServer must use https or localhost http")
        }
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
        errors.fail("Server '$serverId': auth.tokenEndpointAuthMethod must be one of ${allowed.joinToString()}")
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
