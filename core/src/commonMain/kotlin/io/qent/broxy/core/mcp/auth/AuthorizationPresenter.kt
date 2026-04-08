package io.qent.broxy.core.mcp.auth

data class AuthorizationRequest(
    val resourceUrl: String,
    val authorizationUrl: String,
    val redirectUri: String,
)

data class AuthorizationCompletionPageContext(
    val serverName: String,
    val iconUrl: String? = null,
)

sealed interface AuthorizationResult {
    val resourceUrl: String

    data class Success(
        override val resourceUrl: String,
    ) : AuthorizationResult

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

    fun resolveCompletionPageContext(resourceUrl: String): AuthorizationCompletionPageContext? = null
}
