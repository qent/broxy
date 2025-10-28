package io.qent.bro.core.utils

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.datetime.Clock

data class LogEvent(
    val timestampMillis: Long = Clock.System.now().toEpochMilliseconds(),
    val level: LogLevel,
    val message: String,
    val throwableMessage: String? = null
)

class CollectingLogger(
    private val delegate: Logger = ConsoleLogger,
    bufferCapacity: Int = 200
) : Logger {
    private val _events = MutableSharedFlow<LogEvent>(
        replay = bufferCapacity,
        extraBufferCapacity = bufferCapacity
    )
    val events: SharedFlow<LogEvent> = _events

    override fun debug(message: String) {
        delegate.debug(message)
        append(LogLevel.DEBUG, message, null)
    }

    override fun info(message: String) {
        delegate.info(message)
        append(LogLevel.INFO, message, null)
    }

    override fun warn(message: String, throwable: Throwable?) {
        delegate.warn(message, throwable)
        append(LogLevel.WARN, message, throwable)
    }

    override fun error(message: String, throwable: Throwable?) {
        delegate.error(message, throwable)
        append(LogLevel.ERROR, message, throwable)
    }

    private fun append(level: LogLevel, message: String, throwable: Throwable?) {
        val event = LogEvent(
            timestampMillis = Clock.System.now().toEpochMilliseconds(),
            level = level,
            message = message,
            throwableMessage = throwable?.message
        )
        _events.tryEmit(event)
    }
}
