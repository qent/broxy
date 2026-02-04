package io.qent.broxy.core.mcp.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
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
        val resourceUri = canonicalResourceUri(resourceUrl, state.resourceMetadata)
        val params =
            Parameters.build {
                append("grant_type", "authorization_code")
                append("code", code)
                append("redirect_uri", redirectUri)
                append("client_id", registration.clientId)
                append("code_verifier", codeVerifier)
                append("resource", resourceUri)
                if (
                    registration.tokenEndpointAuthMethod == "client_secret_post" &&
                    !registration.clientSecret.isNullOrBlank()
                ) {
                    append("client_secret", registration.clientSecret)
                }
            }
        val response =
            httpClientProvider().submitForm(
                url = authMeta.tokenEndpoint ?: error("Missing token endpoint"),
                formParameters = params,
                encodeInQuery = false,
            ) {
                headers { append(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString()) }
                applyClientAuthHeaders(registration)
            }
        if (!response.status.isSuccess()) {
            error("Token request failed: ${response.status} ${response.bodyAsText()}")
        }
        val body = response.bodyAsText()
        logger.debug("OAuth token endpoint response received for $resourceUrl")
        return toToken(
            json.decodeFromString(OAuthTokenResponse.serializer(), body),
            clockMillis(),
        )
    }

    /**
     * Requires the caller to hold [OAuthState.mutex].
     */
    suspend fun refreshToken(
        refreshToken: String,
        scope: String?,
    ): OAuthToken? {
        val registration = state.registration
        val tokenEndpoint = state.authorizationMetadata?.tokenEndpoint
        var refreshed: OAuthToken? = null
        if (registration != null && tokenEndpoint != null) {
            logger.debug("OAuth refreshing token for $resourceUrl")
            val resourceUri = canonicalResourceUri(resourceUrl, state.resourceMetadata)
            val params =
                Parameters.build {
                    append("grant_type", "refresh_token")
                    append("refresh_token", refreshToken)
                    append("client_id", registration.clientId)
                    append("resource", resourceUri)
                    if (!scope.isNullOrBlank()) append("scope", scope)
                    if (
                        registration.tokenEndpointAuthMethod == "client_secret_post" &&
                        !registration.clientSecret.isNullOrBlank()
                    ) {
                        append("client_secret", registration.clientSecret)
                    }
                }
            val response =
                httpClientProvider().submitForm(
                    url = tokenEndpoint,
                    formParameters = params,
                    encodeInQuery = false,
                ) {
                    headers {
                        append(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    }
                    applyClientAuthHeaders(registration)
                }
            if (response.status.isSuccess()) {
                val body = response.bodyAsText()
                logger.debug("OAuth refresh token response received for $resourceUrl")
                refreshed =
                    toToken(
                        json.decodeFromString(OAuthTokenResponse.serializer(), body),
                        clockMillis(),
                    )
            } else {
                logger.debug("OAuth refresh token request failed: ${response.status}")
            }
        }
        return refreshed
    }
}
