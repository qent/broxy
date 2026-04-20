package io.qent.broxy.core.mcp.auth

import kotlin.test.Test
import kotlin.test.assertEquals

class AuthorizationResultTest {
    @Test
    fun authorization_result_variants_preserve_resource_url() {
        val request =
            AuthorizationRequest(
                resourceUrl = "https://mcp.example.com",
                authorizationUrl = "auth",
                redirectUri = "http://localhost",
            )
        val success = AuthorizationResult.Success(request.resourceUrl)
        val failure = AuthorizationResult.Failure(request.resourceUrl, "failed")
        val cancelled = AuthorizationResult.Cancelled(request.resourceUrl, "cancelled")

        assertEquals("https://mcp.example.com", success.resourceUrl)
        assertEquals("https://mcp.example.com", failure.resourceUrl)
        assertEquals("https://mcp.example.com", cancelled.resourceUrl)
    }
}
