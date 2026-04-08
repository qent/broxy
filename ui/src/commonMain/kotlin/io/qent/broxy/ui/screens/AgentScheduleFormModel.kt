@file:Suppress(
    "MagicNumber",
    "LongMethod",
    "CyclomaticComplexMethod",
    "TooManyFunctions",
    "ComplexCondition",
    "ReturnCount",
)

package io.qent.broxy.ui.screens

import io.qent.broxy.ui.strings.AppStrings
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

internal enum class SchedulePattern {
    EVERY_N_MINUTES,
    EVERY_N_HOURS,
    DAILY,
    WEEKDAYS,
    WEEKLY,
    MONTHLY,
}

internal enum class Weekday(
    val cronValue: Int,
    val label: String,
) {
    MONDAY(1, "Mon"),
    TUESDAY(2, "Tue"),
    WEDNESDAY(3, "Wed"),
    THURSDAY(4, "Thu"),
    FRIDAY(5, "Fri"),
    SATURDAY(6, "Sat"),
    SUNDAY(0, "Sun"),
    ;

    companion object {
        fun fromCron(value: Int): Weekday? =
            when (value) {
                1 -> MONDAY
                2 -> TUESDAY
                3 -> WEDNESDAY
                4 -> THURSDAY
                5 -> FRIDAY
                6 -> SATURDAY
                0, 7 -> SUNDAY
                else -> null
            }
    }
}

internal data class ScheduleFormState(
    val pattern: SchedulePattern = SchedulePattern.DAILY,
    val everyMinutes: Int = 5,
    val everyHours: Int = 1,
    val hour: Int = 9,
    val minute: Int = 0,
    val monthlyDay: Int = 1,
    val weeklyDays: Set<Weekday> = setOf(Weekday.MONDAY),
)

internal fun ScheduleFormState.toCron(): String =
    when (pattern) {
        SchedulePattern.EVERY_N_MINUTES -> "*/$everyMinutes * * * *"
        SchedulePattern.EVERY_N_HOURS -> "$minute */$everyHours * * *"
        SchedulePattern.DAILY -> "$minute $hour * * *"
        SchedulePattern.WEEKDAYS -> "$minute $hour * * 1-5"
        SchedulePattern.WEEKLY -> "$minute $hour * * ${weeklyDays.toCronWeekdayList()}"
        SchedulePattern.MONTHLY -> "$minute $hour $monthlyDay * *"
    }

internal fun ScheduleFormState.isValid(): Boolean =
    when (pattern) {
        SchedulePattern.EVERY_N_MINUTES -> everyMinutes in 1..59
        SchedulePattern.EVERY_N_HOURS -> everyHours in 1..23 && minute in 0..59
        SchedulePattern.DAILY, SchedulePattern.WEEKDAYS -> hour in 0..23 && minute in 0..59
        SchedulePattern.WEEKLY -> hour in 0..23 && minute in 0..59 && weeklyDays.isNotEmpty()
        SchedulePattern.MONTHLY -> monthlyDay in 1..31 && hour in 0..23 && minute in 0..59
    }

