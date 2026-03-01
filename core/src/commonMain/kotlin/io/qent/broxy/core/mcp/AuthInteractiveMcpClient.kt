package io.qent.broxy.core.mcp

/**
 * Marks clients that may require user interaction (for example, OAuth authorization).
 * Connection attempts should allow a longer timeout to complete the flow.
 */
interface AuthInteractiveMcpClient {
    val authorizationTimeoutMillis: Long
}
