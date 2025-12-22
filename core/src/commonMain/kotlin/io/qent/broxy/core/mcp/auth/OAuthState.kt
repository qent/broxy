package io.qent.broxy.core.mcp.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable

@Serializable
data class OAuthToken(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val refreshToken: String? = null,
    val scope: String? = null,
    val expiresAtEpochMillis: Long? = null,
)

@Serializable
data class OAuthClientRegistration(
    val clientId: String,
    val clientSecret: String? = null,
    val tokenEndpointAuthMethod: String = "none",
)

data class OAuthChallenge(
    val statusCode: Int,
    val resourceMetadataUrl: String? = null,
    val scope: String? = null,
    val error: String? = null,
    val errorDescription: String? = null,
)

class OAuthState {
    val mutex = Mutex()

    @Volatile
    var token: OAuthToken? = null

    @Volatile
    var registration: OAuthClientRegistration? = null

    @Volatile
    var registeredRedirectUri: String? = null

    @Volatile
    var resourceMetadata: ProtectedResourceMetadata? = null

    @Volatile
    var resourceMetadataUrl: String? = null

    @Volatile
    var authorizationMetadata: AuthorizationServerMetadata? = null

    @Volatile
    var authorizationServer: String? = null

    @Volatile
    var lastRequestedScope: String? = null

    @Volatile
    var authorizationTimeoutMillis: Long? = null
}
