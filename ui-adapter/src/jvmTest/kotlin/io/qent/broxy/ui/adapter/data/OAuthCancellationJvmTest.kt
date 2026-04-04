package io.qent.broxy.ui.adapter.data

import com.sun.net.httpserver.HttpServer
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
}
