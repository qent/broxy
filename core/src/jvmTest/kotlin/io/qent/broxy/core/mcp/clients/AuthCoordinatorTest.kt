package io.qent.broxy.core.mcp.clients

import io.qent.broxy.core.mcp.auth.AuthorizationStatusListener
import io.qent.broxy.core.mcp.auth.OAuthAuthorizer
import io.qent.broxy.core.mcp.auth.OAuthChallenge
import io.qent.broxy.core.mcp.auth.OAuthState
import io.qent.broxy.core.mcp.auth.OAuthToken
import io.qent.broxy.core.mcp.auth.withLock
import io.qent.broxy.core.models.AuthConfig
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthCoordinatorTest {
    @Test
    fun explicit_authorization_header_disables_oauth_retry() {
        val state = OAuthState()
        runBlocking {
            state.withLock {
                token =
                    OAuthToken(
                        accessToken = "token",
                        tokenType = "Bearer",
                        refreshToken = null,
                        scope = null,
                        expiresAtEpochMillis = null,
                    )
            }
        }
        val coordinator =
            AuthCoordinator(
                headersMap = mapOf("Authorization" to "Bearer abc"),
                authConfig = null,
                authState = state,
                oauthAuthorizerFactory = { _, _, _, _ -> FakeAuthorizer() },
                authorizationStatusListener = null,
                logger = NoopLogger,
                url = "https://mcp.example.com",
            )

        assertFalse(coordinator.shouldRetryAuth())
        assertNull(coordinator.currentAccessToken())
    }

    @Test
    fun auto_oauth_creates_manager_and_uses_state_token() {
        val state = OAuthState()
        runBlocking {
            state.withLock {
                token =
                    OAuthToken(
                        accessToken = "token",
                        tokenType = "Bearer",
                        refreshToken = null,
                        scope = null,
                        expiresAtEpochMillis = null,
                    )
            }
        }
        val coordinator =
            AuthCoordinator(
                headersMap = emptyMap(),
                authConfig = null,
                authState = state,
                oauthAuthorizerFactory = { _, _, _, _ -> FakeAuthorizer() },
                authorizationStatusListener = null,
                logger = NoopLogger,
                url = "https://mcp.example.com",
            )

        val manager = coordinator.resolvePreauthManager(allowPreauth = true)
        assertTrue(manager is FakeAuthorizer)
        assertEquals("token", coordinator.currentAccessToken())
    }

    @Test
    fun ensure_authorized_notifies_listener() {
        val state = OAuthState()
        val listener = RecordingListener()
        val coordinator =
            AuthCoordinator(
                headersMap = emptyMap(),
                authConfig = AuthConfig.OAuth(clientId = "client"),
                authState = state,
                oauthAuthorizerFactory = { _, _, _, _ -> FakeAuthorizer() },
                authorizationStatusListener = listener,
                logger = NoopLogger,
                url = "https://mcp.example.com",
            )

        runBlocking {
            coordinator.ensureAuthorized(FakeAuthorizer(), OAuthChallenge(statusCode = 401))
        }

        assertEquals(1, listener.started)
        assertEquals(1, listener.completed)
    }

    @Test
    fun is_auth_failure_checks_challenge_status() {
        val coordinator =
            AuthCoordinator(
                headersMap = emptyMap(),
                authConfig = null,
                authState = OAuthState(),
                oauthAuthorizerFactory = { _, _, _, _ -> FakeAuthorizer() },
                authorizationStatusListener = null,
                logger = NoopLogger,
                url = "https://mcp.example.com",
            )

        assertTrue(coordinator.isAuthFailure(null, OAuthChallenge(statusCode = 401)))
        assertFalse(coordinator.isAuthFailure(null, OAuthChallenge(statusCode = 400)))
    }

    private class FakeAuthorizer : OAuthAuthorizer {
        override fun currentAccessToken(): String? = null

        override suspend fun ensureAuthorized(challenge: OAuthChallenge?): Result<String?> = Result.success(null)

        override fun close() = Unit
    }

    private class RecordingListener : AuthorizationStatusListener {
        var started = 0
        var completed = 0

        override fun onAuthorizationStart() {
            started += 1
        }

        override fun onAuthorizationComplete() {
            completed += 1
        }
    }

    private object NoopLogger : Logger {
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
