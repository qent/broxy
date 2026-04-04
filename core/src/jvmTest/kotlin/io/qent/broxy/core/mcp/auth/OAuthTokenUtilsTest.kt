package io.qent.broxy.core.mcp.auth

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OAuthTokenUtilsTest {
    @Test
    fun resolve_token_endpoint_auth_method_prefers_config_or_secret() {
        assertEquals("client_secret_basic", resolveTokenEndpointAuthMethod(null, "secret", null))
        assertEquals("none", resolveTokenEndpointAuthMethod(null, null, null))
        assertEquals("client_secret_post", resolveTokenEndpointAuthMethod("client_secret_post", "secret", null))
    }

    @Test
    fun resolve_token_endpoint_auth_method_rejects_unsupported() {
        val error =
            assertFailsWith<IllegalStateException> {
                resolveTokenEndpointAuthMethod("client_secret_post", "secret", listOf("none"))
            }
        assertTrue(error.message?.contains("not supported") == true)
    }

    @Test
    fun resolve_registered_token_endpoint_auth_method_prefers_registered_and_infers_secret_mode() {
        assertEquals(
            "client_secret_basic",
            resolveRegisteredTokenEndpointAuthMethod(
                configured = null,
                registered = null,
                clientSecret = "secret",
                supported = listOf("client_secret_post", "client_secret_basic", "none"),
            ),
        )
        assertEquals(
            "client_secret_post",
            resolveRegisteredTokenEndpointAuthMethod(
                configured = null,
                registered = null,
                clientSecret = "secret",
                supported = listOf("client_secret_post"),
            ),
        )
        assertEquals(
            "client_secret_basic",
            resolveRegisteredTokenEndpointAuthMethod(
                configured = null,
                registered = null,
                clientSecret = "secret",
                supported = null,
            ),
        )
        assertEquals(
            "none",
            resolveRegisteredTokenEndpointAuthMethod(
                configured = null,
                registered = "none",
                clientSecret = null,
                supported = listOf("none"),
            ),
        )
        assertEquals(
            "client_secret_basic",
            resolveRegisteredTokenEndpointAuthMethod(
                configured = null,
                registered = "client_secret_basic",
                clientSecret = "secret",
                supported = listOf("client_secret_post"),
            ),
        )
    }

    @Test
    fun apply_client_auth_headers_adds_basic_auth() {
        val registration =
            OAuthClientRegistration(
                clientId = "client",
                clientSecret = "secret",
                tokenEndpointAuthMethod = "client_secret_basic",
            )
        val builder = HttpRequestBuilder()

        builder.applyClientAuthHeaders(registration)

        val header = builder.headers[HttpHeaders.Authorization]
        assertNotNull(header)
        assertTrue(header.startsWith("Basic "))
    }

    @Test
    fun to_token_applies_expiry_skew() {
        val response =
            OAuthTokenResponse(
                accessToken = "token",
                tokenType = null,
                refreshToken = "refresh",
                expiresIn = 10,
                scope = "files:read",
            )
        val token = toToken(response, nowMillis = 1_000L)
        assertEquals("Bearer", token.tokenType)
        assertEquals(1_000L, token.expiresAtEpochMillis)
    }

    @Test
    fun scope_and_expiry_helpers_select_expected_values() {
        val token =
            OAuthToken(
                accessToken = "t1",
                tokenType = "Bearer",
                refreshToken = null,
                scope = "a b",
                expiresAtEpochMillis = 10,
            )
        assertTrue(isExpired(token, nowMillis = 10))
        assertTrue(tokenSatisfiesScope(token, "a"))
        assertTrue(tokenSatisfiesScope(token, null))

        assertEquals("challenge", selectScope("challenge", listOf("s1"), listOf("s2")))
        assertEquals("s1 s2", selectScope(null, listOf("s1", "s2"), listOf("fallback")))
        assertEquals("fallback", selectScope(null, null, listOf("fallback")))
    }

    @Test
    fun resolve_authorization_timeout_defaults_when_invalid() {
        assertEquals(DEFAULT_AUTH_CODE_TIMEOUT_MILLIS, resolveAuthorizationTimeoutMillis(null))
        assertEquals(DEFAULT_AUTH_CODE_TIMEOUT_MILLIS, resolveAuthorizationTimeoutMillis(0))
        assertEquals(30_000L, resolveAuthorizationTimeoutMillis(30_000L))
    }

    @Test
    fun resolve_access_token_uses_existing_or_refreshes() {
        val logger = RecordingLogger()
        val state = OAuthState()
        runBlocking {
            state.withLock {
                token =
                    OAuthToken(
                        accessToken = "access",
                        tokenType = "Bearer",
                        refreshToken = "refresh",
                        scope = "scope",
                        expiresAtEpochMillis = 5_000L,
                    )
            }
        }

        val access =
            runBlocking {
                resolveAccessTokenLocked(
                    state = state,
                    requiredScope = "scope",
                    nowMillis = 1_000L,
                    logger = logger,
                    resourceUrl = "https://mcp.example.com",
                    refreshToken = { _, _ -> null },
                )
            }

        assertEquals("access", access)

        val refreshed =
            runBlocking {
                resolveAccessTokenLocked(
                    state = state,
                    requiredScope = "scope",
                    nowMillis = 10_000L,
                    logger = logger,
                    resourceUrl = "https://mcp.example.com",
                    refreshToken = { _, _ ->
                        OAuthToken(
                            accessToken = "new",
                            tokenType = "Bearer",
                            refreshToken = "refresh",
                            scope = "scope",
                            expiresAtEpochMillis = 20_000L,
                        )
                    },
                )
            }

        assertEquals("new", refreshed)
    }

    @Test
    fun resolve_access_token_returns_null_when_refresh_fails() {
        val logger = RecordingLogger()
        val state = OAuthState()
        runBlocking {
            state.withLock {
                token =
                    OAuthToken(
                        accessToken = "access",
                        tokenType = "Bearer",
                        refreshToken = "refresh",
                        scope = "scope",
                        expiresAtEpochMillis = 1L,
                    )
            }
        }

        val access =
            runBlocking {
                resolveAccessTokenLocked(
                    state = state,
                    requiredScope = "scope",
                    nowMillis = 10_000L,
                    logger = logger,
                    resourceUrl = "https://mcp.example.com",
                    refreshToken = { _, _ -> null },
                )
            }

        assertNull(access)
    }

    private class RecordingLogger : Logger {
        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(
            message: String,
            throwable: Throwable?,
        ) = Unit

        override fun error(
            message: String,
            throwable: Throwable?,
        ) = Unit
    }
}
