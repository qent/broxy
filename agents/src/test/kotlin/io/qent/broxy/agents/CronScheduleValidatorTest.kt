package io.qent.broxy.agents

import io.qent.broxy.agents.application.scheduler.CronScheduleValidator
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CronScheduleValidatorTest {
    @Test
    fun validate_acceptsUnixCronAndTimezone() {
        val result = CronScheduleValidator.validate("*/5 * * * *", "UTC")
        assertTrue(result.isSuccess)
    }

    @Test
    fun validate_rejectsInvalidCron() {
        val result = CronScheduleValidator.validate("invalid cron", "UTC")
        assertTrue(result.isFailure)
    }

    @Test
    fun nextExecution_returnsExpectedNextMinute() {
        val from = ZonedDateTime.of(2026, 3, 3, 10, 7, 0, 0, ZoneId.of("UTC"))
        val next =
            CronScheduleValidator
                .nextExecution("*/15 * * * *", "UTC", from)
                .getOrThrow()

        assertNotNull(next)
        assertEquals(15, next.minute)
        assertEquals(10, next.hour)
    }
}
