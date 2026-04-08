package io.qent.broxy.agents

import io.qent.broxy.agents.infrastructure.persistence.JsonAgentRunRepository
import io.qent.broxy.core.utils.ConfigurationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonAgentRunRepositoryTest {
    @Test
    fun saveLoadListAndSortRuns() {
        val tempDir = Files.createTempDirectory("broxy-agent-runs")
        try {
            val json =
                Json {
                    prettyPrint = true
                    ignoreUnknownKeys = true
                }
            val repository = JsonAgentRunRepository(baseDir = tempDir, json = json)

            val first = runDetails(runId = "run-1", startedAt = 100L, finishedAt = 110L)
            val second = runDetails(runId = "run-2", startedAt = 300L, finishedAt = 320L)
            val third = runDetails(runId = "run-3", startedAt = 200L, finishedAt = 205L)

            repository.saveRun(first)
            repository.saveRun(second)
            repository.saveRun(third)

            val listed = repository.listRuns()
            assertEquals(listOf("run-2", "run-3", "run-1"), listed.map { it.runId })

            val loaded = repository.loadRun("run-2")
            assertEquals(second.summary, loaded.summary)
            assertEquals(second.dialogue, loaded.dialogue)
            assertEquals(second.actions, loaded.actions)

            val runFile = tempDir.resolve("agents").resolve("runs").resolve("run_run-2.json")
            val indexFile = tempDir.resolve("agents").resolve("runs_index.json")
            assertTrue(Files.exists(runFile))
            assertTrue(Files.exists(indexFile))

            val indexFromDisk =
                json.decodeFromString(
                    ListSerializer(AgentRunSummary.serializer()),
                    Files.readString(indexFile),
                )
            assertEquals(listOf("run-2", "run-3", "run-1"), indexFromDisk.map { it.runId })
        } finally {
            deleteTempDir(tempDir)
        }
    }

    @Test
    fun saveRun_updatesExistingRunByRunId() {
        val tempDir = Files.createTempDirectory("broxy-agent-runs-upsert")
        try {
            val repository = JsonAgentRunRepository(baseDir = tempDir)
            val original = runDetails(runId = "run-1", startedAt = 100L, finishedAt = 110L)
            val updated =
                original.copy(
                    summary =
                        original.summary.copy(
                            status = AgentRunStatus.FAILED,
                            errorMessage = "failure",
                            response = null,
                            finishedAtEpochMillis = 120L,
                        ),
                )

            repository.saveRun(original)
            repository.saveRun(updated)

            val listed = repository.listRuns()
            assertEquals(1, listed.size)
            assertEquals(AgentRunStatus.FAILED, listed.first().status)

            val loaded = repository.loadRun("run-1")
            assertEquals(AgentRunStatus.FAILED, loaded.summary.status)
            assertEquals("failure", loaded.summary.errorMessage)
        } finally {
            deleteTempDir(tempDir)
        }
    }

    @Test
    fun loadRun_throwsForMissingRun() {
        val tempDir = Files.createTempDirectory("broxy-agent-runs-missing")
        try {
            val repository = JsonAgentRunRepository(baseDir = tempDir)
            assertFailsWith<ConfigurationException> {
                repository.loadRun("missing")
            }
        } finally {
            deleteTempDir(tempDir)
        }
    }

    private fun runDetails(
        runId: String,
        startedAt: Long,
        finishedAt: Long,
    ): AgentRunDetails =
        AgentRunDetails(
            summary =
                AgentRunSummary(
                    runId = runId,
                    agentId = "agent-1",
                    agentName = "Agent 1",
                    trigger = AgentRunTrigger.MANUAL,
                    status = AgentRunStatus.SUCCESS,
                    runtime = AgentRuntime.LANGCHAIN,
                    prompt = "prompt-$runId",
                    response = "response-$runId",
                    errorMessage = null,
                    startedAtEpochMillis = startedAt,
                    finishedAtEpochMillis = finishedAt,
                ),
            systemPrompt = "system",
            llm =
                AgentLlmConfig(
                    provider = LlmProvider.OPENAI,
                    model = "gpt-4o-mini",
                    temperature = 0.2,
                ),
            fileSystem =
                AgentFileSystemSettings(
                    path = DEFAULT_AGENT_WORKSPACE_PATH,
                    access = AgentFileSystemAccess.NONE,
                ),
            dialogue =
                listOf(
                    AgentRunDialogueEntry(
                        role = AgentRunDialogueRole.USER,
                        content = "hello",
                        timestampEpochMillis = startedAt,
                    ),
                ),
            actions =
                listOf(
                    AgentRunActionEntry(
                        type = AgentRunActionType.PREPARING_RUN,
                        timestampEpochMillis = startedAt,
                    ),
                ),
        )

    private fun deleteTempDir(dir: java.nio.file.Path) {
        dir.toFile().walkBottomUp().forEach { file ->
            if (file.exists()) {
                file.delete()
            }
        }
        dir.deleteIfExists()
    }
}
