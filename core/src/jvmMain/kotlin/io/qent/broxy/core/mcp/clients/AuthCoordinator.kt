package io.qent.broxy.core.mcp.clients

import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import io.qent.broxy.core.mcp.auth.AuthorizationStatusListener
import io.qent.broxy.core.mcp.auth.OAuthAuthorizer
import io.qent.broxy.core.mcp.auth.OAuthChallenge
import io.qent.broxy.core.mcp.auth.OAuthChallengeRecorder
import io.qent.broxy.core.mcp.auth.OAuthState
import io.qent.broxy.core.mcp.auth.peekAccessToken
import io.qent.broxy.core.mcp.auth.resolveOAuthResourceUrl
import io.qent.broxy.core.models.AuthConfig
import io.qent.broxy.core.utils.Logger

@Suppress("LongParameterList")
internal class AuthCoordinator(
    private val headersMap: Map<String, String>,
    private val authConfig: AuthConfig?,
    private val authState: OAuthState,
    private val oauthAuthorizerFactory: (AuthConfig.OAuth, OAuthState, String, Logger) -> OAuthAuthorizer,
    private val authorizationStatusListener: AuthorizationStatusListener?,
    private val logger: Logger,
    private val url: String,
) {
    private val authChallengeRecorder = OAuthChallengeRecorder()
    private val hasExplicitAuthorizationHeader =
        headersMap.keys.any { it.equals(HttpHeaders.Authorization, ignoreCase = true) }
    private val oauthAllowed = !hasExplicitAuthorizationHeader
    private val autoOauthEnabled = oauthAllowed && authConfig == null
    private var oauthManager: OAuthAuthorizer? =
        if (oauthAllowed) {
            (authConfig as? AuthConfig.OAuth)?.let { cfg ->
                oauthAuthorizerFactory(cfg, authState, resolveOAuthResourceUrl(url), logger)
            }
        } else {
            null
        }

    fun shouldRetryAuth(): Boolean = oauthAllowed && (oauthManager != null || autoOauthEnabled)

    fun resetChallenge() {
        authChallengeRecorder.reset()
    }

    fun consumeChallenge(): OAuthChallenge? = authChallengeRecorder.consume()

    fun recordChallenge(response: HttpResponse) {
        authChallengeRecorder.record(response)
    }

    fun resolvePreauthManager(allowPreauth: Boolean): OAuthAuthorizer? {
        if (!allowPreauth) return null
        var manager = oauthManager
        if (manager == null && oauthAllowed && autoOauthEnabled) {
            manager = getOrCreateOAuthManager()
        }
        return manager
    }

    fun getOrCreateOAuthManager(): OAuthAuthorizer? {
        var manager = oauthManager
        if (manager == null && oauthAllowed && autoOauthEnabled) {
            manager = oauthAuthorizerFactory(AuthConfig.OAuth(), authState, resolveOAuthResourceUrl(url), logger)
            oauthManager = manager
        }
        return manager
    }

    fun currentAccessToken(): String? =
        if (oauthAllowed) {
            oauthManager?.currentAccessToken() ?: authState.peekAccessToken()
        } else {
            null
        }

    fun isAuthFailure(
        error: Throwable?,
        challenge: OAuthChallenge?,
    ): Boolean {
        if (challenge != null && isAuthStatus(challenge.statusCode)) return true
        return when (error) {
            is StreamableHttpError -> isAuthStatus(error.code)
            is SSEClientException -> isAuthStatus(error.response?.status?.value)
            is ResponseException -> isAuthStatus(error.response.status.value)
            else -> false
        }
    }

    suspend fun ensureAuthorized(
        manager: OAuthAuthorizer,
        challenge: OAuthChallenge? = null,
    ) {
        val listener = authorizationStatusListener
        if (listener == null) {
            manager.ensureAuthorized(challenge).getOrThrow()
            return
        }
        listener.onAuthorizationStart()
        try {
            manager.ensureAuthorized(challenge).getOrThrow()
        } finally {
            listener.onAuthorizationComplete()
        }
    }

    fun close() {
        runCatching { oauthManager?.close() }
    }
}

private const val STATUS_UNAUTHORIZED = 401
private const val STATUS_FORBIDDEN = 403

private fun isAuthStatus(code: Int?): Boolean = code == STATUS_UNAUTHORIZED || code == STATUS_FORBIDDEN
