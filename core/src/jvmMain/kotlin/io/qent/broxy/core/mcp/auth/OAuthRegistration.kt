package io.qent.broxy.core.mcp.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.qent.broxy.core.models.AuthConfig
import io.qent.broxy.core.utils.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray

@Suppress("LongParameterList")
internal suspend fun registerDynamicClient(
    client: HttpClient,
    registrationEndpoint: String,
    redirectUri: String,
    config: AuthConfig.OAuth,
    json: Json,
    logger: Logger,
    resourceUrl: String,
): OAuthClientRegistration {
    logger.debug("OAuth dynamic client registration request to $registrationEndpoint")
    val authMethod = resolveTokenEndpointAuthMethod(config.tokenEndpointAuthMethod, null, null)
    val payload =
        buildJsonObject {
            put("client_name", JsonPrimitive(config.clientName ?: "Broxy"))
            putJsonArray("redirect_uris") { add(JsonPrimitive(redirectUri)) }
            putJsonArray("grant_types") {
                add(JsonPrimitive("authorization_code"))
                add(JsonPrimitive("refresh_token"))
            }
            putJsonArray("response_types") { add(JsonPrimitive("code")) }
            put("token_endpoint_auth_method", JsonPrimitive(authMethod))
        }
    val response =
        client.post(registrationEndpoint) {
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    JsonObject
                        .serializer(),
                    payload,
                ),
            )
        }
    if (!response.status.isSuccess()) {
        error("Dynamic client registration failed: ${response.status} ${response.bodyAsText()}")
    }
    val responseBody = response.bodyAsText()
    val registration =
        json.decodeFromString(
            DynamicClientRegistrationResponse.serializer(),
            responseBody,
        )
    logger.debug("OAuth dynamic client registration succeeded for $resourceUrl")
    return OAuthClientRegistration(
        clientId = registration.clientId,
        clientSecret = registration.clientSecret,
        tokenEndpointAuthMethod = registration.tokenEndpointAuthMethod?.lowercase() ?: authMethod,
    )
}

@Suppress("LongParameterList")
/**
 * Requires the caller to hold [OAuthState.mutex].
 */
internal suspend fun resolveDynamicRegistrationLocked(
    authMeta: AuthorizationServerMetadata,
    redirectUri: String,
    state: OAuthState,
    config: AuthConfig.OAuth,
    client: HttpClient,
    json: Json,
    logger: Logger,
    resourceUrl: String,
): OAuthClientRegistration {
    val existing = state.registration
    val registeredRedirect = state.registeredRedirectUri
    val shouldReuse =
        existing != null && (registeredRedirect.isNullOrBlank() || registeredRedirect == redirectUri)
    if (shouldReuse) {
        logger.debug("OAuth reusing cached dynamic registration for $resourceUrl")
        return existing
    }
    logger.debug("OAuth starting dynamic client registration for $resourceUrl")
    val registrationEndpoint =
        authMeta.registrationEndpoint ?: error("Authorization server missing registration endpoint.")
    val registration =
        registerDynamicClient(
            client,
            registrationEndpoint,
            redirectUri,
            config,
            json,
            logger,
            resourceUrl,
        )
    state.registeredRedirectUri = redirectUri
    return registration
}
