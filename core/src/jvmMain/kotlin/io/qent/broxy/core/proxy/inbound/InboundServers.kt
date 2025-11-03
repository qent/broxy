package io.qent.broxy.core.proxy.inbound

import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import io.qent.broxy.core.mcp.ServerStatus
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.proxy.ProxyMcpServer
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger
import java.net.URI

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
            kotlinx.coroutines.runBlocking { server.connect(transport) }
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
        val (host, port, path) = parse(url)
        val scheme = runCatching { URI(url).scheme }.getOrNull()?.takeIf { it.isNotBlank() } ?: "http"
        logger.info("Starting HTTP SSE inbound at $scheme://$host:$port$path")
        engine = embeddedServer(Netty, host = host, port = port, module = {
            install(CallLogging)
            install(SSE)
            routing {
                mcp(path) { buildSdkServer(proxy, logger) }
            }
        }).start(wait = false)
        return ServerStatus.Running
    }

    override fun stop(): ServerStatus {
        engine?.stop()
        return ServerStatus.Stopped
    }
}

private fun parse(url: String): Triple<String, Int, String> {
    val u = URI(url)
    val host = if (u.host.isNullOrBlank()) "0.0.0.0" else u.host
    val port = if (u.port == -1) (if (u.scheme == "https" || u.scheme == "wss") 443 else 80) else u.port
    val path = if (u.path.isNullOrBlank()) "/mcp" else u.path
    return Triple(host, port, if (path.endsWith('/')) path.dropLast(1) else path)
}
