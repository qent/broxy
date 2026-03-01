package io.qent.broxy.core.mcp.auth

interface AuthorizationStatusListener {
    fun onAuthorizationStart()

    fun onAuthorizationComplete()
}
