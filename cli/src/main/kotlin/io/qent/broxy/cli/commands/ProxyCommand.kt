package io.qent.broxy.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.file
import java.io.File
import java.net.URL
import java.nio.file.Paths

open class ProxyCommand : CliktCommand {
    private var runner: ProxyCommandRunner

    constructor() : super(name = "proxy", help = "Run Broxy server") {
        runner = ProxyCommandRunner()
    }

    internal constructor(runner: ProxyCommandRunner) : super(name = "proxy", help = "Run Broxy server") {
        this.runner = runner
    }

    private val defaultConfigDir =
        Paths.get(System.getProperty("user.home"), ".config", "broxy").toFile()

    private val configDir: File by option(
        "--config-dir",
        help =
            "Directory containing config.json, preset_*.json, and defaults for mcp.json path. " +
                "Defaults to ~/.config/broxy.",
    ).file(mustExist = false, canBeFile = false, canBeDir = true)
        .default(defaultConfigDir)

    private val presetId: String by option(
        "--preset-id",
        help = "Preset ID, e.g. 'developer' (loads preset_developer.json)",
    ).required()

    private val inbound: InboundMode by option(
        "--inbound",
        help = "Inbound transport: stdio|http (aliases: local|remote|sse)",
    ).choice(inboundChoices).default(InboundMode.STDIO)

    private val url: String? by option(
        "--url",
        help = "Listen URL for HTTP Streamable inbound (e.g. http://localhost:3335/mcp)",
    ).check("must be a valid URL, e.g. http://localhost:3335/mcp") { value ->
        inbound != InboundMode.HTTP || isValidUrl(value)
    }

    private val logLevel: LogLevelOption by
        option("--log-level", help = "Log level: debug|info|warn|error")
            .enum<LogLevelOption>()
            .default(LogLevelOption.INFO)

    override fun run() {
        val options =
            CliOptions(
                configDir = configDir,
                presetId = presetId,
                inbound = inbound,
                url = url,
                logLevel = logLevel,
            )
        runner.run(options)
    }

    private companion object {
        private val inboundChoices =
            linkedMapOf(
                "stdio" to InboundMode.STDIO,
                "local" to InboundMode.STDIO,
                "http" to InboundMode.HTTP,
                "remote" to InboundMode.HTTP,
                "sse" to InboundMode.HTTP,
            )

        private fun isValidUrl(value: String): Boolean = runCatching { URL(value).toURI() }.isSuccess
    }
}
