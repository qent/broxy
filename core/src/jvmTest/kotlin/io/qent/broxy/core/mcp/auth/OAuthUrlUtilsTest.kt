package io.qent.broxy.core.mcp.auth

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OAuthUrlUtilsTest {
    @Test
    fun validate_pkce_support_requires_s256() {
        val supported = AuthorizationServerMetadata(codeChallengeMethodsSupported = listOf("S256"))
        validatePkceSupport(supported)

        val unsupported = AuthorizationServerMetadata(codeChallengeMethodsSupported = listOf("plain"))
        assertFailsWith<IllegalStateException> { validatePkceSupport(unsupported) }
    }

    @Test
    fun build_authorization_url_includes_required_parameters() {
        val url =
            buildAuthorizationUrl(
                authorizationEndpoint = "https://auth.example.com/authorize",
                clientId = "client",
                redirectUri = "http://localhost:8080/callback",
                scope = "files:read",
                state = "state123",
                codeChallenge = "challenge",
                resourceUri = "https://mcp.example.com",
            )

        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("client_id=client"))
        assertTrue(url.contains("redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fcallback"))
        assertTrue(url.contains("scope=files%3Aread"))
        assertTrue(url.contains("state=state123"))
        assertTrue(url.contains("code_challenge=challenge"))
        assertTrue(url.contains("resource=https%3A%2F%2Fmcp.example.com"))
    }

    @Test
    fun code_challenge_is_deterministic_for_known_verifier() {
        val verifier = "simple-verifier"
        val challenge = generateCodeChallenge(verifier)
        assertEquals("ToTk9q3-oVXhWlJHRY5SRoxaFtGNK_EUxfavGmqSPhg", challenge)
    }

    @Test
    fun generate_code_verifier_and_state_produce_non_empty_values() {
        val random = SecureRandom(byteArrayOf(1, 2, 3, 4))
        val verifier = generateCodeVerifier(random)
        val state = generateState(random)

        assertTrue(verifier.isNotBlank())
        assertTrue(state.isNotBlank())
    }

    @Test
    fun canonical_resource_uri_normalizes_scheme_host_and_path() {
        val raw = "HTTPS://EXAMPLE.COM/"
        assertEquals("https://example.com", canonicalResourceUri(raw, null))

        val withPath = "https://example.com/api/"
        assertEquals("https://example.com/api", canonicalResourceUri(withPath, null))
    }

    @Test
    fun metadata_url_builders_include_path_variants() {
        val resourceUrls = buildProtectedResourceMetadataUrls("https://mcp.example.com/api")
        assertEquals(
            listOf(
                "https://mcp.example.com/.well-known/oauth-protected-resource/api",
                "https://mcp.example.com/.well-known/oauth-protected-resource",
            ),
            resourceUrls,
        )

        val issuerUrls = buildAuthorizationServerMetadataUrls("https://auth.example.com/issuer")
        assertEquals(
            listOf(
                "https://auth.example.com/.well-known/oauth-authorization-server/issuer",
                "https://auth.example.com/.well-known/openid-configuration/issuer",
                "https://auth.example.com/issuer/.well-known/openid-configuration",
            ),
            issuerUrls,
        )
    }
}
