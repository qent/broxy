package io.qent.broxy.cli.commands

import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.utils.LogLevel
import java.io.File

internal const val DEFAULT_STREAMABLE_HTTP_URL = "http://localhost:3335/mcp"

internal enum class InboundMode {
    STDIO,
    HTTP,
}

internal enum class LogLevelOption {
    DEBUG,
    INFO,
    WARN,
    ERROR,
    ;

    fun toLogLevel(): LogLevel =
        when (this) {
            DEBUG -> LogLevel.DEBUG
            INFO -> LogLevel.INFO
            WARN -> LogLevel.WARN
            ERROR -> LogLevel.ERROR
        }
}

internal data class CliOptions(
    val configDir: File,
    val presetId: String,
    val inbound: InboundMode,
    val url: String?,
    val logLevel: LogLevelOption,
) {
    fun toInboundTransport(): TransportConfig =
        when (inbound) {
            InboundMode.STDIO -> TransportConfig.StdioTransport(command = "", args = emptyList())
            InboundMode.HTTP ->
                TransportConfig.StreamableHttpTransport(
                    url = url ?: DEFAULT_STREAMABLE_HTTP_URL,
                )
        }
}
