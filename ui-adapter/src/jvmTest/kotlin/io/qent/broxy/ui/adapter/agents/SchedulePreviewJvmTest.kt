package io.qent.broxy.ui.adapter.agents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchedulePreviewJvmTest {
    @Test
    fun previewScheduleFromCron_returnsRequestedRuns() {
        val result = previewScheduleFromCron(cron = "*/15 * * * *", timezoneId = "UTC", limit = 3)
        assertTrue(result.isSuccess)
        assertEquals(3, result.getOrThrow().nextRunsEpochMillis.size)
    }

    @Test
    fun previewScheduleFromCron_rejectsInvalidCron() {
        val result = previewScheduleFromCron(cron = "invalid cron", timezoneId = "UTC", limit = 3)
        assertTrue(result.isFailure)
    }
}
