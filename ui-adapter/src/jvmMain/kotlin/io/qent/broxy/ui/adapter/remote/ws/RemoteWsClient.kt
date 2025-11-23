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
import io.ktor.websocket.readText
import io.ktor.websocket.readReason
import io.modelcontextprotocol.kotlin.sdk.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.shared.McpJson
import io.qent.broxy.core.proxy.ProxyMcpServer
import io.qent.broxy.core.proxy.inbound.buildSdkServer
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.ui.adapter.models.UiRemoteStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.consumeEach
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

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
    private var pingJob: Job? = null
    private var lastPong: Instant? = null

    suspend fun connect(): Result<Unit> = runCatching {
        logger.info("[RemoteWsClient] Dialing $url with serverIdentifier=$serverIdentifier")
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
            lastPong = Instant.now()
            onStatus(UiRemoteStatus.WsConnecting, null)

            val sessionJob = scope.launch(Dispatchers.IO) {
                val serverSession = server.connect(transport)
                serverSession.onClose {
                    onStatus(UiRemoteStatus.WsOffline, "Server session closed")
                    logger.warn("[RemoteWsClient] SDK server session closed")
                }
            }
            readerJob = scope.launch(Dispatchers.IO) {
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
                                "[RemoteWsClient] Inbound message session=${inbound.sessionIdentifier} ${describeJsonRpcPayload(inbound.message)}"
                            )
                            val message = runCatching {
                                McpJson.decodeFromJsonElement(JSONRPCMessage.serializer(), inbound.message)
                            }.onFailure { err ->
                                logger.warn(
                                    "[RemoteWsClient] Failed to decode JSON-RPC message for session=${inbound.sessionIdentifier}: ${err.message}"
                                )
                            }.getOrNull() ?: return@consumeEach
                            transport.handleIncoming(message, inbound.sessionIdentifier)
                            onStatus(UiRemoteStatus.WsOnline, null)
                        }
                        is Frame.Pong -> {
                            logger.debug("[RemoteWsClient] Pong received")
                            lastPong = Instant.now()
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
            }

            pingJob = scope.launch(Dispatchers.IO) {
                var missed = 0
                while (true) {
                    delay(15.seconds)
                    val s = session ?: break
                    s.send(Frame.Ping(ByteArray(0)))
                    val last = lastPong
                    if (last != null && Instant.now().isAfter(last.plusSeconds(30))) {
                        missed++
                    } else {
                        missed = 0
                    }
                    if (missed >= 2) {
                        logger.warn("[RemoteWsClient] Ping timeout after ${missed * 30}s; closing session")
                        onStatus(UiRemoteStatus.WsOffline, "Ping timeout")
                        close()
                        break
                    }
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

    suspend fun close() {
        pingJob?.cancel()
        readerJob?.cancel()
        logger.info("[RemoteWsClient] Closing WebSocket session")
        runCatching { session?.close() }
        session = null
    }
}
