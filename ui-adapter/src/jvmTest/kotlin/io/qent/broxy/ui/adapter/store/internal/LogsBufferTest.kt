package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.ui.adapter.models.UiLogEntry
import io.qent.broxy.ui.adapter.models.UiLogLevel
import org.junit.Test
import kotlin.test.assertEquals

class LogsBufferTest {
    @Test
    fun keepsNewestEntriesWithinLimit() {
        val buffer = LogsBuffer(maxEntries = 2)
        buffer.append(entry(id = 1))
        buffer.append(entry(id = 2))
        buffer.append(entry(id = 3))

        val snapshot = buffer.snapshot()
        assertEquals(listOf(3L, 2L), snapshot.map { it.timestampMillis })
    }

    private fun entry(id: Long) = UiLogEntry(
        timestampMillis = id,
        level = UiLogLevel.INFO,
        message = "msg$id",
        throwableMessage = null
    )
}
