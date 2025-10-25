package io.qent.broxy.core.utils

import java.nio.file.Files
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DailyFileLoggerTest {
    @Test
    fun writes_sanitized_lines_to_daily_file() {
        val tempDir = Files.createTempDirectory("broxy-logs")
        val logger = DailyFileLogger(tempDir)

        logger.info("hello\nworld")
        logger.warn("warned", IllegalStateException("boom"))

        val date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val logFile = tempDir.resolve("logs").resolve("$date.log")
        val content = Files.readString(logFile)

        assertTrue(content.contains("INFO hello\\nworld"))
        assertTrue(content.contains("WARN warned (boom)"))
    }

    @Test
    fun writes_from_multiple_threads_without_losing_lines() {
        val tempDir = Files.createTempDirectory("broxy-logs-mt")
        val logger = DailyFileLogger(tempDir)
        val count = 8
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(count)

        repeat(count) { index ->
            thread {
                startLatch.await()
                logger.info("line-$index")
                doneLatch.countDown()
            }
        }

        startLatch.countDown()
        doneLatch.await()

        val date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val logFile = tempDir.resolve("logs").resolve("$date.log")
        val lines = Files.readAllLines(logFile).filter { it.isNotBlank() }

        assertEquals(count, lines.size)
        repeat(count) { index ->
            assertTrue(lines.any { it.contains("INFO line-$index") })
        }
    }
}
