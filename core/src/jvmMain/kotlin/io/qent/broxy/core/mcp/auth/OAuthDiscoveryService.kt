package io.qent.broxy.core.mcp.auth

import io.ktor.client.HttpClient
import io.qent.broxy.core.models.AuthConfig
import io.qent.broxy.core.utils.Logger
import kotlinx.serialization.json.Json

internal data class OAuthDiscoveryResult(
    val resourceMetadata: ProtectedResourceMetadata?,
    val authorizationMetadata: AuthorizationServerMetadata,
    val authorizationServer: String,
)

internal class OAuthDiscoveryService(
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
    @Suppress("LongMethod")
    suspend fun discover(challenge: OAuthChallenge?): OAuthDiscoveryResult? {
        val client = httpClientProvider()
        val force = challenge != null
        logger.debug("OAuth discovery start for $resourceUrl (force=$force)")
        val resourceMetadata =
            state.resourceMetadata
                ?: fetchProtectedResourceMetadataLocked(client, challenge, state, resourceUrl, logger, json).also {
                    if (it != null) {
                        state.resourceMetadata = it
                    }
                }
        val hasServerOverride = !config.authorizationServer.isNullOrBlank()
        val authorizationServers =
            resourceMetadata
                ?.authorizationServers
                ?.filter { it.isNotBlank() }
                .orEmpty()
                .ifEmpty {
                    listOfNotNull(
                        config.authorizationServer?.takeIf { it.isNotBlank() },
                    )
                }
        val authServerMetadataUrl = config.authServerMetadataUrl?.takeIf { it.isNotBlank() }
        if (
            shouldSkipDiscovery(
                resourceMetadata = resourceMetadata,
                hasServerOverride = hasServerOverride,
                authorizationServers = authorizationServers,
                authServerMetadataUrl = authServerMetadataUrl,
                force = force,
            )
        ) {
            return null
        }

        val cachedIssuer = state.authorizationServer
        val cachedMeta = state.authorizationMetadata
        val (issuer, authMeta) =
            if (!cachedIssuer.isNullOrBlank() && cachedMeta != null) {
                logger.debug("OAuth using cached authorization metadata for $resourceUrl")
                cachedIssuer to cachedMeta
            } else {
                if (authServerMetadataUrl != null) {
                    val metadata =
                        fetchAuthorizationServerMetadataByUrl(
                            client = client,
                            metadataUrl = authServerMetadataUrl,
                            logger = logger,
                            json = json,
                        )
                    val issuer =
                        metadata.issuer?.takeIf { it.isNotBlank() }
                            ?: config.authorizationServer?.takeIf { it.isNotBlank() }
                            ?: authServerMetadataUrl
                    issuer to metadata
                } else {
                    discoverAuthorizationServerMetadata(
                        client,
                        authorizationServers,
                        logger,
                        json,
                    )
                }
            }
        state.authorizationServer = issuer
        state.authorizationMetadata = authMeta
        validatePkceSupport(authMeta)
        return OAuthDiscoveryResult(resourceMetadata, authMeta, issuer)
    }

    private fun shouldSkipDiscovery(
        resourceMetadata: ProtectedResourceMetadata?,
        hasServerOverride: Boolean,
        authorizationServers: List<String>,
        authServerMetadataUrl: String?,
        force: Boolean,
    ): Boolean =
        when {
            resourceMetadata == null && !hasServerOverride -> {
                if (force) {
                    error("Protected resource metadata not found for $resourceUrl")
                }
                true
            }

            authorizationServers.isEmpty() && authServerMetadataUrl == null -> {
                if (force) {
                    error("OAuth authorization server list is empty for $resourceUrl")
                }
                logger.info(
                    "OAuth metadata has no authorization servers for $resourceUrl; " +
                        "skipping preauthorization until an auth challenge is received.",
                )
                true
            }

            else -> false
        }
}
