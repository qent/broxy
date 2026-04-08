package io.qent.broxy.core.utils

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class CollectingLoggerTest {
    private object NoopLogger : Logger {
        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(
            message: String,
            throwable: Throwable?,
        ) = Unit

        override fun error(
            message: String,
            throwable: Throwable?,
        ) = Unit
    }

    @Test
    fun `replays latest events to late subscribers`() =
        runTest {
            val logger = CollectingLogger(delegate = NoopLogger, bufferCapacity = 2)

            logger.info("one")
            logger.info("two")
            logger.info("three")

            val events = logger.events.take(2).toList()

            assertEquals(listOf("two", "three"), events.map { it.message })
        }

    @Test
    fun `emits events in order to active collectors`() =
        runTest {
            val logger = CollectingLogger(delegate = NoopLogger, bufferCapacity = 3)
            val events = mutableListOf<LogEvent>()

            val job =
                launch(UnconfinedTestDispatcher(testScheduler)) {
                    logger.events.take(3).toList(events)
                }

            logger.info("first")
            logger.warn("second", null)
            logger.error("third", null)

            job.join()

            assertEquals(listOf("first", "second", "third"), events.map { it.message })
        }
}
