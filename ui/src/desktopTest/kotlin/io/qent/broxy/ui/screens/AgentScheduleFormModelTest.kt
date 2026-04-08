package io.qent.broxy.ui.screens

import io.qent.broxy.ui.strings.EnglishStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AgentScheduleFormModelTest {
    @Test
    fun toCron_everyPatterns() {
        val minutes = ScheduleFormState(pattern = SchedulePattern.EVERY_N_MINUTES, everyMinutes = 7)
        val hours = ScheduleFormState(pattern = SchedulePattern.EVERY_N_HOURS, everyHours = 3, minute = 12)

        assertEquals("*/7 * * * *", minutes.toCron())
        assertEquals("12 */3 * * *", hours.toCron())
    }

    @Test
    fun toCron_calendarPatterns() {
        val daily = ScheduleFormState(pattern = SchedulePattern.DAILY, hour = 9, minute = 30)
        val weekdays = ScheduleFormState(pattern = SchedulePattern.WEEKDAYS, hour = 8, minute = 0)
        val weekly =
            ScheduleFormState(
                pattern = SchedulePattern.WEEKLY,
                hour = 10,
                minute = 15,
                weeklyDays = setOf(Weekday.MONDAY, Weekday.WEDNESDAY, Weekday.SUNDAY),
            )
        val monthly = ScheduleFormState(pattern = SchedulePattern.MONTHLY, monthlyDay = 28, hour = 6, minute = 5)

        assertEquals("30 9 * * *", daily.toCron())
        assertEquals("0 8 * * 1-5", weekdays.toCron())
        assertEquals("15 10 * * 1,3,0", weekly.toCron())
        assertEquals("5 6 28 * *", monthly.toCron())
    }

    @Test
    fun fromCronOrNull_supportsPlannedPatterns() {
        assertEquals(
            SchedulePattern.EVERY_N_MINUTES,
            requireNotNull(scheduleFormStateFromCronOrNull("*/5 * * * *")).pattern,
        )
        assertEquals(
            SchedulePattern.EVERY_N_HOURS,
            requireNotNull(scheduleFormStateFromCronOrNull("10 */2 * * *")).pattern,
        )
        assertEquals(
            SchedulePattern.DAILY,
            requireNotNull(scheduleFormStateFromCronOrNull("30 9 * * *")).pattern,
        )
        assertEquals(
            SchedulePattern.WEEKDAYS,
            requireNotNull(scheduleFormStateFromCronOrNull("0 8 * * 1-5")).pattern,
        )
        assertEquals(
            SchedulePattern.WEEKLY,
            requireNotNull(scheduleFormStateFromCronOrNull("15 10 * * 1,3,0")).pattern,
        )
        assertEquals(
            SchedulePattern.MONTHLY,
            requireNotNull(scheduleFormStateFromCronOrNull("5 6 28 * *")).pattern,
        )
    }

    @Test
    fun fromCronOrNull_returnsNullForUnsupportedCron() {
        assertNull(scheduleFormStateFromCronOrNull("0 0 L * *"))
    }

    @Test
    fun scheduleToHumanReadable_returnsCustomForUnsupported() {
        assertEquals(EnglishStrings.customSchedule, scheduleToHumanReadable("0 0 L * *", EnglishStrings))
    }

    @Test
    fun scheduleToHumanReadable_returnsFormattedForSupported() {
        val result = scheduleToHumanReadable("0 8 * * 1-5", EnglishStrings)
        assertEquals("Weekdays at 08:00", result)
    }

    @Test
    fun weeklyCronParsing_mapsSundayAliases() {
        val fromZero = scheduleFormStateFromCronOrNull("0 9 * * 0")
        val fromSeven = scheduleFormStateFromCronOrNull("0 9 * * 7")

        assertNotNull(fromZero)
        assertNotNull(fromSeven)
        assertEquals(setOf(Weekday.SUNDAY), fromZero.weeklyDays)
        assertEquals(setOf(Weekday.SUNDAY), fromSeven.weeklyDays)
    }
}
