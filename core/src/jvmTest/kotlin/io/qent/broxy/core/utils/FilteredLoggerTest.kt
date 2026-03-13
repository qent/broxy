package io.qent.broxy.core.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class FilteredLoggerTest {
    @Test
    fun filters_below_minimum_level() {
        val recording = RecordingLogger()
        val logger = FilteredLogger(minLevel = LogLevel.WARN, delegate = recording)

        logger.debug("debug")
        logger.info("info")
        logger.warn("warn")
        logger.error("error")

        assertEquals(listOf("WARN:warn", "ERROR:error"), recording.entries)
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
