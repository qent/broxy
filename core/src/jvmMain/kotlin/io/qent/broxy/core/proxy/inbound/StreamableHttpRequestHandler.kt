package io.qent.broxy.core.proxy.inbound

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.TimeoutCancellationException

internal data class StreamableHttpResponse(
    val status: HttpStatusCode,
    val body: String? = null,
    val contentType: ContentType? = null,
    val headers: Map<String, String> = emptyMap(),
)

internal class StreamableHttpRequestHandler(
    private val sessions: InboundStreamableHttpRegistry,
    private val sessionFactory: suspend (InboundSessionBinding) -> StreamableHttpSession,
    private val requestTimeoutMillisProvider: () -> Long,
) {
    suspend fun handleMessage(
        binding: InboundSessionBinding,
        requestedSessionId: String?,
        message: JSONRPCMessage,
    ): StreamableHttpResponse =
        runCatching {
            sessions.getOrCreate(requestedSessionId, binding) { sessionFactory(binding) }
        }.fold(
            onSuccess = { session ->
                session.touch()
                val headers = mapOf(MCP_SESSION_ID_HEADER to session.transport.sessionId)
                when (message) {
                    is JSONRPCRequest -> handleRequest(session, message, headers)
                    else -> {
                        runCatching { session.transport.handleMessage(message) }
                        session.touch()
                        StreamableHttpResponse(status = HttpStatusCode.Accepted, headers = headers)
                    }
                }
            },
            onFailure = { error ->
                val status =
                    when (error) {
                        is InboundSessionBindingConflictException -> HttpStatusCode.Conflict
                        is InboundPresetNotFoundException -> HttpStatusCode.NotFound
                        else -> HttpStatusCode.InternalServerError
                    }
                val messageBody =
                    when (error) {
                        is InboundSessionBindingConflictException -> error.message ?: "Session binding mismatch"
                        is InboundPresetNotFoundException -> error.message ?: "Preset not found"
                        else -> error.message ?: "Failed to create session"
                    }
                StreamableHttpResponse(
                    status = status,
                    body = messageBody,
                    contentType = ContentType.Text.Plain,
                )
            },
        )

    private suspend fun handleRequest(
        session: StreamableHttpSession,
        message: JSONRPCRequest,
        headers: Map<String, String>,
    ): StreamableHttpResponse {
        val requestTimeoutMillis = requestTimeoutMillisProvider().coerceAtLeast(MIN_REQUEST_TIMEOUT_MILLIS)
        val responseResult =
            runCatching {
                session.transport.awaitResponse(message, timeoutMillis = requestTimeoutMillis)
            }
        val response =
            responseResult.getOrElse { error ->
                val status =
                    if (error is TimeoutCancellationException) {
                        HttpStatusCode.RequestTimeout
                    } else {
                        HttpStatusCode.InternalServerError
                    }
                return StreamableHttpResponse(
                    status = status,
                    body = error.message ?: "Failed to handle MCP request",
                    contentType = ContentType.Text.Plain,
                    headers = headers,
                )
            }
        session.touch()
        return StreamableHttpResponse(
            status = HttpStatusCode.OK,
            body = McpJson.encodeToString(JSONRPCMessage.serializer(), response),
            contentType = ContentType.Application.Json,
            headers = headers,
        )
    }
}
