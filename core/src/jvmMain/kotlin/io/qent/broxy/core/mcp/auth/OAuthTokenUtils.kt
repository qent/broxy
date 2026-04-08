package io.qent.broxy.core.mcp.auth

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import io.ktor.http.HttpHeaders
import io.qent.broxy.core.utils.Logger
import java.util.Base64
import kotlin.math.max

internal fun resolveTokenEndpointAuthMethod(
    configured: String?,
    clientSecret: String?,
    supported: List<String>?,
): String {
    val method =
        configured?.trim()?.lowercase()
            ?: if (!clientSecret.isNullOrBlank()) "client_secret_basic" else "none"
    if (supported != null && supported.none { it.equals(method, ignoreCase = true) }) {
        error("Token endpoint auth method '$method' is not supported by the authorization server.")
    }
    return method
}

internal fun resolveRegisteredTokenEndpointAuthMethod(
    configured: String?,
    registered: String?,
    clientSecret: String?,
    supported: List<String>?,
): String {
    val normalizedRegistered = registered?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
    val normalizedConfigured = configured?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
    val hasClientSecret = !clientSecret.isNullOrBlank()
    val (method, enforceSupportedValidation) =
        when {
            !normalizedRegistered.isNullOrBlank() -> {
                // Some providers return registration values that are stricter than (or inconsistent with)
                // discovery metadata, so dynamic registration should trust the server-issued method.
                normalizedRegistered to false
            }
            !normalizedConfigured.isNullOrBlank() -> normalizedConfigured to true
            hasClientSecret -> {
                val supportedLower =
                    supported
                        ?.map { it.trim().lowercase() }
                        ?.filter { it.isNotBlank() }
                        .orEmpty()
                val inferred =
                    when {
                        supportedLower.contains("client_secret_basic") -> "client_secret_basic"
                        supportedLower.contains("client_secret_post") -> "client_secret_post"
                        supportedLower.contains("none") -> "none"
                        else -> "client_secret_basic"
                    }
                inferred to true
            }
            else -> "none" to true
        }
    val supportedForValidation = if (enforceSupportedValidation) supported else null
    return resolveTokenEndpointAuthMethod(method, clientSecret, supportedForValidation)
}

internal fun HttpRequestBuilder.applyClientAuthHeaders(registration: OAuthClientRegistration) {
    if (registration.tokenEndpointAuthMethod == "client_secret_basic" && !registration.clientSecret.isNullOrBlank()) {
        val raw = "${registration.clientId}:${registration.clientSecret}"
        val encoded = Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
        headers { append(HttpHeaders.Authorization, "Basic $encoded") }
    }
}

internal fun toToken(
    response: OAuthTokenResponse,
    nowMillis: Long,
): OAuthToken {
    val expiry =
        response.expiresIn?.let {
            nowMillis + max(0, it * MILLIS_PER_SECOND - EXPIRY_SKEW_MILLIS)
        }
    return OAuthToken(
        accessToken = response.accessToken,
        tokenType = response.tokenType ?: "Bearer",
        refreshToken = response.refreshToken,
        scope = response.scope,
        expiresAtEpochMillis = expiry,
    )
}

internal fun selectScope(
    challengeScope: String?,
    scopesSupported: List<String>?,
    fallback: List<String>?,
): String? {
    val supportedScope =
        scopesSupported
            ?.filter { it.isNotBlank() }
            ?.joinToString(" ")
            ?.takeIf { it.isNotBlank() }
    val fallbackScope =
        fallback
            ?.filter { it.isNotBlank() }
            ?.joinToString(" ")
            ?.takeIf { it.isNotBlank() }
    return when {
        !challengeScope.isNullOrBlank() -> challengeScope
        !supportedScope.isNullOrBlank() -> supportedScope
        else -> fallbackScope
    }
}

internal fun isExpired(
    token: OAuthToken,
    nowMillis: Long,
): Boolean {
    val expiry = token.expiresAtEpochMillis ?: return false
    return nowMillis >= expiry
}

internal fun tokenSatisfiesScope(
    token: OAuthToken,
    requiredScope: String?,
): Boolean {
    val required =
        requiredScope
            ?.split(" ")
            ?.filter { it.isNotBlank() }
            .orEmpty()
            .toSet()
    val available =
        token.scope
            ?.split(" ")
            ?.filter { it.isNotBlank() }
            .orEmpty()
            .toSet()
    return required.isEmpty() || available.containsAll(required)
}

internal fun resolveAuthorizationTimeoutMillis(authorizationTimeoutMillis: Long?): Long {
    val configured = authorizationTimeoutMillis
    return if (configured != null && configured > 0) configured else DEFAULT_AUTH_CODE_TIMEOUT_MILLIS
}

@Suppress("LongParameterList")
/**
 * Requires the caller to hold [OAuthState.mutex].
 */
internal suspend fun resolveAccessTokenLocked(
    state: OAuthState,
    requiredScope: String?,
    nowMillis: Long,
    logger: Logger,
    resourceUrl: String,
    refreshToken: suspend (String, String?) -> OAuthToken?,
): String? {
    val token = state.token
    var accessToken: String? = null
    if (token != null) {
        val expired = isExpired(token, nowMillis)
        val scopeSatisfied = tokenSatisfiesScope(token, requiredScope)
        if (!expired && scopeSatisfied) {
            logger.debug("OAuth token valid for $resourceUrl; skipping authorization")
            accessToken = token.accessToken
        } else if (expired && !token.refreshToken.isNullOrBlank()) {
            logger.debug("OAuth token expired; attempting refresh for $resourceUrl")
            val refreshed = refreshToken(token.refreshToken, requiredScope)
            if (refreshed != null && tokenSatisfiesScope(refreshed, requiredScope)) {
                state.token = refreshed
                logger.debug("OAuth token refresh succeeded for $resourceUrl")
                accessToken = refreshed.accessToken
            } else {
                logger.debug("OAuth token refresh failed or insufficient scope for $resourceUrl")
            }
        }
    }
    return accessToken
}
