package io.qent.broxy.core.mcp.clients

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.modelcontextprotocol.kotlin.sdk.client.mcpSse
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.client.mcpWebSocket
import io.qent.broxy.core.mcp.tls.configureCioCertificateValidation
import io.qent.broxy.core.utils.Logger
import kotlin.time.Duration.Companion.seconds

internal data class TransportConnection(
    val httpClient: HttpClient,
    val sdkClient: SdkClientFacade,
)

internal class TransportConnector(
    private val mode: KtorMcpClient.Mode,
    private val url: String,
    private val headersMap: Map<String, String>,
    private val logger: Logger,
    private val authCoordinator: AuthCoordinator,
    private val ignoreHttpsCertificateErrors: Boolean,
) {
    private companion object {
        private const val NANOS_PER_MILLI = 1_000_000
        private const val RECONNECT_SECONDS = 3
    }

    suspend fun connect(connectTimeout: Long): TransportConnection {
        val client = createHttpClient(connectTimeout)
        val reqBuilder = buildRequestBuilder()
        val sdk =
            when (mode) {
                KtorMcpClient.Mode.Sse ->
                    client.mcpSse(
                        urlString = url,
                        reconnectionTime = RECONNECT_SECONDS.seconds,
                        requestBuilder = reqBuilder,
                    )

                KtorMcpClient.Mode.StreamableHttp -> client.mcpStreamableHttp(url = url, requestBuilder = reqBuilder)
                KtorMcpClient.Mode.WebSocket -> client.mcpWebSocket(urlString = url, requestBuilder = reqBuilder)
            }
        return TransportConnection(client, RealSdkClientFacade(sdk, logger))
    }

    private fun createHttpClient(connectTimeout: Long): HttpClient {
        val client =
            HttpClient(CIO) {
                configureCioCertificateValidation(ignoreHttpsCertificateErrors)
                if (mode == KtorMcpClient.Mode.Sse || mode == KtorMcpClient.Mode.StreamableHttp) install(SSE)
                if (mode == KtorMcpClient.Mode.WebSocket) install(WebSockets)
                install(HttpTimeout) {
                    this.connectTimeoutMillis = connectTimeout
                    // Avoid request/socket timeouts so callTool can rely on outer coroutine timeouts.
                }
                HttpResponseValidator {
                    validateResponse { response ->
                        authCoordinator.recordChallenge(response)
                    }
                }
            }
        client.plugin(HttpSend).intercept { request ->
            val startNanos = System.nanoTime()
            val urlLabel = request.url.build().toLogString()
            val result = runCatching { execute(request) }
            val elapsedMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
            result
                .onSuccess { call ->
                    val status = call.response.status
                    logger.debug(
                        "HTTP ${request.method.value} $urlLabel -> " +
                            "${status.value} ${status.description} in ${elapsedMs}ms",
                    )
                }.onFailure { ex ->
                    logger.warn(
                        "HTTP ${request.method.value} $urlLabel failed after ${elapsedMs}ms",
                        ex,
                    )
                }
            result.getOrElse { throw it }
        }
        return client
    }

    private fun buildRequestBuilder(): HttpRequestBuilder.() -> Unit =
        {
            val token = authCoordinator.currentAccessToken()
            if (headersMap.isNotEmpty() || token != null) {
                headers {
                    headersMap.forEach { (k, v) -> append(k, v) }
                    if (token != null) {
                        remove(HttpHeaders.Authorization)
                        append(HttpHeaders.Authorization, "Bearer $token")
                    }
                }
            }
        }
}

internal fun Url.toLogString(): String {
    val portPart = if (port != protocol.defaultPort && port > 0) ":$port" else ""
    val path = encodedPath.takeIf { it.isNotBlank() } ?: "/"
    return "${protocol.name}://$host$portPart$path"
}
