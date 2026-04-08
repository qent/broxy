package io.qent.broxy.ui.adapter.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UiAgentStatusTest {
    @Test
    fun latestFailedRunsByAgent_returnsLatestFailedWhenAgentLatestRunFailed() {
        val runs =
            listOf(
                runSummary(
                    runId = "a-success",
                    agentId = "agent-a",
                    status = UiAgentRunStatus.SUCCESS,
                    startedAt = 100L,
                    finishedAt = 110L,
                ),
                runSummary(
                    runId = "a-failed",
                    agentId = "agent-a",
                    status = UiAgentRunStatus.FAILED,
                    startedAt = 200L,
                    finishedAt = 210L,
                    errorMessage = "missing api key",
                ),
                runSummary(
                    runId = "b-failed",
                    agentId = "agent-b",
                    status = UiAgentRunStatus.FAILED,
                    startedAt = 150L,
                    finishedAt = 160L,
                    errorMessage = "network",
                ),
            )

        val actual = latestFailedRunsByAgent(runs)

        assertEquals(setOf("agent-a", "agent-b"), actual.keys)
        assertEquals("a-failed", actual.getValue("agent-a").runId)
        assertEquals("b-failed", actual.getValue("agent-b").runId)
    }

    @Test
    fun latestFailedRunsByAgent_omitsAgentWhenLatestRunSucceeded() {
        val runs =
            listOf(
                runSummary(
                    runId = "failed-old",
                    agentId = "agent-1",
                    status = UiAgentRunStatus.FAILED,
                    startedAt = 100L,
                    finishedAt = 110L,
                    errorMessage = "network",
                ),
                runSummary(
                    runId = "success-new",
                    agentId = "agent-1",
                    status = UiAgentRunStatus.SUCCESS,
                    startedAt = 200L,
                    finishedAt = 210L,
                ),
            )

        val actual = latestFailedRunsByAgent(runs)

        assertTrue(actual.isEmpty())
    }

    @Test
    fun latestFailedRunsByAgent_returnsEmptyForEmptyInput() {
        val actual = latestFailedRunsByAgent(emptyList())

        assertTrue(actual.isEmpty())
    }
}

private fun runSummary(
    runId: String,
    agentId: String,
    status: UiAgentRunStatus,
    startedAt: Long,
    finishedAt: Long,
    errorMessage: String? = null,
): UiRunSummary =
    UiRunSummary(
        runId = runId,
        agentId = agentId,
        agentName = "Agent $agentId",
        trigger = UiAgentRunTrigger.MANUAL,
        status = status,
        runtime = UiAgentRuntime.LANGCHAIN,
        prompt = "prompt",
        response = if (status == UiAgentRunStatus.SUCCESS) "ok" else null,
        errorMessage = errorMessage,
        startedAtEpochMillis = startedAt,
        finishedAtEpochMillis = finishedAt,
    )
