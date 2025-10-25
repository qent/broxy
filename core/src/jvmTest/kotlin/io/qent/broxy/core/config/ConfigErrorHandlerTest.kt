package io.qent.broxy.core.config

import io.qent.broxy.core.utils.ConfigurationException
import io.qent.broxy.core.utils.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfigErrorHandlerTest {
    @Test
    fun fail_logs_and_throws_configuration_exception() {
        val logger = RecordingLogger()
        val handler = ConfigErrorHandler(logger)

        val ex =
            assertFailsWith<ConfigurationException> {
                handler.fail("bad config", IllegalStateException("root cause"))
            }

        assertEquals("bad config", ex.message)
        assertTrue(logger.messages.any { it.contains("bad config") })
    }

    private class RecordingLogger : Logger {
        val messages = mutableListOf<String>()

        override fun debug(message: String) {
            messages += message
        }

        override fun info(message: String) {
            messages += message
        }

        override fun warn(
            message: String,
            throwable: Throwable?,
        ) {
            messages += message
        }

        override fun error(
            message: String,
            throwable: Throwable?,
        ) {
            messages += message
        }
    }
}
