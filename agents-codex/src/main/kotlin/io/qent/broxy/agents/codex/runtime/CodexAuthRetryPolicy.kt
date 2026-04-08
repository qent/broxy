package io.qent.broxy.agents.codex.runtime

internal class CodexAuthRetryPolicy {
    fun isRefreshTokenReusedFailure(failure: Throwable): Boolean = isRefreshTokenReusedFailure(failure.message)

    fun isRefreshTokenReusedFailure(message: String?): Boolean =
        message?.contains(CODEX_REFRESH_TOKEN_REUSED_MARKER, ignoreCase = true) == true

    fun buildRefreshTokenFailureMessage(authFileChanged: Boolean): String =
        if (authFileChanged) {
            "Codex user session refresh failed because the stored refresh token was already used " +
                "even after ~/.codex/auth.json changed. Another Codex process may have rotated the " +
                "session again. Run `codex login` in your terminal and retry."
        } else {
            "Codex user session refresh failed because the stored refresh token was already used " +
                "and Broxy did not observe an updated ~/.codex/auth.json. The session may be stale " +
                "or another Codex process may have rotated the refresh token already. Run `codex login` " +
                "in your terminal and retry."
        }

    fun classifyFailureKind(failure: Throwable): String =
        when (failure) {
            is CodexPreflightException -> "preflight"
            is CodexRefreshTokenReusedException -> "refresh_token_reused"
            else ->
                if (isRefreshTokenReusedFailure(failure.message)) {
                    "refresh_token_reused"
                } else {
                    CODEX_DEFAULT_FAILURE_KIND
                }
        }
}
