package io.qent.broxy.core.mcp.auth

data class AuthorizationRequest(
    val resourceUrl: String,
    val authorizationUrl: String,
    val redirectUri: String,
)

sealed interface AuthorizationResult {
    val resourceUrl: String

    data class Success(override val resourceUrl: String) : AuthorizationResult

    data class Failure(
        override val resourceUrl: String,
        val message: String?,
    ) : AuthorizationResult

    data class Cancelled(
        override val resourceUrl: String,
        val message: String?,
    ) : AuthorizationResult
}

interface AuthorizationPresenter {
    fun onAuthorizationRequest(request: AuthorizationRequest)

    fun onAuthorizationResult(result: AuthorizationResult)
}
