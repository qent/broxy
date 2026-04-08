package io.qent.broxy.ui.adapter.agents

import io.qent.broxy.agents.application.scheduler.CronScheduleValidator
import io.qent.broxy.ui.adapter.models.UiSchedulePreview
import java.time.ZoneId
import java.time.ZonedDateTime

internal fun previewScheduleFromCron(
    cron: String,
    timezoneId: String,
    limit: Int,
): Result<UiSchedulePreview> =
    runCatching {
        require(limit > 0) { "Preview limit must be positive" }
        val zone = ZoneId.of(timezoneId.trim())
        var cursor = ZonedDateTime.now(zone)
        val nextRuns = mutableListOf<Long>()

        while (nextRuns.size < limit) {
            val next =
                CronScheduleValidator
                    .nextExecution(cron, timezoneId, cursor)
                    .getOrThrow()
                    ?: break
            nextRuns += next.toInstant().toEpochMilli()
            cursor = next.plusSeconds(1)
        }

        UiSchedulePreview(nextRunsEpochMillis = nextRuns)
    }
