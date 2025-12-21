package io.qent.broxy.core.mcp.auth

interface AuthorizationCodeReceiver : AutoCloseable {
    val redirectUri: String

    suspend fun awaitCode(
        authorizationUrl: String,
        expectedState: String,
        timeoutMillis: Long,
    ): Result<String>
}
