package io.qent.broxy.core.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class CompositeLoggerTest {
    @Test
    fun composite_logger_fans_out_messages() {
        val first = RecordingLogger()
        val second = RecordingLogger()
        val logger = CompositeLogger(first, second)

        logger.info("hello")
        logger.warn("warn", null)

        assertEquals(listOf("INFO:hello", "WARN:warn"), first.entries)
        assertEquals(listOf("INFO:hello", "WARN:warn"), second.entries)
    }

    private class RecordingLogger : Logger {
        val entries = mutableListOf<String>()

        override fun debug(message: String) {
            entries += "DEBUG:$message"
        }

        override fun info(message: String) {
            entries += "INFO:$message"
        }

        override fun warn(
            message: String,
            throwable: Throwable?,
        ) {
            entries += "WARN:$message"
        }

        override fun error(
            message: String,
            throwable: Throwable?,
        ) {
            entries += "ERROR:$message"
        }
    }
}
