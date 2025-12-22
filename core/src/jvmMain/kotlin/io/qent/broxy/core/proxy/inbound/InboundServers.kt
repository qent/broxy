package io.qent.broxy.core.proxy.inbound

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCError
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.qent.broxy.core.mcp.ServerStatus
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.proxy.ProxyMcpServer
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.net.BindException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Abstraction for platform-specific inbound transport servers that expose
 * the proxy over STDIO / HTTP(Streamable). These classes adapt the
 * wire protocol to the [ProxyMcpServer]'s filtering and routing.
 */
interface InboundServer {
    fun start(): ServerStatus

    fun stop(): ServerStatus

    fun refreshCapabilities(): Result<Unit>
}

object InboundServerFactory {
    fun create(
        transport: TransportConfig,
        proxy: ProxyMcpServer,
        logger: Logger = ConsoleLogger,
    ): InboundServer =
        when (transport) {
            is TransportConfig.StdioTransport -> StdioInboundServer(proxy, logger)
            is TransportConfig.StreamableHttpTransport ->
                KtorStreamableHttpInboundServer(
                    url = transport.url,
                    proxy = proxy,
                    logger = logger,
                )
            // Backward compatibility: historically inbound used HttpTransport (SSE).
            // We now treat it as Streamable HTTP so older callers keep working.
            is TransportConfig.HttpTransport ->
                KtorStreamableHttpInboundServer(
                    url = transport.url,
                    proxy = proxy,
                    logger = logger,
                )

            else -> error("Unsupported inbound transport: ${transport::class.simpleName}")
        }
}

private class StdioInboundServer(
    private val proxy: ProxyMcpServer,
    private val logger: Logger,
) : InboundServer {
    private var server: Server? = null
    private var session: ServerSession? = null

    override fun start(): ServerStatus {
        logger.info("Starting STDIO inbound server (MCP SDK)")
        val input = System.`in`.asSource().buffered()
        val output = System.out.asSink().buffered()
        val transport = io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport(input, output)
        val server = buildSdkServer(proxy, logger)
        this.server = server
        return try {
            session = kotlinx.coroutines.runBlocking { server.createSession(transport) }
            ServerStatus.Running
        } catch (t: Throwable) {
            this.server = null
            this.session = null
            logger.error("Failed to start STDIO MCP server", t)
            ServerStatus.Error(t.message)
        }
    }

    override fun stop(): ServerStatus {
        logger.info("Stopping STDIO inbound server")
        val server = this.server
        val session = this.session
        this.server = null
        this.session = null
        runCatching { kotlinx.coroutines.runBlocking { session?.close() } }
        runCatching { kotlinx.coroutines.runBlocking { server?.close() } }
        return ServerStatus.Stopped
    }

    override fun refreshCapabilities(): Result<Unit> {
        val server = server ?: return Result.failure(IllegalStateException("STDIO inbound server is not running"))
        return runCatching { syncSdkServer(server, proxy, logger) }
    }
}

