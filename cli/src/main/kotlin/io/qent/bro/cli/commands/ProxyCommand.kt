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
import io.qent.bro.core.config.JsonConfigurationRepository
import io.qent.bro.core.config.ConfigurationWatcher
import io.qent.bro.core.config.ConfigurationObserver
import io.qent.bro.core.config.EnvironmentVariableResolver
import io.qent.bro.core.utils.FilteredLogger
import io.qent.bro.core.utils.LogLevel
import io.qent.bro.core.utils.Logger
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Paths
import kotlinx.coroutines.runBlocking

class ProxyCommand : CliktCommand(name = "proxy", help = "Run MCP proxy server") {
    private val configDir: File by option("--config-dir", help = "Directory containing mcp.json and preset_*.json. Defaults to ~/.config/bro.")
        .file(mustExist = false, canBeFile = false, canBeDir = true)
        .default(File(java.nio.file.Paths.get(System.getProperty("user.home"), ".config", "bro").toString()))

    private val presetId: String by option("--preset-id", help = "Preset ID, e.g. 'developer' (loads preset_developer.json)").required()

    private val inbound: String by option("--inbound", help = "Inbound transport: stdio|http|ws").default("stdio")

    private val url: String? by option("--url", help = "Listen URL for http/ws inbound (e.g. http://0.0.0.0:3335/mcp or ws://0.0.0.0:3336/ws)")

    private val logLevel: String by option("--log-level", help = "Log level: debug|info|warn|error").default("info")

    override fun run() {
        val json = Json { ignoreUnknownKeys = true }

        val logger = createLogger(logLevel)

        val repo = JsonConfigurationRepository(
            baseDir = Paths.get(configDir.absolutePath),
            json = json,
            envResolver = EnvironmentVariableResolver(logger = logger),
            logger = logger
        )

        var serversCfg = repo.loadMcpConfig()
        var currentPreset = repo.loadPreset(presetId)

        fun buildDownstreams(cfg: McpServersConfig): List<McpServerConnection> = cfg.servers
            .filter { it.enabled }
            .map { DefaultMcpServerConnection(config = it, logger = logger) }

        var downstreams: List<McpServerConnection> = buildDownstreams(serversCfg)
        var proxy = ProxyMcpServer(downstreams, logger = logger)

        val inboundTransport = when (inbound) {
            "stdio" -> TransportConfig.StdioTransport(command = "", args = emptyList())
            "http" -> TransportConfig.HttpTransport(url = url ?: "http://0.0.0.0:3335/mcp")
            "ws", "websocket" -> TransportConfig.WebSocketTransport(url = url ?: "ws://0.0.0.0:3336/ws")
            else -> error("Unsupported inbound: $inbound")
        }

        proxy.start(currentPreset, inboundTransport)
        var inboundServer = InboundServerFactory.create(inboundTransport, proxy, logger)
        inboundServer.start()

        val watcher = ConfigurationWatcher(
            baseDir = Paths.get(configDir.absolutePath),
            repo = repo,
            logger = logger
        )
        watcher.addObserver(object : ConfigurationObserver {
            override fun onConfigurationChanged(config: McpServersConfig) {
                logger.info("Configuration changed; restarting downstream connections")
                val oldDownstreams = downstreams
                val oldInbound = inboundServer

                val newDownstreams = buildDownstreams(config)
                val newProxy = ProxyMcpServer(newDownstreams, logger = logger)
                newProxy.start(currentPreset, inboundTransport)
                val newInbound = InboundServerFactory.create(inboundTransport, newProxy, logger)
                newInbound.start()

                downstreams = newDownstreams
                proxy = newProxy
                inboundServer = newInbound
                serversCfg = config

                runCatching { oldInbound.stop() }
                runBlocking { oldDownstreams.forEach { runCatching { it.disconnect() } } }
            }

            override fun onPresetChanged(preset: Preset) {
                logger.info("Preset changed to '${preset.id}'; applying to proxy")
                currentPreset = preset
                proxy.applyPreset(preset)
            }
        })
        watcher.start()

        // Graceful shutdown
        Runtime.getRuntime().addShutdownHook(Thread {
            try {
                echo("Shutting down proxy...")
                inboundServer.stop()
                downstreams.forEach { runCatching { kotlinx.coroutines.runBlocking { it.disconnect() } } }
            } catch (_: Throwable) {}
        })

        // Keep process alive for all inbounds
        while (true) Thread.sleep(60_000)
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
