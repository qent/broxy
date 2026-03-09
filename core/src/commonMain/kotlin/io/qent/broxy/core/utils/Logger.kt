package io.qent.broxy.core.utils

interface Logger {
    fun debug(message: String)

    fun info(message: String)

    fun warn(
        message: String,
        throwable: Throwable? = null,
    )

    fun error(
        message: String,
        throwable: Throwable? = null,
    )
}

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

object ConsoleLogger : Logger {
    override fun debug(message: String) {
        println("[DEBUG] $message")
    }

    override fun info(message: String) {
        println("[INFO] $message")
    }

    override fun warn(
        message: String,
        throwable: Throwable?,
    ) {
        println("[WARN] $message" + (throwable?.let { ": ${it.message}" } ?: ""))
    }

    override fun error(
        message: String,
        throwable: Throwable?,
    ) {
        println("[ERROR] $message" + (throwable?.let { ": ${it.message}" } ?: ""))
    }
}

class FilteredLogger(
    private val minLevel: LogLevel = LogLevel.INFO,
    private val delegate: Logger = ConsoleLogger,
) : Logger {
    override fun debug(message: String) {
        if (minLevel <= LogLevel.DEBUG) delegate.debug(message)
    }

    override fun info(message: String) {
        if (minLevel <= LogLevel.INFO) delegate.info(message)
    }

    override fun warn(
        message: String,
        throwable: Throwable?,
    ) {
        if (minLevel <= LogLevel.WARN) delegate.warn(message, throwable)
    }

    override fun error(
        message: String,
        throwable: Throwable?,
    ) {
        if (minLevel <= LogLevel.ERROR) delegate.error(message, throwable)
    }
}
