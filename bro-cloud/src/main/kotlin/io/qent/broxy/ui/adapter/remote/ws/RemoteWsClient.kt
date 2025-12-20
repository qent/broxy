package io.qent.broxy.ui.adapter.remote.ws

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readReason
import io.ktor.websocket.readText
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.qent.broxy.cloud.api.CloudLogger
import io.qent.broxy.cloud.api.CloudProxyRuntime
import io.qent.broxy.cloud.api.CloudRemoteStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RemoteWsClient(
    private val httpClient: HttpClient,
    private val url: String,
    private val authToken: String,
    private val serverIdentifier: String,
    private val proxyRuntime: CloudProxyRuntime,
    private val logger: CloudLogger,
    private val scope: CoroutineScope,
    private val onStatus: (CloudRemoteStatus, String?) -> Unit,
    private val onAuthFailure: (String) -> Unit,
) {
    private var session: WebSocketSession? = null
    private var readerJob: Job? = null
    private val messageMutex = Mutex()

    suspend fun connect(): Result<Unit> {
        val maxAttempts = 10
        var lastError: Throwable? = null

        for (attempt in 1..maxAttempts) {
            val result =
                runCatching {
                    logger.info("[RemoteWsClient] Dialing $url with serverIdentifier=$serverIdentifier (attempt $attempt/$maxAttempts)")
                    try {
                        onStatus(CloudRemoteStatus.WsConnecting, null)
                        val transport =
                            ProxyWebSocketTransport(serverIdentifier, logger) { payload ->
                                val s = session ?: error("WebSocket not connected")
                                val text = McpJson.encodeToString(McpProxyResponsePayload.serializer(), payload)
                                s.send(Frame.Text(text))
                            }
                        val ws =
                            httpClient.webSocketSession(url) {
                                header(HttpHeaders.Authorization, "Bearer $authToken")
                                header(HttpHeaders.SecWebSocketProtocol, "mcp")
                            }
                        logger.info("[RemoteWsClient] WebSocket session established; wiring SDK transport")
                        session = ws
                        onStatus(CloudRemoteStatus.WsConnecting, null)

                        scope.launch(Dispatchers.IO) {
                            val serverSession = proxyRuntime.createSession(transport)
                            serverSession.onClose {
                                onStatus(CloudRemoteStatus.WsOffline, "Server session closed")
                                logger.warn("[RemoteWsClient] SDK server session closed")
                            }
                        }
                        readerJob =
                            scope.launch(Dispatchers.IO) {
                                try {
                                    ws.incoming.consumeEach { frame ->
                                        when (frame) {
                                            is Frame.Text -> {
                                                val text = frame.readText()
                                                val inbound =
                                                    runCatching {
                                                        McpJson.decodeFromString(McpProxyRequestPayload.serializer(), text)
                                                    }.getOrElse { err ->
                                                        logger.warn("[RemoteWsClient] malformed inbound frame: ${err.message}")
                                                        return@consumeEach
                                                    }
                                                logger.info(
                                                    "[RemoteWsClient] Inbound message session=${inbound.sessionIdentifier} ${
                                                        describeJsonRpcPayload(
                                                            inbound.message,
                                                        )
                                                    }",
                                                )
                                                val message =
                                                    runCatching {
                                                        McpJson.decodeFromJsonElement(JSONRPCMessage.serializer(), inbound.message)
                                                    }.onFailure { err ->
                                                        logger.warn(
                                                            "[RemoteWsClient] Failed to decode JSON-RPC message for session=" +
                                                                "${inbound.sessionIdentifier}: ${err.message}",
                                                        )
                                                    }.getOrNull() ?: return@consumeEach
                                                launch {
                                                    runCatching {
                                                        messageMutex.withLock {
                                                            transport.handleIncoming(message, inbound.sessionIdentifier)
                                                        }
                                                    }.onFailure { err ->
                                                        logger.warn(
                                                            "[RemoteWsClient] Failed to handle inbound message for session=" +
                                                                "${inbound.sessionIdentifier}: ${err.message}",
                                                        )
                                                    }
                                                }
                                                onStatus(CloudRemoteStatus.WsOnline, null)
                                            }

                                            is Frame.Close -> {
                                                val reason = frame.readReason()
                                                val textReason = reason?.message
                                                logger.warn(
                                                    "[RemoteWsClient] WebSocket closed: " +
                                                        "${reason?.knownReason} ${textReason.orEmpty()}",
                                                )
                                                if (reason?.knownReason == CloseReason.Codes.NORMAL) {
                                                    onStatus(CloudRemoteStatus.WsOffline, textReason)
                                                } else {
                                                    onStatus(CloudRemoteStatus.WsOffline, textReason ?: "Disconnected")
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
                                    onStatus(CloudRemoteStatus.WsOffline, msg)
                                }
                            }
                        onStatus(CloudRemoteStatus.WsOnline, null)
                        logger.info("[RemoteWsClient] Remote WebSocket is online")
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (cre: ClientRequestException) {
                        val status = cre.response.status
                        val msg = "WebSocket unauthorized (${status.value})"
                        if (status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden) {
                            onAuthFailure(msg)
                        }
                        onStatus(CloudRemoteStatus.WsOffline, msg)
                        logger.error("[RemoteWsClient] WebSocket authentication error: $msg", cre)
                        throw cre
                    } catch (ex: Exception) {
                        val reason = ex.message ?: "WebSocket connect failed"
                        onStatus(CloudRemoteStatus.WsOffline, reason)
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
                logger.warn(
                    "[RemoteWsClient] WebSocket connect attempt $attempt/$maxAttempts failed: " +
                        "${lastError?.message}; retrying in $backoffMillis ms",
                )
                delay(backoffMillis)
            }
        }

        return Result.failure(
            lastError ?: IllegalStateException("WebSocket connect failed after $maxAttempts attempts"),
        )
    }

    suspend fun close() {
        readerJob?.cancel()
        logger.info("[RemoteWsClient] Closing WebSocket session")
        try {
            session?.close()
        } catch (error: Throwable) {
            logger.warn("[RemoteWsClient] Failed to close WebSocket session: ${error.message}", error)
        }
        session = null
    }
}
