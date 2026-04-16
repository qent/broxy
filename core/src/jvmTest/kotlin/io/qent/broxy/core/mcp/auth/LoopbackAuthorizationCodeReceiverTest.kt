package io.qent.broxy.core.mcp.auth

import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LoopbackAuthorizationCodeReceiverTest {
    @Test
    fun await_code_returns_authorization_code_over_https_loopback() {
        val logger = CapturingLogger()
        val receiver = LoopbackAuthorizationCodeReceiver("https://127.0.0.1:0/callback", logger)
        val redirect = URI(receiver.redirectUri)
        assertEquals("https", redirect.scheme)

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
    fun default_redirect_uses_localhost_and_default_callback_path() {
        val logger = CapturingLogger()
        val receiver = LoopbackAuthorizationCodeReceiver(null, logger)
        try {
            val redirect = URI(receiver.redirectUri)
            assertEquals("http", redirect.scheme)
            assertEquals("localhost", redirect.host)
            assertEquals("/oauth/callback", redirect.path)
            assertTrue(redirect.port > 0)
        } finally {
            receiver.close()
        }
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
    fun completion_page_renders_failure_state_for_oauth_error() {
        withRegisteredPresenter(
            object : AuthorizationPresenter {
                override fun onAuthorizationRequest(request: AuthorizationRequest) = Unit

                override fun onAuthorizationResult(result: AuthorizationResult) = Unit

                override fun resolveCompletionPageContext(resourceUrl: String): AuthorizationCompletionPageContext? =
                    AuthorizationCompletionPageContext(
                        serverName = "Slack",
                        iconUrl = "https://cdn.example/slack.png",
                    )
            },
        ) {
            val receiver =
                LoopbackAuthorizationCodeReceiver(
                    redirectUriOverride = "http://127.0.0.1:0/callback",
                    logger = CapturingLogger(),
                    resourceUrl = "https://mcp.slack.com/mcp",
                )
            val redirect = URI(receiver.redirectUri)

            val callbackQuery = "error=access_denied&error_description=cancelled_by_user&state=state123"
            val connection = openCallback(redirect, callbackQuery)
            val body = connection.inputStream.bufferedReader().use { it.readText() }

            assertTrue(body.contains("<h1>Slack Authorization failed</h1>"))
            assertTrue(body.contains("OAuth client authorization failed (access_denied: cancelled_by_user)."))
            assertTrue(body.contains("<span class=\"meta-value error\">Failed</span>"))
            assertTrue(body.contains("<path d=\"M18 6L6 18\"></path>"))

            val result =
                runBlocking {
                    receiver.awaitCode(
                        authorizationUrl = "https://auth.example.com",
                        expectedState = "state123",
                        timeoutMillis = 1_000L,
                    )
                }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("cancelled by user", ignoreCase = true) == true)
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
        val connection = callback.toURL().openConnection() as HttpURLConnection
        if (connection is HttpsURLConnection) {
            connection.sslSocketFactory = TRUST_ALL_CONTEXT.socketFactory
            connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        }
        return connection
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

    private companion object {
        private val TRUST_ALL_CONTEXT =
            SSLContext
                .getInstance("TLS")
                .apply {
                    init(null, arrayOf(TrustAllX509Manager), SecureRandom())
                }
    }
}

private object TrustAllX509Manager : X509TrustManager {
    override fun checkClientTrusted(
        chain: Array<X509Certificate>,
        authType: String,
    ) = Unit

    override fun checkServerTrusted(
        chain: Array<X509Certificate>,
        authType: String,
    ) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
