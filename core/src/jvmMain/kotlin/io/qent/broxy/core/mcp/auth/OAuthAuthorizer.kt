package io.qent.broxy.core.mcp.auth

interface OAuthAuthorizer : AutoCloseable {
    fun currentAccessToken(): String?

    suspend fun ensureAuthorized(challenge: OAuthChallenge? = null): Result<String?>

    override fun close()
}
