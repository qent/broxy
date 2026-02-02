package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.core.mcp.auth.AuthorizationPresenter
import io.qent.broxy.core.mcp.auth.AuthorizationPresenterRegistry

actual fun registerAuthorizationPresenter(presenter: AuthorizationPresenter?) {
    AuthorizationPresenterRegistry.register(presenter)
}
