package io.qent.broxy.core.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class AuthConfig {
    @Serializable
    @SerialName("oauth")
    data class OAuth(
        val clientId: String? = null,
        val clientSecret: String? = null,
        val clientIdMetadataUrl: String? = null,
        val redirectUri: String? = null,
        val clientName: String? = null,
        val tokenEndpointAuthMethod: String? = null,
        val authorizationServer: String? = null,
        val scopes: List<String>? = null,
        val allowDynamicRegistration: Boolean = true,
    ) : AuthConfig()
}
