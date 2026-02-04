package io.qent.broxy.core.mcp.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AuthorizationPresenterRegistryTest {
    @Test
    fun register_and_current_return_latest_presenter() {
        AuthorizationPresenterRegistry.register(null)
        assertNull(AuthorizationPresenterRegistry.current())

        val presenter =
            object : AuthorizationPresenter {
                override fun onAuthorizationRequest(request: AuthorizationRequest) = Unit

                override fun onAuthorizationResult(result: AuthorizationResult) = Unit
            }
        AuthorizationPresenterRegistry.register(presenter)
        assertEquals(presenter, AuthorizationPresenterRegistry.current())

        AuthorizationPresenterRegistry.register(null)
    }
}