private class KtorStreamableHttpInboundServer(
    private val url: String,
    private val proxy: ProxyMcpServer,
    private val logger: Logger,
) : InboundServer {
    private var engine: EmbeddedServer<*, *>? = null
    private var server: Server? = null

    override fun start(): ServerStatus {
        val (host, port, rawPath) = parse(url)
        val scheme = runCatching { URI(url).scheme }.getOrNull()?.takeIf { it.isNotBlank() } ?: "http"
        val normalizedPath = normalizePath(rawPath)
        val displayPath = if (normalizedPath.display.isBlank()) "/" else normalizedPath.display
        logger.info("Starting HTTP Streamable inbound at $scheme://$host:$port$displayPath")
        logger.debug("HTTP inbound route segments='${normalizedPath.routeSegments.ifBlank { "/" }}'")
        return try {
            val sdkServer = buildSdkServer(proxy, logger)
            server = sdkServer
            val sessions = InboundStreamableHttpRegistry(logger)
            engine =
                embeddedServer(Netty, host = host, port = port, module = {
                    install(CallLogging) {
                        // Avoid ANSI/Jansi native initialization in packaged apps.
                        disableDefaultColors()
                    }
                    routing {
                        if (normalizedPath.routeSegments.isBlank()) {
                            mountStreamableHttpRoute(server = sdkServer, sessions = sessions)
                        } else {
                            route("/${normalizedPath.routeSegments}") {
                                mountStreamableHttpRoute(server = sdkServer, sessions = sessions)
                            }
                        }
                    }
                }).start(wait = false)
            ServerStatus.Running
        } catch (t: Throwable) {
            engine = null
            server = null
            val bind = t.findCause<BindException>()
            val message =
                if (bind != null) {
                    "Port $port is already in use"
                } else {
                    t.message ?: "Failed to start HTTP Streamable inbound server"
                }
            logger.error(message, t)
            ServerStatus.Error(message)
        }
    }

    override fun stop(): ServerStatus {
        val srv = engine
        engine = null
        val sdkServer = server
        server = null
        srv?.stop()
        runCatching { kotlinx.coroutines.runBlocking { sdkServer?.close() } }
        return ServerStatus.Stopped
    }

    override fun refreshCapabilities(): Result<Unit> {
        val sdkServer = server ?: return Result.failure(IllegalStateException("HTTP inbound server is not running"))
        return runCatching { syncSdkServer(sdkServer, proxy, logger) }
    }
}

private inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var cur: Throwable? = this
    while (cur != null) {
        if (cur is T) return cur
        cur = cur.cause
    }
    return null
}

private fun parse(url: String): Triple<String, Int, String> {
    val u = URI(url)
    val host = if (u.host.isNullOrBlank()) "0.0.0.0" else u.host
    val port = if (u.port == -1) (if (u.scheme == "https" || u.scheme == "wss") 443 else 80) else u.port
    val path = if (u.path.isNullOrBlank()) "/mcp" else u.path
    return Triple(host, port, if (path.endsWith('/')) path.dropLast(1) else path)
}

private fun normalizePath(rawPath: String): NormalizedPath {
    val trimmed = rawPath.trim().ifBlank { "/" }
    val withoutPrefix = trimmed.removePrefix("/")
    val routeSegments = withoutPrefix.trim()
    val display = if (routeSegments.isBlank()) "/" else "/$routeSegments"
    return NormalizedPath(display = display, routeSegments = routeSegments)
}

private data class NormalizedPath(
    val display: String,
    val routeSegments: String,
)

private const val MCP_SESSION_ID_HEADER = "mcp-session-id"
private const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 60_000L

private fun isApplicationJson(contentType: ContentType): Boolean =
    contentType.contentType == ContentType.Application.Json.contentType &&
        contentType.contentSubtype == ContentType.Application.Json.contentSubtype

private class InboundStreamableHttpRegistry(
    private val logger: Logger,
) {
    private val sessions = ConcurrentHashMap<String, StreamableHttpSession>()

    suspend fun getOrCreate(
        server: Server,
        requestedSessionId: String?,
    ): StreamableHttpSession {
        if (!requestedSessionId.isNullOrBlank()) {
            sessions[requestedSessionId]?.let { return it }
        }

        val transport = StreamableHttpServerTransport(logger = logger)
        val session = server.createSession(transport)
        val entry = StreamableHttpSession(transport, session)
        sessions[transport.sessionId] = entry
        logger.debug("Registered Streamable HTTP session ${transport.sessionId}")
        return entry
    }

    suspend fun remove(sessionId: String?): Result<Unit> =
        runCatching {
            if (sessionId.isNullOrBlank()) return@runCatching
            val existing = sessions.remove(sessionId) ?: return@runCatching
            runCatching { existing.serverSession.close() }
            runCatching { existing.transport.close() }
            logger.debug("Removed Streamable HTTP session $sessionId")
        }
}

private data class StreamableHttpSession(
    val transport: StreamableHttpServerTransport,
    val serverSession: ServerSession,
)

