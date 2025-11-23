package io.qent.broxy.ui.adapter.remote.auth

import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder

class LoopbackCallbackServer(
    private val port: Int = DEFAULT_PORT,
    private val logger: Logger? = null
) {
    companion object {
        const val DEFAULT_PORT: Int = 8765
    }

    private var serverSocket: ServerSocket? = null

    suspend fun awaitCallback(expectedState: String, timeoutMillis: Long = 120_000): OAuthCallback? =
        withContext(Dispatchers.IO) {
            val socket = ServerSocket().apply {
                reuseAddress = true
                soTimeout = timeoutMillis.toInt()
                try {
                    bind(InetSocketAddress("127.0.0.1", port))
                    logger?.info("[RemoteAuth] Loopback callback server listening on 127.0.0.1:$port for state=$expectedState")
                } catch (ex: Exception) {
                    logger?.error("[RemoteAuth] Failed to start loopback callback server on 127.0.0.1:$port: ${ex.message}", ex)
                    throw ex
                }
            }
            serverSocket = socket
            try {
                withTimeout(timeoutMillis) {
                    acceptOnce(socket, expectedState)
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
        runCatching { serverSocket?.close() }
        logger?.debug("[RemoteAuth] Loopback callback server stopped")
        serverSocket = null
    }

    private fun acceptOnce(socket: ServerSocket, expectedState: String): OAuthCallback? {
        return try {
            val client = socket.accept()
            client.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val writer = OutputStreamWriter(s.getOutputStream())
                val requestLine = reader.readLine().orEmpty()
                val pathWithQuery = requestLine.split(" ").getOrNull(1).orEmpty()
                logger?.debug("[RemoteAuth] OAuth callback hit: pathWithQuery=$pathWithQuery")
                val uri = runCatching { URI("http://localhost$pathWithQuery") }.getOrNull()
                val queryParams = parseQuery(uri?.rawQuery.orEmpty())
                val code = queryParams["code"]
                val state = queryParams["state"]
                val ok = code != null && state != null && state == expectedState
                val body = if (ok) {
                    "<html><body><h3>Authorization received. You can close this window.</h3></body></html>"
                } else {
                    "<html><body><h3>Missing or invalid authorization parameters.</h3></body></html>"
                }
                val statusLine = if (ok) "HTTP/1.1 200 OK" else "HTTP/1.1 400 Bad Request"
                writer.write("$statusLine\r\nContent-Type: text/html\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n")
                writer.write(body)
                writer.flush()
                if (ok) {
                    logger?.info("[RemoteAuth] OAuth callback accepted; state verified")
                    OAuthCallback(code!!, state!!)
                } else {
                    logger?.warn("[RemoteAuth] OAuth callback rejected: code/state mismatch (expected=$expectedState, gotState=$state)")
                    null
                }
            }
        } catch (ste: SocketTimeoutException) {
            logger?.warn("[RemoteAuth] OAuth callback accept timed out on 127.0.0.1:$port after ${socket.soTimeout}ms")
            null
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
        URLDecoder.decode(value, Charsets.UTF_8)
    } catch (_: Exception) {
        value
    }
}
