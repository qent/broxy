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
}
