package io.qent.broxy.agents.application.scheduler

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import java.time.ZoneId
import java.time.ZonedDateTime

data class CronExecutionPlan(
    val executionTime: ExecutionTime,
    val zoneId: ZoneId,
)

object CronScheduleValidator {
    private val parser = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX))

    fun prepare(
        cron: String,
        timezoneId: String,
    ): Result<CronExecutionPlan> =
        runCatching {
            val parsedCron = parser.parse(cron.trim()).also { it.validate() }
            val zoneId = ZoneId.of(timezoneId.trim())
            CronExecutionPlan(
                executionTime = ExecutionTime.forCron(parsedCron),
                zoneId = zoneId,
            )
        }

    fun validate(
        cron: String,
        timezoneId: String,
    ): Result<Unit> = prepare(cron, timezoneId).map { Unit }

    fun nextExecution(
        cron: String,
        timezoneId: String,
        from: ZonedDateTime,
    ): Result<ZonedDateTime?> =
        prepare(cron, timezoneId).map { plan ->
            val normalizedFrom = from.withZoneSameInstant(plan.zoneId)
            plan.executionTime.nextExecution(normalizedFrom).orElse(null)
        }
}
