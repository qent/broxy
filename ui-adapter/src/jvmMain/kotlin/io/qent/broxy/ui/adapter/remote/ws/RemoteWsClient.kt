package io.qent.broxy.ui.adapter.remote.ws

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.websocket.*
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.qent.broxy.core.proxy.ProxyMcpServer
import io.qent.broxy.core.proxy.inbound.buildSdkServer
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.ui.adapter.models.UiRemoteStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RemoteWsClient(
    private val httpClient: HttpClient,
    private val url: String,
    private val authToken: String,
    private val serverIdentifier: String,
    private val proxyProvider: () -> ProxyMcpServer?,
    private val logger: CollectingLogger,
    private val scope: CoroutineScope,
    private val onStatus: (UiRemoteStatus, String?) -> Unit,
    private val onAuthFailure: (String) -> Unit
) {
    private var session: WebSocketSession? = null
    private var readerJob: Job? = null
    private val messageMutex = Mutex()

    suspend fun connect(): Result<Unit> {
        val maxAttempts = 10
        var lastError: Throwable? = null

        for (attempt in 1..maxAttempts) {
            val result = runCatching {
                logger.info("[RemoteWsClient] Dialing $url with serverIdentifier=$serverIdentifier (attempt $attempt/$maxAttempts)")
                try {
                    onStatus(UiRemoteStatus.WsConnecting, null)
                    val proxy = proxyProvider()
                        ?: error("Proxy is not running; cannot attach remote client")
                    val transport = ProxyWebSocketTransport(serverIdentifier, logger) { payload ->
                        val s = session ?: error("WebSocket not connected")
                        val text = McpJson.encodeToString(McpProxyResponsePayload.serializer(), payload)
                        s.send(Frame.Text(text))
                    }
                    val server = buildSdkServer(proxy, logger)
                    val ws = httpClient.webSocketSession(url) {
                        header(HttpHeaders.Authorization, "Bearer $authToken")
                        header(HttpHeaders.SecWebSocketProtocol, "mcp")
                    }
                    logger.info("[RemoteWsClient] WebSocket session established; wiring SDK transport")
                    session = ws
                    onStatus(UiRemoteStatus.WsConnecting, null)

                    scope.launch(Dispatchers.IO) {
                        val serverSession = server.createSession(transport)
                        serverSession.onClose {
                            onStatus(UiRemoteStatus.WsOffline, "Server session closed")
                            logger.warn("[RemoteWsClient] SDK server session closed")
                        }
                    }
                    readerJob = scope.launch(Dispatchers.IO) {
                        try {
                            ws.incoming.consumeEach { frame ->
                                when (frame) {
                                    is Frame.Text -> {
                                        val text = frame.readText()
                                        val inbound = runCatching {
                                            McpJson.decodeFromString(McpProxyRequestPayload.serializer(), text)
                                        }.getOrElse { err ->
                                            logger.warn("[RemoteWsClient] malformed inbound frame: ${err.message}")
                                            return@consumeEach
                                        }
                                        logger.info(
                                            "[RemoteWsClient] Inbound message session=${inbound.sessionIdentifier} ${
                                                describeJsonRpcPayload(
                                                    inbound.message
                                                )
                                            }"
                                        )
                                        val message = runCatching {
                                            McpJson.decodeFromJsonElement(JSONRPCMessage.serializer(), inbound.message)
                                        }.onFailure { err ->
                                            logger.warn(
                                                "[RemoteWsClient] Failed to decode JSON-RPC message for session=${inbound.sessionIdentifier}: ${err.message}"
                                            )
                                        }.getOrNull() ?: return@consumeEach
                                        launch {
                                            runCatching {
                                                messageMutex.withLock {
                                                    transport.handleIncoming(message, inbound.sessionIdentifier)
                                                }
                                            }.onFailure { err ->
                                                logger.warn(
                                                    "[RemoteWsClient] Failed to handle inbound message for session=${inbound.sessionIdentifier}: ${err.message}"
                                                )
                                            }
                                        }
                                        onStatus(UiRemoteStatus.WsOnline, null)
                                    }

                                    is Frame.Close -> {
                                        val reason = frame.readReason()
                                        val textReason = reason?.message
                                        logger.warn("[RemoteWsClient] WebSocket closed: ${reason?.knownReason} ${textReason.orEmpty()}")
                                        if (reason?.knownReason == CloseReason.Codes.NORMAL) {
                                            onStatus(UiRemoteStatus.WsOffline, textReason)
                                        } else {
                                            onStatus(UiRemoteStatus.WsOffline, textReason ?: "Disconnected")
                                        }
                                    }

                                    else -> Unit
                                }
                            }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            val msg = t.message ?: "WebSocket receive failed"
                            logger.warn("[RemoteWsClient] WebSocket receive loop failed: $msg", t)
                            onStatus(UiRemoteStatus.WsOffline, msg)
                        }
                    }
                    onStatus(UiRemoteStatus.WsOnline, null)
                    logger.info("[RemoteWsClient] Remote WebSocket is online")
                } catch (ce: CancellationException) {
                    throw ce
                } catch (cre: ClientRequestException) {
                    val status = cre.response.status
                    val msg = "WebSocket unauthorized (${status.value})"
                    if (status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden) {
                        onAuthFailure(msg)
                    }
                    onStatus(UiRemoteStatus.WsOffline, msg)
                    logger.error("[RemoteWsClient] WebSocket authentication error: $msg", cre)
                    throw cre
                } catch (ex: Exception) {
                    val reason = ex.message ?: "WebSocket connect failed"
                    onStatus(UiRemoteStatus.WsOffline, reason)
                    onAuthFailure(reason)
                    logger.error("[RemoteWsClient] WebSocket connect failed: $reason", ex)
                    throw ex
                }
            }

            if (result.isSuccess) {
                return result
            }

            lastError = result.exceptionOrNull()
            if (attempt < maxAttempts) {
                val backoffMillis = kotlin.math.min(1000L * (1L shl (attempt - 1)), 30000L)
                logger.warn("[RemoteWsClient] WebSocket connect attempt $attempt/$maxAttempts failed: ${lastError?.message}; retrying in ${backoffMillis} ms")
                kotlinx.coroutines.delay(backoffMillis)
            }
        }

        return Result.failure(
            lastError ?: IllegalStateException("WebSocket connect failed after $maxAttempts attempts")
        )
    }

    suspend fun close() {
        readerJob?.cancel()
        logger.info("[RemoteWsClient] Closing WebSocket session")
        runCatching { session?.close() }
        session = null
    }
}
