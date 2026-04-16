package io.qent.broxy.core.mcp.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.ParametersBuilder
import io.ktor.http.isSuccess
import io.qent.broxy.core.utils.Logger
import kotlinx.serialization.json.Json

internal class OAuthTokenService(
    private val state: OAuthState,
    private val resourceUrl: String,
    private val logger: Logger,
    private val json: Json,
    private val httpClientProvider: () -> HttpClient,
    private val clockMillis: () -> Long,
) {
    private data class TokenRequestSuccess(
        val registration: OAuthClientRegistration,
        val token: OAuthToken,
    )

    private data class TokenRequestFailure(
        val status: String,
        val body: String,
    )

    private sealed class TokenRequestResult {
        data class Success(
            val value: TokenRequestSuccess,
        ) : TokenRequestResult()

        data class Failure(
            val value: TokenRequestFailure,
        ) : TokenRequestResult()
    }

    /**
     * Requires the caller to hold [OAuthState.mutex].
     */
    suspend fun exchangeAuthorizationCode(
        authMeta: AuthorizationServerMetadata,
        registration: OAuthClientRegistration,
        code: String,
        redirectUri: String,
        codeVerifier: String,
    ): OAuthToken {
        logger.debug("OAuth exchanging authorization code for token at ${authMeta.tokenEndpoint}")
        val tokenEndpoint = authMeta.tokenEndpoint ?: error("Missing token endpoint")
        val resourceUri = canonicalResourceUri(resourceUrl, state.resourceMetadata)
        return when (
            val result =
                requestTokenWithFallback(
                    registration = registration,
                    supported = authMeta.tokenEndpointAuthMethodsSupported,
                    tokenEndpoint = tokenEndpoint,
                    buildParameters = { effectiveRegistration ->
                        Parameters.build {
                            append("grant_type", "authorization_code")
                            append("code", code)
                            append("redirect_uri", redirectUri)
                            append("client_id", registration.clientId)
                            append("code_verifier", codeVerifier)
                            append("resource", resourceUri)
                            appendClientSecret(effectiveRegistration)
                        }
                    },
                    retryLog = { method ->
                        "OAuth token exchange failed with auth method '$method' for $resourceUrl; retrying."
                    },
                )
        ) {
            is TokenRequestResult.Success -> {
                logger.debug("OAuth token endpoint response received for $resourceUrl")
                applyRecoveredRegistration(
                    previous = registration,
                    current = result.value.registration,
                    context = "token exchange",
                )
                result.value.token
            }
            is TokenRequestResult.Failure -> {
                error("Token request failed: ${result.value.status} ${result.value.body}")
            }
        }
    }

    /**
     * Requires the caller to hold [OAuthState.mutex].
     */
    suspend fun refreshToken(
        refreshToken: String,
        scope: String?,
    ): OAuthToken? {
        val registration = state.registration
        val authMeta = state.authorizationMetadata
        val tokenEndpoint = authMeta?.tokenEndpoint
        if (registration == null || tokenEndpoint == null) {
            return null
        }
        logger.debug("OAuth refreshing token for $resourceUrl")
        val resourceUri = canonicalResourceUri(resourceUrl, state.resourceMetadata)
        return when (
            val result =
                requestTokenWithFallback(
                    registration = registration,
                    supported = authMeta.tokenEndpointAuthMethodsSupported,
                    tokenEndpoint = tokenEndpoint,
                    buildParameters = { effectiveRegistration ->
                        Parameters.build {
                            append("grant_type", "refresh_token")
                            append("refresh_token", refreshToken)
                            append("client_id", registration.clientId)
                            append("resource", resourceUri)
                            if (!scope.isNullOrBlank()) append("scope", scope)
                            appendClientSecret(effectiveRegistration)
                        }
                    },
                    retryLog = { method ->
                        "OAuth refresh failed with auth method '$method' for $resourceUrl; retrying."
                    },
                )
        ) {
            is TokenRequestResult.Success -> {
                logger.debug("OAuth refresh token response received for $resourceUrl")
                applyRecoveredRegistration(
                    previous = registration,
                    current = result.value.registration,
                    context = "refresh",
                )
                result.value.token
            }
            is TokenRequestResult.Failure -> {
                logger.debug("OAuth refresh token request failed: ${result.value.status}")
                null
            }
        }
    }

    private suspend fun requestTokenWithFallback(
        registration: OAuthClientRegistration,
        supported: List<String>?,
        tokenEndpoint: String,
        buildParameters: (OAuthClientRegistration) -> Parameters,
        retryLog: (String) -> String,
    ): TokenRequestResult {
        val methods = tokenEndpointAuthMethodCandidates(registration, supported)
        var result: TokenRequestResult =
            TokenRequestResult.Failure(
                TokenRequestFailure(
                    status = "unknown",
                    body = "no compatible token endpoint auth method",
                ),
            )
        var shouldStop = false
        for ((index, method) in methods.withIndex()) {
            val effectiveRegistration = registration.copy(tokenEndpointAuthMethod = method)
            val response =
                requestToken(
                    tokenEndpoint = tokenEndpoint,
                    formParameters = buildParameters(effectiveRegistration),
                    registration = effectiveRegistration,
                )
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val token =
                    toToken(
                        json.decodeFromString(OAuthTokenResponse.serializer(), body),
                        clockMillis(),
                    )
                result = TokenRequestResult.Success(TokenRequestSuccess(effectiveRegistration, token))
                shouldStop = true
            }
            val canRetry = index < methods.lastIndex && shouldRetryTokenAuthMethod(response.status.value, body)
            if (response.status.isSuccess()) {
                // no-op: success path already captured above
            } else if (canRetry) {
                logger.warn(retryLog(method))
            } else {
                result =
                    TokenRequestResult.Failure(
                        TokenRequestFailure(
                            status = response.status.toString(),
                            body = body,
                        ),
                    )
                shouldStop = true
            }
            if (shouldStop) {
                break
            }
        }
        return result
    }

    private fun applyRecoveredRegistration(
        previous: OAuthClientRegistration,
        current: OAuthClientRegistration,
        context: String,
    ) {
        if (current.tokenEndpointAuthMethod != previous.tokenEndpointAuthMethod) {
            state.registration = current
            logger.info(
                "OAuth $context recovered with auth method '${current.tokenEndpointAuthMethod}' for $resourceUrl",
            )
        }
    }

    private fun ParametersBuilder.appendClientSecret(registration: OAuthClientRegistration) {
        if (
            registration.tokenEndpointAuthMethod == "client_secret_post" &&
            !registration.clientSecret.isNullOrBlank()
        ) {
            append("client_secret", registration.clientSecret)
        }
    }

    private suspend fun requestToken(
        tokenEndpoint: String,
        formParameters: Parameters,
        registration: OAuthClientRegistration,
    ) = httpClientProvider().submitForm(
        url = tokenEndpoint,
        formParameters = formParameters,
        encodeInQuery = false,
    ) {
        headers { append(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString()) }
        applyClientAuthHeaders(registration)
    }

    private fun tokenEndpointAuthMethodCandidates(
        registration: OAuthClientRegistration,
        supported: List<String>?,
    ): List<String> {
        val ordered = linkedSetOf<String>()
        normalizeAuthMethod(registration.tokenEndpointAuthMethod)?.let { ordered.add(it) }
        supported
            .orEmpty()
            .mapNotNull(::normalizeAuthMethod)
            .forEach { ordered.add(it) }
        if (!registration.clientSecret.isNullOrBlank()) {
            ordered.add("client_secret_basic")
            ordered.add("client_secret_post")
        }
        ordered.add("none")
        return ordered.toList()
    }

    private fun normalizeAuthMethod(value: String?): String? {
        val normalized = value?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        return if (normalized in TOKEN_ENDPOINT_AUTH_METHODS) normalized else null
    }

    private fun shouldRetryTokenAuthMethod(
        statusCode: Int,
        body: String,
    ): Boolean {
        if (statusCode != HTTP_BAD_REQUEST && statusCode != HTTP_UNAUTHORIZED) return false
        val lowerBody = body.lowercase()
        return lowerBody.contains("invalid_client") ||
            lowerBody.contains("client authentication failed") ||
            lowerBody.contains("unsupported authentication method")
    }

    private companion object {
        val TOKEN_ENDPOINT_AUTH_METHODS = setOf("client_secret_basic", "client_secret_post", "none")
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_UNAUTHORIZED = 401
    }
}
