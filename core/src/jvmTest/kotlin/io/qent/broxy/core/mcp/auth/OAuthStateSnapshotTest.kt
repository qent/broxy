package io.qent.broxy.core.mcp.auth

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OAuthStateSnapshotTest {
    @Test
    fun snapshot_roundtrip_restores_state() {
        val state = OAuthState()
        runBlocking {
            state.withLock {
                token =
                    OAuthToken(
                        accessToken = "token",
                        tokenType = "Bearer",
                        refreshToken = null,
                        scope = "scope",
                        expiresAtEpochMillis = null,
                    )
                registration = OAuthClientRegistration(clientId = "client")
                registeredRedirectUri = "http://localhost/callback"
                authorizationServer = "https://auth.example.com"
            }
        }

        val snapshot = runBlocking { state.toSnapshotLocked("https://mcp.example.com") }
        val restored = OAuthState()
        runBlocking { restored.restoreFromLocked(snapshot) }

        assertEquals("token", restored.token?.accessToken)
        assertEquals("client", restored.registration?.clientId)
        assertEquals("http://localhost/callback", restored.registeredRedirectUri)
        assertEquals("https://auth.example.com", restored.authorizationServer)
        assertNull(restored.resourceMetadata)
    }

    @Test
    fun snapshot_compares_fields() {
        val base =
            OAuthStateSnapshot(
                resourceUrl = "https://mcp.example.com",
                token = OAuthToken(accessToken = "token"),
                registration = OAuthClientRegistration(clientId = "client"),
                registeredRedirectUri = "http://localhost/callback",
                resourceMetadata = ProtectedResourceMetadata(resource = "https://mcp.example.com"),
                resourceMetadataUrl = "https://mcp.example.com/metadata",
                authorizationMetadata = AuthorizationServerMetadata(issuer = "https://auth.example.com"),
                authorizationServer = "https://auth.example.com",
                lastRequestedScope = "files:read",
            )

        val variants =
            listOf(
                base.copy(resourceUrl = "https://mcp2.example.com"),
                base.copy(token = OAuthToken(accessToken = "other")),
                base.copy(registration = OAuthClientRegistration(clientId = "other")),
                base.copy(registeredRedirectUri = "http://localhost/other"),
                base.copy(resourceMetadata = ProtectedResourceMetadata(resource = "https://other")),
                base.copy(resourceMetadataUrl = "https://mcp.example.com/other"),
                base.copy(authorizationMetadata = AuthorizationServerMetadata(issuer = "https://auth2.example.com")),
                base.copy(authorizationServer = "https://auth2.example.com"),
                base.copy(lastRequestedScope = "files:write"),
            )

        variants.forEach { variant ->
            assertTrue(base != variant)
        }
    }
}
