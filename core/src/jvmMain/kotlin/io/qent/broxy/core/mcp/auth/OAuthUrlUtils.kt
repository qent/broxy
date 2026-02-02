package io.qent.broxy.core.mcp.auth

import io.ktor.http.URLBuilder
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal fun validatePkceSupport(metadata: AuthorizationServerMetadata) {
    val supported =
        metadata.codeChallengeMethodsSupported
            ?.any { it.equals(PKCE_METHOD_S256, ignoreCase = true) } == true
    if (!supported) {
        error("Authorization server does not advertise PKCE S256 support.")
    }
}

@Suppress("LongParameterList")
internal fun buildAuthorizationUrl(
    authorizationEndpoint: String?,
    clientId: String,
    redirectUri: String,
    scope: String?,
    state: String,
    codeChallenge: String,
    resourceUri: String,
): String {
    val endpoint = authorizationEndpoint ?: error("Missing authorization endpoint")
    val builder = URLBuilder(endpoint)
    builder.parameters.append("response_type", "code")
    builder.parameters.append("client_id", clientId)
    builder.parameters.append("redirect_uri", redirectUri)
    builder.parameters.append("state", state)
    builder.parameters.append("code_challenge", codeChallenge)
    builder.parameters.append("code_challenge_method", PKCE_METHOD_S256)
    builder.parameters.append("resource", resourceUri)
    if (!scope.isNullOrBlank()) {
        builder.parameters.append("scope", scope)
    }
    return builder.buildString()
}

internal fun generateCodeVerifier(random: SecureRandom): String {
    val bytes = ByteArray(CODE_VERIFIER_BYTES)
    random.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

internal fun generateCodeChallenge(verifier: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hashed = digest.digest(verifier.toByteArray(Charsets.US_ASCII))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed)
}

internal fun generateState(random: SecureRandom): String {
    val bytes = ByteArray(STATE_BYTES)
    random.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

internal fun canonicalResourceUri(
    resourceUrl: String,
    resourceMetadata: ProtectedResourceMetadata?,
): String {
    val rawResource = resourceMetadata?.resource?.takeIf { it.isNotBlank() } ?: resourceUrl
    val uri = URI(rawResource)
    val scheme = uri.scheme?.lowercase() ?: error("Resource URL is missing scheme: $rawResource")
    val host = uri.host?.lowercase() ?: error("Resource URL is missing host: $rawResource")
    var path = uri.path ?: ""
    if (path == "/") {
        path = ""
    } else if (path.endsWith("/")) {
        path = path.removeSuffix("/")
    }
    return URI(scheme, null, host, uri.port, if (path.isBlank()) null else path, null, null).toString()
}

internal fun buildProtectedResourceMetadataUrls(resource: String): List<String> {
    val uri = URI(resource)
    val scheme = uri.scheme ?: error("Resource URL is missing scheme: $resource")
    val host = uri.host ?: error("Resource URL is missing host: $resource")
    val port = if (uri.port == -1) "" else ":${uri.port}"
    val origin = "$scheme://$host$port"
    val path = uri.path?.takeIf { it.isNotBlank() && it != "/" } ?: ""
    val urls = mutableListOf<String>()
    if (path.isNotBlank()) {
        urls.add("$origin/.well-known/oauth-protected-resource$path")
    }
    urls.add("$origin/.well-known/oauth-protected-resource")
    return urls
}

internal fun buildAuthorizationServerMetadataUrls(issuer: String): List<String> {
    val uri = URI(issuer)
    val scheme = uri.scheme ?: error("Authorization server URL is missing scheme: $issuer")
    val host = uri.host ?: error("Authorization server URL is missing host: $issuer")
    val port = if (uri.port == -1) "" else ":${uri.port}"
    val origin = "$scheme://$host$port"
    val path = uri.path?.takeIf { it.isNotBlank() && it != "/" } ?: ""
    val urls =
        if (path.isNotBlank()) {
            listOf(
                "$origin/.well-known/oauth-authorization-server$path",
                "$origin/.well-known/openid-configuration$path",
                "$origin$path/.well-known/openid-configuration",
            )
        } else {
            listOf(
                "$origin/.well-known/oauth-authorization-server",
                "$origin/.well-known/openid-configuration",
            )
        }
    return urls
}
