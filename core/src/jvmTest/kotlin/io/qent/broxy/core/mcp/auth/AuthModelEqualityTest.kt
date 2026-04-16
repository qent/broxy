package io.qent.broxy.core.mcp.auth

import kotlin.test.Test
import kotlin.test.assertTrue

class AuthModelEqualityTest {
    @Test
    fun authorization_server_metadata_compares_fields() {
        val base =
            AuthorizationServerMetadata(
                issuer = "https://auth.example.com",
                authorizationEndpoint = "https://auth.example.com/auth",
                tokenEndpoint = "https://auth.example.com/token",
                registrationEndpoint = "https://auth.example.com/register",
                codeChallengeMethodsSupported = listOf("S256"),
                clientIdMetadataDocumentSupported = true,
                scopesSupported = listOf("files:read"),
                tokenEndpointAuthMethodsSupported = listOf("client_secret_basic"),
            )

        val variants =
            listOf(
                base.copy(issuer = "https://auth2.example.com"),
                base.copy(authorizationEndpoint = "https://auth.example.com/auth2"),
                base.copy(tokenEndpoint = "https://auth.example.com/token2"),
                base.copy(registrationEndpoint = "https://auth.example.com/register2"),
                base.copy(codeChallengeMethodsSupported = listOf("plain")),
                base.copy(clientIdMetadataDocumentSupported = false),
                base.copy(scopesSupported = listOf("files:write")),
                base.copy(tokenEndpointAuthMethodsSupported = listOf("none")),
            )

        variants.forEach { variant ->
            assertTrue(base != variant)
        }
    }

    @Test
    fun protected_resource_metadata_compares_fields() {
        val base =
            ProtectedResourceMetadata(
                resource = "https://mcp.example.com",
                resourceName = "MCP",
                authorizationServers = listOf("https://auth.example.com"),
                scopesSupported = listOf("files:read"),
            )

        val variants =
            listOf(
                base.copy(resource = "https://mcp2.example.com"),
                base.copy(resourceName = "Other"),
                base.copy(authorizationServers = listOf("https://auth2.example.com")),
                base.copy(scopesSupported = listOf("files:write")),
            )

        variants.forEach { variant ->
            assertTrue(base != variant)
        }
    }

    @Test
    fun token_response_compares_fields() {
        val base =
            OAuthTokenResponse(
                accessToken = "token",
                tokenType = "Bearer",
                refreshToken = "refresh",
                expiresIn = 60,
                scope = "files:read",
            )

        val variants =
            listOf(
                base.copy(accessToken = "other"),
                base.copy(tokenType = "MAC"),
                base.copy(refreshToken = "refresh2"),
                base.copy(expiresIn = 120),
                base.copy(scope = "files:write"),
            )

        variants.forEach { variant ->
            assertTrue(base != variant)
        }
    }
}
