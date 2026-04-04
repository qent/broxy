package io.qent.broxy.core.mcp.auth

import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LoopbackAuthorizationCodeReceiverTest {
    @Test
    fun await_code_returns_authorization_code() {
        val logger = CapturingLogger()
        val receiver = LoopbackAuthorizationCodeReceiver("http://127.0.0.1:0/callback", logger)
        val redirect = URI(receiver.redirectUri)

        val connection = openCallback(redirect, "code=abc&state=state123")
        connection.inputStream.use { it.readBytes() }

        val result =
            runBlocking {
                receiver.awaitCode(
                    authorizationUrl = "https://auth.example.com",
                    expectedState = "state123",
                    timeoutMillis = 1_000L,
                )
            }

        assertTrue(result.isSuccess)
        assertEquals("abc", result.getOrThrow())
    }

    @Test
    fun await_code_reports_state_mismatch() {
        val logger = CapturingLogger()
        val receiver = LoopbackAuthorizationCodeReceiver("http://127.0.0.1:0/callback", logger)
        val redirect = URI(receiver.redirectUri)

        val connection = openCallback(redirect, "code=abc&state=wrong")
        connection.inputStream.use { it.readBytes() }

        val result =
            runBlocking {
                receiver.awaitCode(
                    authorizationUrl = "https://auth.example.com",
                    expectedState = "expected",
                    timeoutMillis = 1_000L,
                )
            }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("state mismatch") == true)
    }

    @Test
    fun invalid_redirect_override_is_rejected() {
        assertFailsWith<IllegalArgumentException> {
            LoopbackAuthorizationCodeReceiver("https://example.com/callback", CapturingLogger())
        }
    }

    @Test
    fun completion_page_renders_server_context_with_remote_icon() {
        withRegisteredPresenter(
            object : AuthorizationPresenter {
                override fun onAuthorizationRequest(request: AuthorizationRequest) = Unit

                override fun onAuthorizationResult(result: AuthorizationResult) = Unit

                override fun resolveCompletionPageContext(resourceUrl: String): AuthorizationCompletionPageContext? =
                    if (resourceUrl == "https://mcp.example.com/mcp") {
                        AuthorizationCompletionPageContext(
                            serverName = "Context7",
                            iconUrl = "https://cdn.example/context7.png",
                        )
                    } else {
                        null
                    }
            },
        ) {
            val receiver =
                LoopbackAuthorizationCodeReceiver(
                    redirectUriOverride = "http://127.0.0.1:0/callback",
                    logger = CapturingLogger(),
                    resourceUrl = "https://mcp.example.com/mcp",
                )
            val redirect = URI(receiver.redirectUri)

            val connection = openCallback(redirect, "code=abc&state=state123")
            val body = connection.inputStream.bufferedReader().use { it.readText() }

            assertTrue(body.contains("<h1>Context7 Authorized</h1>"))
            assertTrue(body.contains("src=\"https://cdn.example/context7.png\""))
            assertTrue(body.contains("background: #ffffff;"))
            assertTrue(body.contains("padding: 6px;"))

            val result =
                runBlocking {
                    receiver.awaitCode(
                        authorizationUrl = "https://auth.example.com",
                        expectedState = "state123",
                        timeoutMillis = 1_000L,
                    )
                }
            assertTrue(result.isSuccess)
        }
    }

    @Test
    fun completion_page_keeps_generic_fallback_without_context() {
        AuthorizationPresenterRegistry.register(null)
        val receiver =
            LoopbackAuthorizationCodeReceiver(
                redirectUriOverride = "http://127.0.0.1:0/callback",
                logger = CapturingLogger(),
                resourceUrl = "https://mcp.example.com/mcp",
            )
        val redirect = URI(receiver.redirectUri)

        val connection = openCallback(redirect, "code=abc&state=state123")
        val body = connection.inputStream.bufferedReader().use { it.readText() }

        assertTrue(body.contains("<h1>Authorization complete</h1>"))
        assertTrue(body.contains("<path d=\"M5 13l4 4L19 7\"></path>"))

        val result =
            runBlocking {
                receiver.awaitCode(
                    authorizationUrl = "https://auth.example.com",
                    expectedState = "state123",
                    timeoutMillis = 1_000L,
                )
            }
        assertTrue(result.isSuccess)
    }

    private fun openCallback(
        redirect: URI,
        query: String,
    ): HttpURLConnection {
        val callback =
            URI(
                redirect.scheme,
                null,
                redirect.host,
                redirect.port,
                redirect.path,
                query,
                null,
            )
        return callback.toURL().openConnection() as HttpURLConnection
    }

    private fun withRegisteredPresenter(
        presenter: AuthorizationPresenter,
        block: () -> Unit,
    ) {
        AuthorizationPresenterRegistry.register(presenter)
        try {
            block()
        } finally {
            AuthorizationPresenterRegistry.register(null)
        }
    }
}
