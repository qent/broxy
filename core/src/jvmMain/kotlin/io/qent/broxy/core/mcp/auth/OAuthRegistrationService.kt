package io.qent.broxy.core.mcp.auth

import io.ktor.client.HttpClient
import io.qent.broxy.core.models.AuthConfig
import io.qent.broxy.core.utils.Logger
import kotlinx.serialization.json.Json

internal class OAuthRegistrationService(
    private val config: AuthConfig.OAuth,
    private val state: OAuthState,
    private val resourceUrl: String,
    private val logger: Logger,
    private val json: Json,
    private val httpClientProvider: () -> HttpClient,
) {
    /**
     * Requires the caller to hold [OAuthState.mutex].
     */
    suspend fun resolveRegistration(
        authMeta: AuthorizationServerMetadata,
        redirectUri: String,
    ): OAuthClientRegistration {
        val registration =
            when {
                !config.clientId.isNullOrBlank() -> {
                    logger.debug("OAuth using configured client_id for $resourceUrl")
                    OAuthClientRegistration(
                        clientId = config.clientId,
                        clientSecret = config.clientSecret,
                        tokenEndpointAuthMethod =
                            resolveTokenEndpointAuthMethod(
                                config.tokenEndpointAuthMethod,
                                config.clientSecret,
                                authMeta.tokenEndpointAuthMethodsSupported,
                            ),
                    )
                }
                !config.clientIdMetadataUrl.isNullOrBlank() -> {
                    if (authMeta.clientIdMetadataDocumentSupported != true) {
                        error("Authorization server does not support client ID metadata documents.")
                    }
                    logger.debug("OAuth using client_id metadata URL for $resourceUrl")
                    OAuthClientRegistration(
                        clientId = config.clientIdMetadataUrl,
                        clientSecret = config.clientSecret,
                        tokenEndpointAuthMethod =
                            resolveTokenEndpointAuthMethod(
                                config.tokenEndpointAuthMethod,
                                config.clientSecret,
                                authMeta.tokenEndpointAuthMethodsSupported,
                            ),
                    )
                }
                config.allowDynamicRegistration && !authMeta.registrationEndpoint.isNullOrBlank() -> {
                    resolveDynamicRegistrationLocked(
                        authMeta,
                        redirectUri,
                        state,
                        config,
                        httpClientProvider(),
                        json,
                        logger,
                        resourceUrl,
                    )
                }
                else -> null
            }
                ?: error(
                    "No OAuth client registration available; configure clientId or clientIdMetadataUrl.",
                )
        state.registration = registration
        return registration
    }
}
