package io.qent.broxy.core.mcp.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class OAuthTokenResponse(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("token_type")
    val tokenType: String? = null,
    @SerialName("refresh_token")
    val refreshToken: String? = null,
    @SerialName("expires_in")
    val expiresIn: Long? = null,
    val scope: String? = null,
)

@Serializable
internal data class DynamicClientRegistrationResponse(
    @SerialName("client_id")
    val clientId: String,
    @SerialName("client_secret")
    val clientSecret: String? = null,
    @SerialName("token_endpoint_auth_method")
    val tokenEndpointAuthMethod: String? = null,
)
