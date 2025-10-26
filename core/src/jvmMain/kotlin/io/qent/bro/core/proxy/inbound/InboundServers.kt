package io.qent.bro.core.proxy.inbound

import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import io.modelcontextprotocol.kotlin.sdk.server.mcpWebSocket
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import io.qent.bro.core.mcp.ServerStatus
import io.qent.bro.core.models.TransportConfig
import io.qent.bro.core.proxy.ProxyMcpServer
import io.qent.bro.core.utils.ConsoleLogger
import io.qent.bro.core.utils.Logger
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import io.qent.bro.core.mcp.ServerCapabilities
import io.qent.bro.core.mcp.ToolDescriptor
import java.net.URI

/**
 * Abstraction for platform-specific inbound transport servers that expose
 * the proxy over STDIO / HTTP(SSE) / WebSocket. These classes adapt the
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
        is TransportConfig.HttpTransport -> HttpSseInboundServer(transport, proxy, logger)
        is TransportConfig.WebSocketTransport -> WebSocketInboundServer(transport, proxy, logger)
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
        val server = buildSdkServer(proxy)
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

private class HttpSseInboundServer(
    private val config: TransportConfig.HttpTransport,
    private val proxy: ProxyMcpServer,
    private val logger: Logger
) : InboundServer {
    private var engine: EmbeddedServer<*, *>? = null

    override fun start(): ServerStatus {
        val (host, port, basePath) = parse(config.url)
        logger.info("Starting HTTP inbound at http://$host:$port$basePath")
        engine = embeddedServer(Netty, host = host, port = port, module = {
            install(CallLogging)
            routing {
                mcp(basePath) { buildSdkServer(proxy) }
            }
        }).start(wait = false)
        return ServerStatus.Running
    }

    override fun stop(): ServerStatus {
        engine?.stop()
        return ServerStatus.Stopped
    }
}

private class WebSocketInboundServer(
    private val config: TransportConfig.WebSocketTransport,
    private val proxy: ProxyMcpServer,
    private val logger: Logger
) : InboundServer {
    private var engine: EmbeddedServer<*, *>? = null

    override fun start(): ServerStatus {
        val (host, port, path) = parse(config.url)
        logger.info("Starting WebSocket inbound at ws://$host:$port$path")
        engine = embeddedServer(Netty, host = host, port = port, module = {
            install(CallLogging)
            install(WebSockets)
            routing {
                mcpWebSocket(path = path, block = { buildSdkServer(proxy) })
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
