package io.qent.broxy.core.mcp.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProtectedResourceMetadata(
    val resource: String? = null,
    @SerialName("resource_name")
    val resourceName: String? = null,
    @SerialName("authorization_servers")
    val authorizationServers: List<String> = emptyList(),
    @SerialName("scopes_supported")
    val scopesSupported: List<String>? = null,
)

@Serializable
data class AuthorizationServerMetadata(
    val issuer: String? = null,
    @SerialName("authorization_endpoint")
    val authorizationEndpoint: String? = null,
    @SerialName("token_endpoint")
    val tokenEndpoint: String? = null,
    @SerialName("registration_endpoint")
    val registrationEndpoint: String? = null,
    @SerialName("code_challenge_methods_supported")
    val codeChallengeMethodsSupported: List<String>? = null,
    @SerialName("client_id_metadata_document_supported")
    val clientIdMetadataDocumentSupported: Boolean? = null,
    @SerialName("scopes_supported")
    val scopesSupported: List<String>? = null,
    @SerialName("token_endpoint_auth_methods_supported")
    val tokenEndpointAuthMethodsSupported: List<String>? = null,
)
