package io.qent.broxy.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import io.qent.broxy.cli.support.StderrLogger
import io.qent.broxy.core.config.ConfigurationObserver
import io.qent.broxy.core.config.ConfigurationWatcher
import io.qent.broxy.core.config.EnvironmentVariableResolver
import io.qent.broxy.core.config.JsonConfigurationRepository
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.proxy.runtime.ProxyLifecycle
import io.qent.broxy.core.proxy.runtime.createProxyController
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.core.utils.FilteredLogger
import io.qent.broxy.core.utils.LogLevel
import io.qent.broxy.core.utils.Logger
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Paths

class ProxyCommand : CliktCommand(name = "proxy", help = "Run broxy server") {
    private val configDir: File by option("--config-dir", help = "Directory containing mcp.json and preset_*.json. Defaults to ~/.config/broxy.")
        .file(mustExist = false, canBeFile = false, canBeDir = true)
        .default(File(java.nio.file.Paths.get(System.getProperty("user.home"), ".config", "broxy").toString()))

    private val presetId: String by option("--preset-id", help = "Preset ID, e.g. 'developer' (loads preset_developer.json)").required()

    private val inbound: String by option("--inbound", help = "Inbound transport: stdio|http (aliases: local|remote|sse)").default("stdio")

    private val url: String? by option("--url", help = "Listen URL for HTTP Streamable inbound (e.g. http://localhost:3335/mcp)")

    private val logLevel: String by option("--log-level", help = "Log level: debug|info|warn|error").default("info")

    override fun run() {
        val json = Json { ignoreUnknownKeys = true }

        val logger = createLogger(logLevel)
        val collectingLogger = CollectingLogger(delegate = logger)
        val proxyController = createProxyController(collectingLogger)
        val proxyLifecycle = ProxyLifecycle(proxyController, logger)

        val repo = JsonConfigurationRepository(
            baseDir = Paths.get(configDir.absolutePath),
            json = json,
            logger = logger,
            envResolver = EnvironmentVariableResolver(logger = logger)
        )

        var serversCfg = repo.loadMcpConfig()
        var currentPreset = repo.loadPreset(presetId)

        val inboundKey = inbound.lowercase()
        val inboundTransport = when (inboundKey) {
            "stdio", "local" -> TransportConfig.StdioTransport(command = "", args = emptyList())
            "http", "remote", "sse" -> TransportConfig.StreamableHttpTransport(url = url ?: "http://localhost:3335/mcp")
            else -> error("Unsupported inbound: $inbound")
        }

        val startResult = proxyLifecycle.start(serversCfg, currentPreset, inboundTransport)
        if (startResult.isFailure) {
            val message = startResult.exceptionOrNull()?.message ?: "Failed to start proxy"
            throw IllegalStateException(message, startResult.exceptionOrNull())
        }

        val watcher = ConfigurationWatcher(
            baseDir = Paths.get(configDir.absolutePath),
            repo = repo,
            logger = logger,
            emitInitialState = false
        )
        watcher.addObserver(object : ConfigurationObserver {
            override fun onConfigurationChanged(config: McpServersConfig) {
                logger.info("Configuration changed; restarting downstream connections")
                val result = proxyLifecycle.restartWithConfig(config)
                if (result.isSuccess) {
                    serversCfg = config
                } else {
                    val msg = result.exceptionOrNull()?.message ?: "Failed to apply new config"
                    logger.error(msg, result.exceptionOrNull())
                }
            }

            override fun onPresetChanged(preset: Preset) {
                logger.info("Preset changed to '${preset.id}'; applying to proxy")
                val result = proxyLifecycle.applyPreset(preset)
                if (result.isSuccess) {
                    currentPreset = preset
                } else {
                    val msg = result.exceptionOrNull()?.message ?: "Failed to apply preset"
                    logger.error(msg, result.exceptionOrNull())
                }
            }
        })
        watcher.start()

        // Graceful shutdown
        Runtime.getRuntime().addShutdownHook(Thread {
            try {
                echo("Shutting down proxy...")
                proxyLifecycle.stop()
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
        return FilteredLogger(min, StderrLogger)
    }
}
