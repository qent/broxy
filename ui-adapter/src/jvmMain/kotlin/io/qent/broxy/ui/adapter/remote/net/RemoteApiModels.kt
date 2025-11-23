package io.qent.broxy.ui.adapter.remote.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginResponse(
    @SerialName("authorization_url")
    val authorizationUrl: String,
    val state: String
)

@Serializable
data class CallbackRequest(
    val code: String,
    val state: String,
    val audience: String = "mcp"
)

@Serializable
data class TokenResponse(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("token_type")
    val tokenType: String,
    @SerialName("expires_at")
    val expiresAt: String,
    val scope: String
)

@Serializable
data class RegisterRequest(
    @SerialName("server_identifier")
    val serverIdentifier: String,
    val name: String,
    val capabilities: Capabilities
) {
    @Serializable
    data class Capabilities(
        val prompts: Boolean,
        val tools: Boolean,
        val resources: Boolean
    )
}

@Serializable
data class RegisterResponse(
    @SerialName("server_identifier")
    val serverIdentifier: String,
    val status: String,
    @SerialName("jwt_token")
    val jwtToken: String
)
