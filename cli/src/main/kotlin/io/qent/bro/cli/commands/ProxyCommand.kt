package io.qent.bro.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import io.qent.bro.core.mcp.DefaultMcpServerConnection
import io.qent.bro.core.mcp.McpServerConnection
import io.qent.bro.core.models.McpServersConfig
import io.qent.bro.core.models.Preset
import io.qent.bro.core.models.TransportConfig
import io.qent.bro.core.proxy.ProxyMcpServer
import io.qent.bro.core.proxy.inbound.InboundServerFactory
import io.qent.bro.core.utils.FilteredLogger
import io.qent.bro.core.utils.LogLevel
import io.qent.bro.core.utils.Logger
import kotlinx.serialization.json.Json
import java.io.File

class ProxyCommand : CliktCommand(name = "proxy", help = "Run MCP proxy server") {
    private val serversFile: File by option("--servers-file", help = "Path to MCP servers config JSON")
        .file(mustExist = true, canBeFile = true, canBeDir = false)
        .required()

    private val presetFile: File by option("--preset-file", help = "Path to preset JSON")
        .file(mustExist = true, canBeFile = true, canBeDir = false)
        .required()

    private val inbound: String by option("--inbound", help = "Inbound transport: stdio|http|ws").default("stdio")

    private val url: String? by option("--url", help = "Listen URL for http/ws inbound (e.g. http://0.0.0.0:3335/mcp or ws://0.0.0.0:3336/ws)")

    private val logLevel: String by option("--log-level", help = "Log level: debug|info|warn|error").default("info")

    override fun run() {
        val json = Json { ignoreUnknownKeys = true }
        val serversCfg = json.decodeFromString(McpServersConfig.serializer(), serversFile.readText())
        val preset = json.decodeFromString(Preset.serializer(), presetFile.readText())

        val logger = createLogger(logLevel)

        val downstreams: List<McpServerConnection> = serversCfg.servers
            .filter { it.enabled }
            .map { DefaultMcpServerConnection(config = it, logger = logger) }

        val proxy = ProxyMcpServer(downstreams, logger = logger)

        // Initialize proxy (refresh caps)
        proxy.start(preset, when (inbound) {
            "stdio" -> TransportConfig.StdioTransport(command = "", args = emptyList())
            "http" -> TransportConfig.HttpTransport(url = url ?: "http://0.0.0.0:3335/mcp")
            "ws", "websocket" -> TransportConfig.WebSocketTransport(url = url ?: "ws://0.0.0.0:3336/ws")
            else -> error("Unsupported inbound: $inbound")
        })

        // Start inbound server
        val inboundTransport = when (inbound) {
            "stdio" -> TransportConfig.StdioTransport(command = "", args = emptyList())
            "http" -> TransportConfig.HttpTransport(url = url ?: "http://0.0.0.0:3335/mcp")
            "ws", "websocket" -> TransportConfig.WebSocketTransport(url = url ?: "ws://0.0.0.0:3336/ws")
            else -> error("Unsupported inbound: $inbound")
        }
        val inboundServer = InboundServerFactory.create(inboundTransport, proxy, logger)
        inboundServer.start()

        // Graceful shutdown
        Runtime.getRuntime().addShutdownHook(Thread {
            try {
                echo("Shutting down proxy...")
                inboundServer.stop()
                downstreams.forEach { runCatching { kotlinx.coroutines.runBlocking { it.disconnect() } } }
            } catch (_: Throwable) {}
        })

        // Block current thread for http/ws servers. For stdio, just sleep.
        if (inbound == "http" || inbound == "ws" || inbound == "websocket") {
            // Ktor engine is non-blocking start; keep alive.
            while (true) Thread.sleep(60_000)
        } else {
            // In stdio mode, keep process alive for parent (e.g., Claude Desktop)
            while (true) Thread.sleep(60_000)
        }
    }

    private fun createLogger(level: String): Logger {
        val min = when (level.lowercase()) {
            "debug" -> LogLevel.DEBUG
            "info" -> LogLevel.INFO
            "warn" -> LogLevel.WARN
            "error" -> LogLevel.ERROR
            else -> LogLevel.INFO
        }
        return FilteredLogger(min)
    }
}
