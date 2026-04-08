package io.qent.broxy.core.mcp.auth

object AuthorizationPresenterRegistry {
    @Volatile
    private var presenter: AuthorizationPresenter? = null

    fun register(presenter: AuthorizationPresenter?) {
        this.presenter = presenter
    }

    fun current(): AuthorizationPresenter? = presenter
}