internal fun scheduleFormStateFromCronOrNull(cron: String): ScheduleFormState? {
    val parts = cron.trim().split(Regex("\\s+"))
    if (parts.size != 5) return null
    val minuteField = parts[0]
    val hourField = parts[1]
    val dayOfMonthField = parts[2]
    val monthField = parts[3]
    val dayOfWeekField = parts[4]

    if (hourField == "*" && dayOfMonthField == "*" && monthField == "*" && dayOfWeekField == "*") {
        parseStepValue(minuteField)?.let { everyMinutes ->
            if (everyMinutes in 1..59) {
                return ScheduleFormState(
                    pattern = SchedulePattern.EVERY_N_MINUTES,
                    everyMinutes = everyMinutes,
                )
            }
        }
    }

    if (dayOfMonthField == "*" && monthField == "*" && dayOfWeekField == "*") {
        val minute = minuteField.toIntOrNull()
        val hoursStep = parseStepValue(hourField)
        if (minute != null && hoursStep != null && minute in 0..59 && hoursStep in 1..23) {
            return ScheduleFormState(
                pattern = SchedulePattern.EVERY_N_HOURS,
                everyHours = hoursStep,
                minute = minute,
            )
        }
    }

    if (dayOfMonthField == "*" && monthField == "*" && dayOfWeekField == "1-5") {
        val minute = minuteField.toIntOrNull()
        val hour = hourField.toIntOrNull()
        if (minute != null && hour != null && minute in 0..59 && hour in 0..23) {
            return ScheduleFormState(
                pattern = SchedulePattern.WEEKDAYS,
                hour = hour,
                minute = minute,
            )
        }
    }

    if (dayOfMonthField == "*" && monthField == "*" && dayOfWeekField != "*") {
        val minute = minuteField.toIntOrNull()
        val hour = hourField.toIntOrNull()
        val weekdays = parseWeekdayList(dayOfWeekField)
        if (minute != null && hour != null && minute in 0..59 && hour in 0..23 && weekdays.isNotEmpty()) {
            return ScheduleFormState(
                pattern = SchedulePattern.WEEKLY,
                hour = hour,
                minute = minute,
                weeklyDays = weekdays,
            )
        }
    }

    if (dayOfMonthField == "*" && monthField == "*" && dayOfWeekField == "*") {
        val minute = minuteField.toIntOrNull()
        val hour = hourField.toIntOrNull()
        if (minute != null && hour != null && minute in 0..59 && hour in 0..23) {
            return ScheduleFormState(
                pattern = SchedulePattern.DAILY,
                hour = hour,
                minute = minute,
            )
        }
    }

    if (monthField == "*" && dayOfWeekField == "*") {
        val minute = minuteField.toIntOrNull()
        val hour = hourField.toIntOrNull()
        val monthlyDay = dayOfMonthField.toIntOrNull()
        if (
            minute != null &&
            hour != null &&
            monthlyDay != null &&
            minute in 0..59 &&
            hour in 0..23 &&
            monthlyDay in 1..31
        ) {
            return ScheduleFormState(
                pattern = SchedulePattern.MONTHLY,
                monthlyDay = monthlyDay,
                hour = hour,
                minute = minute,
            )
        }
    }

    return null
}

internal fun scheduleToHumanReadable(
    cron: String,
    strings: AppStrings,
): String {
    val formState = scheduleFormStateFromCronOrNull(cron) ?: return strings.customSchedule
    return formState.toHumanReadable(strings)
}

internal fun ScheduleFormState.toHumanReadable(strings: AppStrings): String {
    val time = formatTime(hour, minute)
    return when (pattern) {
        SchedulePattern.EVERY_N_MINUTES -> strings.scheduleSummaryEveryMinutes(everyMinutes)
        SchedulePattern.EVERY_N_HOURS -> strings.scheduleSummaryEveryHours(everyHours, formatMinute(minute))
        SchedulePattern.DAILY -> strings.scheduleSummaryDaily(time)
        SchedulePattern.WEEKDAYS -> strings.scheduleSummaryWeekdays(time)
        SchedulePattern.WEEKLY -> strings.scheduleSummaryWeekly(weeklyDays.toHumanReadableDays(), time)
        SchedulePattern.MONTHLY -> strings.scheduleSummaryMonthly(monthlyDay, time)
    }
}

private fun parseStepValue(field: String): Int? {
    if (!field.startsWith("*/")) return null
    return field.removePrefix("*/").toIntOrNull()
}

private fun parseWeekdayList(field: String): Set<Weekday> {
    if (field.contains('-') || field.contains('/')) return emptySet()
    return field
        .split(',')
        .mapNotNull { token ->
            token.trim().toIntOrNull()?.let(Weekday::fromCron)
        }.toSet()
}

private fun Set<Weekday>.toCronWeekdayList(): String =
    orderedWeekdays()
        .joinToString(",") { day ->
            day.cronValue.toString()
        }

private fun Set<Weekday>.toHumanReadableDays(): String =
    orderedWeekdays()
        .joinToString(", ") { it.label }

private fun Set<Weekday>.orderedWeekdays(): List<Weekday> {
    val order =
        listOf(
            Weekday.MONDAY,
            Weekday.TUESDAY,
            Weekday.WEDNESDAY,
            Weekday.THURSDAY,
            Weekday.FRIDAY,
            Weekday.SATURDAY,
            Weekday.SUNDAY,
        )
    return order.filter { contains(it) }
}

internal fun formatTime(
    hour: Int,
    minute: Int,
): String = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"

private fun formatMinute(minute: Int): String = minute.toString().padStart(2, '0')

internal fun formatSchedulePreviewTimestamp(epochMillis: Long): String {
    val dateTime =
        Instant
            .fromEpochMilliseconds(epochMillis)
            .toLocalDateTime(TimeZone.currentSystemDefault())
    return buildString {
        append(dateTime.date)
        append(' ')
        append(dateTime.hour.toString().padStart(2, '0'))
        append(':')
        append(dateTime.minute.toString().padStart(2, '0'))
        append(':')
        append(dateTime.second.toString().padStart(2, '0'))
    }
}
