package io.qent.broxy.core.proxy.inbound

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.sse.sse
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.SseServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.awaitCancellation

private fun isApplicationJson(contentType: ContentType): Boolean =
    contentType.contentType == ContentType.Application.Json.contentType &&
        contentType.contentSubtype == ContentType.Application.Json.contentSubtype

internal fun Route.mountSseRoute(
    server: Server,
    sessions: InboundSseRegistry,
) {
    sse {
        val transport = SseServerTransport(SSE_ENDPOINT_PATH, this)
        val session = server.createSession(transport)
        sessions.register(transport, session)
        sessions.touch(transport.sessionId)
        session.onClose { sessions.remove(transport.sessionId) }
        awaitCancellation()
    }

    post {
        val sessionId = call.request.queryParameters[SSE_SESSION_ID_PARAM]
        if (sessionId.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, "Missing $SSE_SESSION_ID_PARAM query parameter")
            return@post
        }

        val session = sessions.get(sessionId)
        if (session == null) {
            call.respond(HttpStatusCode.NotFound, "Session not found")
            return@post
        }

        sessions.touch(sessionId)
        session.transport.handlePostMessage(call)
        sessions.touch(sessionId)
    }
}

internal fun Route.mountStreamableHttpRoute(
    server: Server,
    sessions: InboundStreamableHttpRegistry,
    requestTimeoutMillisProvider: () -> Long,
) {
    val handler = StreamableHttpRequestHandler(server, sessions, requestTimeoutMillisProvider)
    get { handleStreamableHttpGet(call) }
    delete { handleStreamableHttpDelete(call, sessions) }
    post { handleStreamableHttpPost(call, handler) }
}

private suspend fun handleStreamableHttpGet(call: ApplicationCall) {
    call.respond(
        HttpStatusCode.MethodNotAllowed,
        "SSE is available at $SSE_ENDPOINT_PATH; use Streamable HTTP POST on this endpoint",
    )
}

private suspend fun handleStreamableHttpDelete(
    call: ApplicationCall,
    sessions: InboundStreamableHttpRegistry,
) {
    val sessionId = call.request.headers[MCP_SESSION_ID_HEADER]
    if (sessionId.isNullOrBlank()) {
        call.respond(HttpStatusCode.BadRequest, "Missing $MCP_SESSION_ID_HEADER header")
        return
    }
    sessions.remove(sessionId)
    call.respond(HttpStatusCode.NoContent)
}

private suspend fun handleStreamableHttpPost(
    call: ApplicationCall,
    handler: StreamableHttpRequestHandler,
) {
    val ct = call.request.contentType()
    if (!isApplicationJson(ct)) {
        call.respond(HttpStatusCode.BadRequest, "Unsupported content-type: $ct")
        return
    }

    val requestedSessionId = call.request.headers[MCP_SESSION_ID_HEADER]
    val body = call.receiveText()
    val message = decodeStreamableHttpMessage(call, body) ?: return

    val response = handler.handleMessage(requestedSessionId, message)
    respondStreamableHttp(call, response)
}

private suspend fun decodeStreamableHttpMessage(
    call: ApplicationCall,
    body: String,
): JSONRPCMessage? {
    val decoded = runCatching { McpJson.decodeFromString<JSONRPCMessage>(body) }
    return decoded.getOrElse { error ->
        call.respond(
            HttpStatusCode.BadRequest,
            "Invalid MCP message: ${error.message ?: error::class.simpleName}",
        )
        null
    }
}

private suspend fun respondStreamableHttp(
    call: ApplicationCall,
    response: StreamableHttpResponse,
) {
    response.headers.forEach { (key, value) -> call.response.headers.append(key, value) }
    val body = response.body
    if (body == null) {
        call.respond(response.status)
        return
    }
    val contentType = response.contentType
    if (contentType == null) {
        call.respondText(text = body, status = response.status)
    } else {
        call.respondText(text = body, contentType = contentType, status = response.status)
    }
}

internal fun Route.mountStreamableHttpRoute(
    server: Server,
    sessions: InboundStreamableHttpRegistry,
) {
    mountStreamableHttpRoute(server, sessions, requestTimeoutMillisProvider = { DEFAULT_REQUEST_TIMEOUT_MILLIS })
}

internal fun Route.mountStreamableHttpRoute(
    server: Server,
    sessions: InboundStreamableHttpRegistry,
    requestTimeoutMillis: Long,
) {
    mountStreamableHttpRoute(server, sessions, requestTimeoutMillisProvider = { requestTimeoutMillis })
}
