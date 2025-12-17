package io.qent.broxy.core.proxy.inbound

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import io.qent.broxy.core.mcp.ServerStatus
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.proxy.ProxyMcpServer
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger
import io.modelcontextprotocol.kotlin.sdk.server.SseServerTransport
import kotlinx.coroutines.Job
import java.net.BindException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Abstraction for platform-specific inbound transport servers that expose
 * the proxy over STDIO / HTTP(SSE). These classes adapt the
 * wire protocol to the [ProxyMcpServer]'s filtering and routing.
 */
interface InboundServer {
    fun start(): ServerStatus
    fun stop(): ServerStatus
}

object InboundServerFactory {
    fun create(
        transport: TransportConfig,
        proxy: ProxyMcpServer,
        logger: Logger = ConsoleLogger
    ): InboundServer = when (transport) {
        is TransportConfig.StdioTransport -> StdioInboundServer(proxy, logger)
        is TransportConfig.HttpTransport -> KtorInboundServer(
            url = transport.url,
            proxy = proxy,
            logger = logger
        )
        else -> error("Unsupported inbound transport: ${transport::class.simpleName}")
    }
}

private class StdioInboundServer(
    private val proxy: ProxyMcpServer,
    private val logger: Logger
) : InboundServer {
    override fun start(): ServerStatus {
        logger.info("Starting STDIO inbound server (MCP SDK)")
        val input = System.`in`.asSource().buffered()
        val output = System.out.asSink().buffered()
        val transport = io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport(input, output)
        val server = buildSdkServer(proxy, logger)
        return try {
            kotlinx.coroutines.runBlocking { server.createSession(transport) }
            ServerStatus.Running
        } catch (t: Throwable) {
            logger.error("Failed to start STDIO MCP server", t)
            ServerStatus.Error(t.message)
        }
    }

    override fun stop(): ServerStatus {
        logger.info("Stopping STDIO inbound server")
        return ServerStatus.Stopped
    }
}

private class KtorInboundServer(
    private val url: String,
    private val proxy: ProxyMcpServer,
    private val logger: Logger
) : InboundServer {

    private var engine: EmbeddedServer<*, *>? = null

    override fun start(): ServerStatus {
        val (host, port, rawPath) = parse(url)
        val scheme = runCatching { URI(url).scheme }.getOrNull()?.takeIf { it.isNotBlank() } ?: "http"
        val normalizedPath = normalizePath(rawPath)
        val displayPath = if (normalizedPath.display.isBlank()) "/" else normalizedPath.display
        logger.info("Starting HTTP SSE inbound at $scheme://$host:$port$displayPath")
        logger.debug("HTTP inbound route segments='${normalizedPath.routeSegments.ifBlank { "/" }}'")
        return try {
            engine = embeddedServer(Netty, host = host, port = port, module = {
                install(CallLogging)
                install(SSE)
                routing {
                    val serverFactory: ServerSSESession.() -> Server = { buildSdkServer(proxy, logger) }
                    val registry = InboundSseRegistry(logger)
                    if (normalizedPath.routeSegments.isBlank()) {
                        mountMcpRoute(endpointPath = normalizedPath.display, serverFactory = serverFactory, registry = registry)
                    } else {
                        route("/${normalizedPath.routeSegments}") {
                            mountMcpRoute(
                                endpointPath = normalizedPath.display,
                                serverFactory = serverFactory,
                                registry = registry
                            )
                        }
                    }
                }
            }).start(wait = false)
            ServerStatus.Running
        } catch (t: Throwable) {
            engine = null
            val bind = t.findCause<BindException>()
            val message = if (bind != null) {
                "Port $port is already in use"
            } else {
                t.message ?: "Failed to start HTTP SSE inbound server"
            }
            logger.error(message, t)
            ServerStatus.Error(message)
        }
    }

    override fun stop(): ServerStatus {
        val srv = engine
        engine = null
        srv?.stop()
        return ServerStatus.Stopped
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
    val routeSegments: String
)

private class InboundSseRegistry(
    private val logger: Logger
) {
    private val sessions = ConcurrentHashMap<String, ServerSession>()

    fun add(transport: SseServerTransport, session: ServerSession) {
        sessions[transport.sessionId] = session
        logger.debug("Registered SSE session ${transport.sessionId}")
    }

    fun remove(sessionId: String?) {
        if (sessionId.isNullOrBlank()) return
        sessions.remove(sessionId)
        logger.debug("Removed SSE session $sessionId")
    }

    fun transport(sessionId: String?): SseServerTransport? {
        if (sessionId.isNullOrBlank()) return null
        return (sessions[sessionId]?.transport as? SseServerTransport)
    }
}

private fun Route.mountMcpRoute(
    endpointPath: String,
    serverFactory: ServerSSESession.() -> Server,
    registry: InboundSseRegistry
) {
    sse {
        val server = serverFactory(this)
        val transport = SseServerTransport(endpointPath, this)
        val session = server.createSession(transport)
        registry.add(transport, session)

        val done = Job()
        session.onClose { done.complete() }

        // Keep Ktor SSE handler alive until the MCP session closes.
        try {
            done.join()
        } finally {
            registry.remove(transport.sessionId)
        }
    }
    post {
        val sessionId = call.request.queryParameters[SESSION_ID_PARAM]
        if (sessionId.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, "sessionId query parameter is not provided")
            return@post
        }
        val transport = registry.transport(sessionId)
        if (transport == null) {
            call.respond(HttpStatusCode.NotFound, "Session not found")
            return@post
        }
        transport.handlePostMessage(call)
    }
}

private const val SESSION_ID_PARAM = "sessionId"
