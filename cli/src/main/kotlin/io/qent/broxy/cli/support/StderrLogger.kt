package io.qent.broxy.cli.support

import io.qent.broxy.core.utils.Logger

/**
 * Simple logger that writes to STDERR to avoid corrupting STDIO MCP streams.
 */
object StderrLogger : Logger {
    override fun debug(message: String) = log("DEBUG", message)

    override fun info(message: String) = log("INFO", message)

    override fun warn(
        message: String,
        throwable: Throwable?,
    ) = log("WARN", message, throwable)

    override fun error(
        message: String,
        throwable: Throwable?,
    ) = log("ERROR", message, throwable)

    private fun log(
        level: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        val suffix = throwable?.let { ": ${it.message}" } ?: ""
        System.err.println("[$level] $message$suffix")
    }
}
