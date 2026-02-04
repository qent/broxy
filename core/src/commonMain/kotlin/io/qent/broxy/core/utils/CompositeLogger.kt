package io.qent.broxy.core.utils

class CompositeLogger(
    private val delegates: List<Logger>,
) : Logger {
    constructor(vararg delegates: Logger) : this(delegates.toList())

    override fun debug(message: String) {
        delegates.forEach { it.debug(message) }
    }

    override fun info(message: String) {
        delegates.forEach { it.info(message) }
    }

    override fun warn(
        message: String,
        throwable: Throwable?,
    ) {
        delegates.forEach { it.warn(message, throwable) }
    }

    override fun error(
        message: String,
        throwable: Throwable?,
    ) {
        delegates.forEach { it.error(message, throwable) }
    }
}
