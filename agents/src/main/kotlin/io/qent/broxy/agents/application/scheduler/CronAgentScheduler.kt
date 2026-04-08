package io.qent.broxy.agents.application.scheduler

import io.qent.broxy.agents.AgentSchedule
import io.qent.broxy.agents.AgentScheduler
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.ZonedDateTime

private const val MIN_SCHEDULE_DELAY_MILLIS = 500L

class CronAgentScheduler(
    private val logger: Logger = ConsoleLogger,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AgentScheduler {
    private val jobs = linkedMapOf<String, Job>()
    private var trigger: (suspend (agentId: String, schedule: AgentSchedule) -> Unit)? = null

    override fun start(
        schedules: Map<String, AgentSchedule>,
        onTrigger: suspend (agentId: String, schedule: AgentSchedule) -> Unit,
    ) {
        stop()
        trigger = onTrigger
        schedules.forEach { (agentId, schedule) ->
            launchSchedule(agentId, schedule)
        }
    }

    override fun updateSchedule(
        agentId: String,
        schedule: AgentSchedule?,
    ) {
        jobs.remove(agentId)?.cancel()
        if (schedule != null) {
            launchSchedule(agentId, schedule)
        }
    }

    override fun stop() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    private fun launchSchedule(
        agentId: String,
        schedule: AgentSchedule,
    ) {
        val plan =
            CronScheduleValidator
                .prepare(schedule.cron, schedule.timezoneId)
                .onFailure {
                    logger.warn("Invalid cron for agent '$agentId': ${schedule.cron} (${it.message})")
                }.getOrNull()
                ?: return

        val job =
            scope.launch {
                while (isActive) {
                    val now = ZonedDateTime.now(plan.zoneId)
                    val next = plan.executionTime.nextExecution(now).orElse(null)
                    if (next == null) {
                        logger.warn("No next execution resolved for agent '$agentId' schedule '${schedule.cron}'")
                        break
                    }
                    val delayMillis = Duration.between(now, next).toMillis().coerceAtLeast(MIN_SCHEDULE_DELAY_MILLIS)
                    delay(delayMillis)
                    trigger?.invoke(agentId, schedule)
                }
            }
        jobs[agentId] = job
    }

    fun close() {
        stop()
        scope.cancel()
    }
}