@OptIn(ExperimentalAtomicApi::class)
private class StreamableHttpServerTransport(
    private val logger: Logger,
) : AbstractTransport() {
    private val initialized: AtomicBoolean = AtomicBoolean(false)
    val sessionId: String = java.util.UUID.randomUUID().toString()

    private val responseWaiters = ConcurrentHashMap<RequestId, CompletableDeferred<JSONRPCMessage>>()

    override suspend fun start() {
        if (!initialized.compareAndSet(expectedValue = false, newValue = true)) {
            error("StreamableHttpServerTransport already started!")
        }
    }

    suspend fun handleMessage(message: JSONRPCMessage) {
        if (!initialized.load()) error("Transport is not started")
        _onMessage.invoke(message)
    }

    suspend fun awaitResponse(
        request: JSONRPCRequest,
        timeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
    ): JSONRPCMessage {
        val deferred = CompletableDeferred<JSONRPCMessage>()
        val previous = responseWaiters.putIfAbsent(request.id, deferred)
        check(previous == null) { "Duplicate in-flight request id ${request.id}" }
        try {
            handleMessage(request)
            return withTimeout(timeoutMillis) { deferred.await() }
        } finally {
            responseWaiters.remove(request.id)
        }
    }

    override suspend fun send(
        message: JSONRPCMessage,
        options: TransportSendOptions?,
    ) {
        if (!initialized.load()) error("Not connected")
        when (message) {
            is JSONRPCResponse -> {
                val waiter = responseWaiters[message.id]
                if (waiter != null && waiter.complete(message)) return
                logger.warn("Dropping response for unknown request id ${message.id}")
            }

            is JSONRPCError -> {
                val waiter = responseWaiters[message.id]
                if (waiter != null && waiter.complete(message)) return
                logger.warn("Dropping error response for unknown request id ${message.id}")
            }

            else -> {
                // JSON-only Streamable HTTP inbound: server-to-client notifications are best-effort dropped.
                logger.debug("Dropping outbound message (no SSE): ${message::class.simpleName}")
            }
        }
    }

    override suspend fun close() {
        if (!initialized.load()) return
        initialized.store(false)
        _onClose.invoke()
    }
}

private fun Route.mountStreamableHttpRoute(
    server: Server,
    sessions: InboundStreamableHttpRegistry,
) {
    get {
        call.respond(HttpStatusCode.MethodNotAllowed, "SSE stream is not supported; use Streamable HTTP POST")
    }

    delete {
        val sessionId = call.request.headers[MCP_SESSION_ID_HEADER]
        if (sessionId.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, "Missing $MCP_SESSION_ID_HEADER header")
            return@delete
        }
        sessions.remove(sessionId)
        call.respond(HttpStatusCode.NoContent)
    }

    post {
        val ct = call.request.contentType()
        if (!isApplicationJson(ct)) {
            call.respond(HttpStatusCode.BadRequest, "Unsupported content-type: $ct")
            return@post
        }

        val requestedSessionId = call.request.headers[MCP_SESSION_ID_HEADER]
        val session = sessions.getOrCreate(server, requestedSessionId)
        call.response.headers.append(MCP_SESSION_ID_HEADER, session.transport.sessionId)

        val body = call.receiveText()
        val message =
            runCatching { McpJson.decodeFromString<JSONRPCMessage>(body) }
                .getOrElse { error ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        "Invalid MCP message: ${error.message ?: error::class.simpleName}",
                    )
                    return@post
                }

        when (message) {
            is JSONRPCRequest -> {
                val response =
                    runCatching {
                        session.transport.awaitResponse(message, timeoutMillis = DEFAULT_REQUEST_TIMEOUT_MILLIS)
                    }.getOrElse { error ->
                        val status =
                            if (error is TimeoutCancellationException) HttpStatusCode.RequestTimeout else HttpStatusCode.InternalServerError
                        call.respond(status, error.message ?: "Failed to handle MCP request")
                        return@post
                    }
                call.respondText(
                    text = McpJson.encodeToString(JSONRPCMessage.serializer(), response),
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.OK,
                )
            }

            else -> {
                runCatching { session.transport.handleMessage(message) }
                call.respond(HttpStatusCode.OK)
            }
        }
    }
}
