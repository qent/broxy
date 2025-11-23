package io.qent.broxy.ui.adapter.remote.auth

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress

class LoopbackCallbackServer(
    private val port: Int = DEFAULT_PORT,
    private val logger: Logger? = null
) {
    companion object {
        const val DEFAULT_PORT: Int = 8765
    }

    private var server: HttpServer? = null

    suspend fun awaitCallback(expectedState: String, timeoutMillis: Long = 120_000): OAuthCallback? =
        withContext(Dispatchers.IO) {
            val deferred = CompletableDeferred<OAuthCallback?>()
            val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).also {
                logger?.info("[RemoteAuth] Loopback callback server listening on 127.0.0.1:$port for state=$expectedState")
            }
            server = httpServer
            httpServer.createContext("/oauth/callback", Handler(expectedState, deferred, logger))
            httpServer.start()
            try {
                withContext(Dispatchers.IO) {
                    withTimeout(timeoutMillis) {
                        deferred.await()
                    }
                }
            } catch (t: TimeoutCancellationException) {
                logger?.warn("[RemoteAuth] OAuth callback wait timed out after ${timeoutMillis}ms for state=$expectedState", t)
                null
            } catch (ex: Exception) {
                logger?.error("[RemoteAuth] OAuth callback wait failed: ${ex.message}", ex)
                null
            } finally {
                stop()
            }
        }

    fun stop() {
        runCatching { server?.stop(0) }
        logger?.debug("[RemoteAuth] Loopback callback server stopped")
        server = null
    }

    private class Handler(
        private val expectedState: String,
        private val deferred: CompletableDeferred<OAuthCallback?>,
        private val logger: Logger?
    ) : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            val query = exchange.requestURI.query.orEmpty()
            logger?.debug("[RemoteAuth] OAuth callback hit: path=${exchange.requestURI.path}, query=$query")
            val params = parseQuery(query)
            val code = params["code"]
            val state = params["state"]
            val ok = code != null && state != null && state == expectedState
            val body = if (ok) {
                "<html><body><h3>Authorization received. You can close this window.</h3></body></html>"
            } else {
                "<html><body><h3>Missing or invalid authorization parameters.</h3></body></html>"
            }
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(if (ok) 200 else 400, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
            if (ok) {
                logger?.info("[RemoteAuth] OAuth callback accepted; state verified")
                deferred.complete(OAuthCallback(code!!, state!!))
            } else {
                logger?.warn("[RemoteAuth] OAuth callback rejected: code/state mismatch (expected=$expectedState, gotState=$state)")
                deferred.complete(null)
            }
        }

        private fun parseQuery(query: String): Map<String, String> =
            query.split("&")
                .mapNotNull { pair ->
                    val idx = pair.indexOf('=')
                    if (idx <= 0) return@mapNotNull null
                    val key = decode(pair.substring(0, idx))
                    val value = decode(pair.substring(idx + 1))
                    key to value
                }
                .toMap()

        private fun decode(value: String): String = try {
            java.net.URLDecoder.decode(value, Charsets.UTF_8)
        } catch (_: Exception) {
            value
        }
    }
}
