package io.qent.broxy.ui.screens

import io.qent.broxy.ui.adapter.models.UiAgentRunStatus
import io.qent.broxy.ui.adapter.models.UiAgentRunTrigger
import io.qent.broxy.ui.adapter.models.UiAgentRuntime
import io.qent.broxy.ui.adapter.models.UiRunSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunsSortingTest {
    @Test
    fun `sortRunsByStartedAtDesc sorts by startedAt desc and uses finishedAt tie-break`() {
        val earliest =
            runSummary(
                runId = "run-1",
                prompt = "earliest",
                startedAtEpochMillis = 1_000L,
                finishedAtEpochMillis = 1_100L,
            )
        val latestLowerFinish =
            runSummary(
                runId = "run-2",
                prompt = "latest-1",
                startedAtEpochMillis = 3_000L,
                finishedAtEpochMillis = 3_100L,
            )
        val latestHigherFinish =
            runSummary(
                runId = "run-3",
                prompt = "latest-2",
                startedAtEpochMillis = 3_000L,
                finishedAtEpochMillis = 3_200L,
            )
        val middle =
            runSummary(
                runId = "run-4",
                prompt = "middle",
                startedAtEpochMillis = 2_000L,
                finishedAtEpochMillis = 2_100L,
            )

        val sorted = sortRunsByStartedAtDesc(listOf(earliest, latestLowerFinish, middle, latestHigherFinish))

        assertEquals(
            listOf(latestHigherFinish, latestLowerFinish, middle, earliest),
            sorted,
        )
    }

    @Test
    fun `sortRunsByStartedAtDesc returns empty list for empty input`() {
        val sorted = sortRunsByStartedAtDesc(emptyList())

        assertTrue(sorted.isEmpty())
    }
}

private fun runSummary(
    runId: String,
    prompt: String,
    startedAtEpochMillis: Long,
    finishedAtEpochMillis: Long,
): UiRunSummary =
    UiRunSummary(
        runId = runId,
        agentId = "agent-1",
        agentName = "Agent",
        trigger = UiAgentRunTrigger.MANUAL,
        status = UiAgentRunStatus.SUCCESS,
        runtime = UiAgentRuntime.LANGCHAIN,
        prompt = prompt,
        startedAtEpochMillis = startedAtEpochMillis,
        finishedAtEpochMillis = finishedAtEpochMillis,
    )
