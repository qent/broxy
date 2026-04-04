package io.qent.broxy.ui.adapter.data

import com.sun.net.httpserver.HttpServer
import io.qent.broxy.core.mcp.auth.LoopbackAuthorizationCodeReceiver
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertTrue

class OAuthCancellationJvmTest {
    @Test
    fun signalOAuthCancellation_returns_failure_for_invalid_uri() {
        val result = signalOAuthCancellation("://not-valid")
        assertTrue(result.isFailure)
    }

    @Test
    fun signalOAuthCancellation_sends_access_denied_callback() {
        val queryRef = AtomicReference<String?>()
        val latch = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/callback") { exchange ->
            queryRef.set(exchange.requestURI.rawQuery)
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("ok".toByteArray()) }
            latch.countDown()
        }
        server.start()
        try {
            val port = server.address.port
            val redirectUri = "http://127.0.0.1:$port/callback"

            val result = signalOAuthCancellation(redirectUri)

            assertTrue(result.isSuccess)
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            val query = queryRef.get().orEmpty()
            assertTrue(query.contains("error=access_denied"))
            assertTrue(query.contains("error_description=cancelled_by_user"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun signalOAuthCancellation_sends_access_denied_callback_over_https_loopback() {
        val receiver =
            LoopbackAuthorizationCodeReceiver(
                redirectUriOverride = "https://127.0.0.1:0/callback",
                logger = NoopLogger,
                resourceUrl = "https://mcp.example.com/mcp",
            )
        try {
            val result = signalOAuthCancellation(receiver.redirectUri)
            assertTrue(result.isSuccess)

            val callbackResult =
                runBlocking {
                    receiver.awaitCode(
                        authorizationUrl = "https://auth.example.com/authorize",
                        expectedState = "unused",
                        timeoutMillis = 1_000L,
                    )
                }
            assertTrue(callbackResult.isFailure)
            assertTrue(callbackResult.exceptionOrNull()?.message?.contains("cancelled", ignoreCase = true) == true)
        } finally {
            receiver.close()
        }
    }

    private object NoopLogger : Logger {
        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(
            message: String,
            throwable: Throwable?,
        ) = Unit

        override fun error(
            message: String,
            throwable: Throwable?,
        ) = Unit
    }
}
