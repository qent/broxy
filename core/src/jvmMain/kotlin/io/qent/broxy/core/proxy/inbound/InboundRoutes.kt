package io.qent.broxy.core.proxy.inbound

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.plugin
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.sse.SSE
import io.ktor.server.sse.SSEServerContent
import io.modelcontextprotocol.kotlin.sdk.server.SseServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.awaitCancellation

private fun isApplicationJson(contentType: ContentType): Boolean =
    contentType.contentType == ContentType.Application.Json.contentType &&
        contentType.contentSubtype == ContentType.Application.Json.contentSubtype

internal fun Route.mountSseRoute(
    sessions: InboundSseRegistry,
    bindingProvider: (ApplicationCall) -> InboundSessionBinding,
    sessionFactory: suspend (InboundSessionBinding, SseServerTransport) -> SseSession,
) {
    get {
        val binding =
            try {
                bindingProvider(call)
            } catch (missingPreset: InboundPresetNotFoundException) {
                call.respond(HttpStatusCode.NotFound, missingPreset.message ?: "Preset not found")
                return@get
            }
        call.application.plugin(SSE)
        call.response.headers.append(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.response.headers.append(HttpHeaders.Connection, "keep-alive")
        call.response.headers.append("X-Accel-Buffering", "no")
        call.respond(
            SSEServerContent(call) {
                val transport = SseServerTransport(binding.routePath, this)
                val session = sessionFactory(binding, transport)
                sessions.register(session)
                sessions.touch(session.transport.sessionId)
                session.serverSession.onClose { sessions.remove(session.transport.sessionId) }
                awaitCancellation()
            },
        )
    }

    post {
        val binding =
            try {
                bindingProvider(call)
            } catch (missingPreset: InboundPresetNotFoundException) {
                call.respond(HttpStatusCode.NotFound, missingPreset.message ?: "Preset not found")
                return@post
            }
        val sessionId = call.request.queryParameters[SSE_SESSION_ID_PARAM]
        if (sessionId.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, "Missing $SSE_SESSION_ID_PARAM query parameter")
            return@post
        }

        val session =
            try {
                sessions.get(sessionId, binding)
            } catch (conflict: InboundSessionBindingConflictException) {
                call.respond(HttpStatusCode.Conflict, conflict.message ?: "Session binding mismatch")
                return@post
            }
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
    sessions: InboundStreamableHttpRegistry,
    bindingProvider: (ApplicationCall) -> InboundSessionBinding,
    sessionFactory: suspend (InboundSessionBinding) -> StreamableHttpSession,
    requestTimeoutMillisProvider: () -> Long,
) {
    val handler = StreamableHttpRequestHandler(sessions, sessionFactory, requestTimeoutMillisProvider)
    get { handleStreamableHttpGet(call) }
    delete {
        val binding =
            try {
                bindingProvider(call)
            } catch (missingPreset: InboundPresetNotFoundException) {
                call.respond(HttpStatusCode.NotFound, missingPreset.message ?: "Preset not found")
                return@delete
            }
        handleStreamableHttpDelete(call, sessions, binding)
    }
    post {
        val binding =
            try {
                bindingProvider(call)
            } catch (missingPreset: InboundPresetNotFoundException) {
                call.respond(HttpStatusCode.NotFound, missingPreset.message ?: "Preset not found")
                return@post
            }
        handleStreamableHttpPost(call, handler, binding)
    }
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
    binding: InboundSessionBinding,
) {
    val sessionId = call.request.headers[MCP_SESSION_ID_HEADER]
    if (sessionId.isNullOrBlank()) {
        call.respond(HttpStatusCode.BadRequest, "Missing $MCP_SESSION_ID_HEADER header")
        return
    }
    val result = sessions.remove(sessionId, binding)
    val error = result.exceptionOrNull()
    val response =
        when (error) {
            is InboundSessionBindingConflictException ->
                HttpStatusCode.Conflict to (error.message ?: "Session binding mismatch")
            null -> HttpStatusCode.NoContent to null
            else -> HttpStatusCode.InternalServerError to (error.message ?: "Failed to close session")
        }
    val (status, message) = response
    if (message == null) {
        call.respond(status)
    } else {
        call.respond(status, message)
    }
}

private suspend fun handleStreamableHttpPost(
    call: ApplicationCall,
    handler: StreamableHttpRequestHandler,
    binding: InboundSessionBinding,
) {
    val ct = call.request.contentType()
    if (!isApplicationJson(ct)) {
        call.respond(HttpStatusCode.BadRequest, "Unsupported content-type: $ct")
        return
    }

    val requestedSessionId = call.request.headers[MCP_SESSION_ID_HEADER]
    val body = call.receiveText()
    val message = decodeStreamableHttpMessage(call, body) ?: return

    val response = handler.handleMessage(binding, requestedSessionId, message)
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
    sessions: InboundStreamableHttpRegistry,
    bindingProvider: (ApplicationCall) -> InboundSessionBinding,
    sessionFactory: suspend (InboundSessionBinding) -> StreamableHttpSession,
) {
    mountStreamableHttpRoute(
        sessions = sessions,
        bindingProvider = bindingProvider,
        sessionFactory = sessionFactory,
        requestTimeoutMillisProvider = { DEFAULT_REQUEST_TIMEOUT_MILLIS },
    )
}

internal fun Route.mountStreamableHttpRoute(
    sessions: InboundStreamableHttpRegistry,
    bindingProvider: (ApplicationCall) -> InboundSessionBinding,
    sessionFactory: suspend (InboundSessionBinding) -> StreamableHttpSession,
    requestTimeoutMillis: Long,
) {
    mountStreamableHttpRoute(
        sessions = sessions,
        bindingProvider = bindingProvider,
        sessionFactory = sessionFactory,
        requestTimeoutMillisProvider = { requestTimeoutMillis },
    )
}
