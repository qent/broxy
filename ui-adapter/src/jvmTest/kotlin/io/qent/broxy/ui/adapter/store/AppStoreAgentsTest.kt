package io.qent.broxy.ui.adapter.store

import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.presetmanagement.PresetManagementBackend
import io.qent.broxy.core.proxy.runtime.ProxyRuntimeFacade
import io.qent.broxy.core.proxy.runtime.ServerConnectionUpdate
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.core.utils.Logger
import io.qent.broxy.ui.adapter.agents.AgentGateway
import io.qent.broxy.ui.adapter.agents.UiAgentExecutionUpdate
import io.qent.broxy.ui.adapter.data.UiSettingsRepository
import io.qent.broxy.ui.adapter.models.UiAgent
import io.qent.broxy.ui.adapter.models.UiAgentAiFeaturesSettings
import io.qent.broxy.ui.adapter.models.UiAgentCodexConfig
import io.qent.broxy.ui.adapter.models.UiAgentDraft
import io.qent.broxy.ui.adapter.models.UiAgentFileSystemSettings
import io.qent.broxy.ui.adapter.models.UiAgentGenerationStage
import io.qent.broxy.ui.adapter.models.UiAgentLlmConfig
import io.qent.broxy.ui.adapter.models.UiAgentOperation
import io.qent.broxy.ui.adapter.models.UiAgentProviderConfig
import io.qent.broxy.ui.adapter.models.UiAgentProviderSettings
import io.qent.broxy.ui.adapter.models.UiAgentRunStatus
import io.qent.broxy.ui.adapter.models.UiAgentRunTrigger
import io.qent.broxy.ui.adapter.models.UiAgentRuntime
import io.qent.broxy.ui.adapter.models.UiAgentSchedule
import io.qent.broxy.ui.adapter.models.UiGeneratedAgentDraft
import io.qent.broxy.ui.adapter.models.UiLlmProvider
import io.qent.broxy.ui.adapter.models.UiRunDetails
import io.qent.broxy.ui.adapter.models.UiRunSummary
import io.qent.broxy.ui.adapter.models.UiSchedulePreview
import io.qent.broxy.ui.adapter.models.UiSettings
import io.qent.broxy.ui.adapter.remote.NoOpRemoteConnector
import io.qent.broxy.ui.adapter.remote.defaultRemoteState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppStoreAgentsTest {
    @Test
    fun start_mapsAgentsAndProviderSettings_andIntentsCallGateway() =
        runTest {
            val gateway =
                FakeAgentGateway(
                    agents =
                        mutableListOf(
                            testAgent(isRunning = false),
                        ),
                    providerSettings =
                        UiAgentProviderSettings(
                            openAi = UiAgentProviderConfig(baseUrl = "https://openai.local/v1"),
                            anthropic = UiAgentProviderConfig(baseUrl = "https://anthropic.local"),
                            lmStudio = UiAgentProviderConfig(baseUrl = "http://127.0.0.1:1234/v1"),
                        ),
                )
            val store = createStore(this, gateway)
            try {
                store.start()
                advanceUntilIdle()

                val ready = assertIs<UIState.Ready>(store.state.value)
                assertEquals(1, ready.agents.size)
                assertEquals("https://openai.local/v1", ready.agentProviderSettings.openAi.baseUrl)
                assertEquals("https://anthropic.local", ready.agentProviderSettings.anthropic.baseUrl)
                assertEquals("http://127.0.0.1:1234/v1", ready.agentProviderSettings.lmStudio.baseUrl)

                ready.intents.runAgent("agent-1", "hello", testRunLlm(), testFileSystem(), null)
                ready.intents.runAgent("agent-1", "scheduled", testRunLlm(), testFileSystem(), "*/5 * * * *")
                ready.intents.stopAgent("agent-1")
                ready.intents.removeAgentSchedule("agent-1")
                ready.intents.saveAgentProviderSettings(
                    UiAgentProviderSettings(
                        openAi = UiAgentProviderConfig(baseUrl = "https://new-openai.local"),
                        anthropic = UiAgentProviderConfig(baseUrl = ""),
                        lmStudio = UiAgentProviderConfig(baseUrl = "http://127.0.0.1:1234/v1"),
                    ),
                )
                ready.intents.saveAgentProviderApiKey(UiLlmProvider.OPENAI, "openai-key")
                ready.intents.clearAgentProviderApiKey(UiLlmProvider.OPENAI)
                advanceUntilIdle()

                assertEquals(listOf("hello"), gateway.runNowPrompts)
                assertEquals("/tmp/broxy/agents", gateway.runNowFileSystems.firstOrNull()?.path)
                assertEquals(1, gateway.scheduleCalls.size)
                val firstScheduleCall = gateway.scheduleCalls.first()
                assertEquals("*/5 * * * *", firstScheduleCall.cron)
                assertEquals("UTC", firstScheduleCall.timezoneId)
                assertEquals(
                    "/tmp/broxy/agents",
                    firstScheduleCall.fileSystem.path,
                )
                assertEquals(1, gateway.stopCalls)
                assertEquals(1, gateway.clearScheduleCalls)
                assertEquals("https://new-openai.local", gateway.savedProviderSettings.openAi.baseUrl)
                assertEquals(listOf(UiLlmProvider.OPENAI to "openai-key"), gateway.savedApiKeys)
                assertEquals(listOf(UiLlmProvider.OPENAI), gateway.clearedApiKeys)
            } finally {
                store.stop()
                advanceUntilIdle()
            }
        }

    @Test
    fun updates_reloadAgentsAndReflectRunningIndicator() =
        runTest {
            val gateway =
                FakeAgentGateway(
                    agents =
                        mutableListOf(
                            testAgent(isRunning = false),
                        ),
                    providerSettings = UiAgentProviderSettings(),
                )
            val store = createStore(this, gateway)
            try {
                store.start()
                advanceUntilIdle()
                var ready = assertIs<UIState.Ready>(store.state.value)
                assertEquals(false, ready.agents.first().isRunning)

                gateway.agents[0] = gateway.agents[0].copy(isRunning = true)
                gateway.emitUpdate(
                    UiAgentExecutionUpdate.Running(agentId = "agent-1", startedAtEpochMillis = 1_000L),
                )
                advanceUntilIdle()

                ready = assertIs<UIState.Ready>(store.state.value)
                assertTrue(ready.agents.first().isRunning)
            } finally {
                store.stop()
                advanceUntilIdle()
            }
        }

    @Test
    fun reorderAgents_updatesSnapshotAndCallsGateway() =
        runTest {
            val gateway =
                FakeAgentGateway(
                    agents =
                        mutableListOf(
                            testAgent(isRunning = false).copy(id = "agent-1", name = "Agent 1", orderIndex = 0),
                            testAgent(isRunning = false).copy(id = "agent-2", name = "Agent 2", orderIndex = 1),
                            testAgent(isRunning = false).copy(id = "agent-3", name = "Agent 3", orderIndex = 2),
                        ),
                    providerSettings = UiAgentProviderSettings(),
                )
            val store = createStore(this, gateway)
            try {
                store.start()
                advanceUntilIdle()

                val ready = assertIs<UIState.Ready>(store.state.value)
                ready.intents.reorderAgents(listOf("agent-3", "agent-1", "agent-2"))
                advanceUntilIdle()

                val updated = assertIs<UIState.Ready>(store.state.value)
                assertEquals(listOf("agent-3", "agent-1", "agent-2"), updated.agents.map { it.id })
                assertEquals(listOf(listOf("agent-3", "agent-1", "agent-2")), gateway.reorderCalls)
            } finally {
                store.stop()
                advanceUntilIdle()
            }
        }

    @Test
    fun start_loadsRunsAndMapsLatestFailedRunToAgentCard() =
        runTest {
            val failedRun =
                testRunSummary(
                    runId = "run-failed",
                    status = UiAgentRunStatus.FAILED,
                    errorMessage = "missing key",
                    startedAt = 2_000L,
                    finishedAt = 2_100L,
                )
            val gateway =
                FakeAgentGateway(
                    agents = mutableListOf(testAgent(isRunning = false)),
                    providerSettings = UiAgentProviderSettings(),
                    runs = mutableListOf(failedRun),
                )
            val store = createStore(this, gateway)
            try {
                store.start()
                advanceUntilIdle()

                val ready = assertIs<UIState.Ready>(store.state.value)
                assertEquals(listOf("run-failed"), ready.runs.map { it.runId })
                assertEquals(
                    "run-failed",
                    ready
                        .agents
                        .first()
                        .latestFailedRun
                        ?.runId,
                )
                assertEquals(
                    "missing key",
                    ready
                        .agents
                        .first()
                        .latestFailedRun
                        ?.errorMessage,
                )
            } finally {
                store.stop()
                advanceUntilIdle()
            }
        }

    @Test
    fun updates_patchOperation_andFinishedClearsRuntimeState() =
        runTest {
            val gateway =
                FakeAgentGateway(
                    agents = mutableListOf(testAgent(isRunning = false)),
                    providerSettings = UiAgentProviderSettings(),
                )
            val store = createStore(this, gateway)
            try {
                store.start()
                advanceUntilIdle()

                gateway.emitUpdate(UiAgentExecutionUpdate.Running(agentId = "agent-1", startedAtEpochMillis = 1_000L))
                gateway.emitUpdate(
                    UiAgentExecutionUpdate.Operation(
                        agentId = "agent-1",
                        operation = UiAgentOperation.ToolExecution(serverId = "s1", toolName = "search", step = 1),
                    ),
                )
                advanceUntilIdle()

                var ready = assertIs<UIState.Ready>(store.state.value)
                val runningAgent = ready.agents.first()
                assertTrue(runningAgent.isRunning)
                assertEquals(
                    UiAgentOperation.ToolExecution(serverId = "s1", toolName = "search", step = 1),
                    runningAgent.activeOperation,
                )

                gateway.agents[0] = gateway.agents[0].copy(isRunning = false, runningSinceEpochMillis = null)
                gateway.emitUpdate(
                    UiAgentExecutionUpdate.Finished(
                        agentId = "agent-1",
                        run = testRunSummary(status = UiAgentRunStatus.SUCCESS, response = "ok"),
                    ),
                )
                advanceUntilIdle()

                ready = assertIs<UIState.Ready>(store.state.value)
                assertEquals(false, ready.agents.first().isRunning)
                assertEquals(null, ready.agents.first().activeOperation)
            } finally {
                store.stop()
                advanceUntilIdle()
            }
        }

    @Test
    fun updates_finished_emitsAgentRunNotification() =
        runTest {
            val gateway =
                FakeAgentGateway(
                    agents = mutableListOf(testAgent(isRunning = false)),
                    providerSettings = UiAgentProviderSettings(),
                )
            val store = createStore(this, gateway)
            val notifications = mutableListOf<AgentRunNotification>()
            val collectorJob =
                launch {
                    store.agentRunNotifications.collect { notifications += it }
                }
            try {
                store.start()
                advanceUntilIdle()

                gateway.emitUpdate(
                    UiAgentExecutionUpdate.Finished(
                        agentId = "agent-1",
                        run =
                            testRunSummary(
                                status = UiAgentRunStatus.SUCCESS,
                                response = "first line\nsecond line",
                            ),
                    ),
                )
                advanceUntilIdle()

                assertEquals(1, notifications.size)
                val event = notifications.first()
                assertEquals("agent-1", event.agentId)
                assertEquals("Agent 1", event.agentName)
                assertEquals(UiAgentRunStatus.SUCCESS, event.status)
                assertEquals("first line second line", event.message)
            } finally {
                collectorJob.cancel()
                store.stop()
                advanceUntilIdle()
            }
        }

    @Test
    fun updates_finished_skipsAgentRunNotificationWhenDisabled() =
        runTest {
            val gateway =
                FakeAgentGateway(
                    agents = mutableListOf(testAgent(isRunning = false)),
                    providerSettings = UiAgentProviderSettings(),
                )
            val store =
                createStore(
                    scope = this,
                    gateway = gateway,
                    uiSettingsRepository = FakeUiSettingsRepository(UiSettings(agentRunNotificationsEnabled = false)),
                )
            val notifications = mutableListOf<AgentRunNotification>()
            val collectorJob =
                launch {
                    store.agentRunNotifications.collect { notifications += it }
                }
            try {
                store.start()
                advanceUntilIdle()

                gateway.emitUpdate(
                    UiAgentExecutionUpdate.Finished(
                        agentId = "agent-1",
                        run =
                            testRunSummary(
                                status = UiAgentRunStatus.FAILED,
                                errorMessage = "boom",
                            ),
                    ),
                )
                advanceUntilIdle()

                assertTrue(notifications.isEmpty())
            } finally {
                collectorJob.cancel()
                store.stop()
                advanceUntilIdle()
            }
        }

    @Test
    fun runAgent_manualLaunch_setsOptimisticPreparingStateImmediately() =
        runTest {
            val gateway =
                FakeAgentGateway(
                    agents = mutableListOf(testAgent(isRunning = false)),
                    providerSettings = UiAgentProviderSettings(),
                )
            val store = createStore(this, gateway)
            try {
                store.start()
                advanceUntilIdle()

                val ready = assertIs<UIState.Ready>(store.state.value)
                ready.intents.runAgent("agent-1", "hello", testRunLlm(), testFileSystem(), null)
                runCurrent()

                val optimistic = assertIs<UIState.Ready>(store.state.value)
                val optimisticAgent = optimistic.agents.first()
                assertTrue(optimisticAgent.isRunning)
                assertNotNull(optimisticAgent.runningSinceEpochMillis)
                assertEquals(UiAgentOperation.PreparingRun, optimisticAgent.activeOperation)
            } finally {
                store.stop()
                advanceUntilIdle()
            }
        }

    @Test
    fun runAgent_manualLaunch_requestsNotificationPermissionWhenEnabled() =
        runTest {
            val gateway =
                FakeAgentGateway(
                    agents = mutableListOf(testAgent(isRunning = false)),
                    providerSettings = UiAgentProviderSettings(),
                )
            val store = createStore(this, gateway)
            val requests = mutableListOf<AgentNotificationPermissionRequest>()
            val collectorJob =
                launch {
                    store.agentNotificationPermissionRequests.collect { requests += it }
                }
            try {
                store.start()
                advanceUntilIdle()

                val ready = assertIs<UIState.Ready>(store.state.value)
                ready.intents.runAgent("agent-1", "hello", testRunLlm(), testFileSystem(), null)
                runCurrent()

                assertEquals(1, requests.size)
                assertEquals("agent-1", requests.first().agentId)
            } finally {
                collectorJob.cancel()
                store.stop()
                advanceUntilIdle()
            }
        }

    @Test
    fun runAgent_manualLaunch_skipsNotificationPermissionRequestWhenDisabled() =
        runTest {
            val gateway =
                FakeAgentGateway(
                    agents = mutableListOf(testAgent(isRunning = false)),
                    providerSettings = UiAgentProviderSettings(),
                )
            val store =
                createStore(
                    scope = this,
                    gateway = gateway,
                    uiSettingsRepository = FakeUiSettingsRepository(UiSettings(agentRunNotificationsEnabled = false)),
                )
            val requests = mutableListOf<AgentNotificationPermissionRequest>()
            val collectorJob =
                launch {
                    store.agentNotificationPermissionRequests.collect { requests += it }
                }
            try {
                store.start()
                advanceUntilIdle()

                val ready = assertIs<UIState.Ready>(store.state.value)
                ready.intents.runAgent("agent-1", "hello", testRunLlm(), testFileSystem(), null)
                runCurrent()

                assertTrue(requests.isEmpty())
            } finally {
                collectorJob.cancel()
                store.stop()
                advanceUntilIdle()
            }
        }

    @Test
    fun runAgent_manualLaunch_rollsBackOptimisticStateOnFailure() =
        runTest {
            val gateway =
                FakeAgentGateway(
                    agents = mutableListOf(testAgent(isRunning = false)),
                    providerSettings = UiAgentProviderSettings(),
                ).apply {
                    runNowResult = Result.failure(IllegalStateException("boom"))
                }
            val store = createStore(this, gateway)
            try {
                store.start()
                advanceUntilIdle()

                val ready = assertIs<UIState.Ready>(store.state.value)
                ready.intents.runAgent("agent-1", "hello", testRunLlm(), testFileSystem(), null)
                advanceUntilIdle()

                val failed = assertIs<UIState.Error>(store.state.value)
                assertTrue(failed.message.contains("boom"))
            } finally {
                store.stop()
                advanceUntilIdle()
            }
        }

    @Test
    fun runAgent_manualLaunch_withScheduleClear_clearsScheduleThenRuns() =
        runTest {
            val gateway =
                FakeAgentGateway(
                    agents = mutableListOf(testAgentWithSchedule(isRunning = false)),
                    providerSettings = UiAgentProviderSettings(),
                )
            val store = createStore(this, gateway)
            try {
                store.start()
                advanceUntilIdle()

                val ready = assertIs<UIState.Ready>(store.state.value)
                ready.intents.runAgent(
                    id = "agent-1",
                    prompt = "manual after unschedule",
                    llm = testRunLlm(),
                    fileSystem = testFileSystem(),
                    cron = null,
                    clearExistingScheduleBeforeRun = true,
                )
                advanceUntilIdle()

                assertEquals(1, gateway.clearScheduleCalls)
                assertEquals(listOf("manual after unschedule"), gateway.runNowPrompts)
            } finally {
                store.stop()
                advanceUntilIdle()
            }
        }

    @Test
    fun runAgent_manualLaunch_withScheduleClear_doesNotRunWhenClearFails() =
        runTest {
            val gateway =
                FakeAgentGateway(
                    agents = mutableListOf(testAgentWithSchedule(isRunning = false)),
                    providerSettings = UiAgentProviderSettings(),
                ).apply {
                    clearScheduleResult = Result.failure(IllegalStateException("clear_failed"))
                }
            val store = createStore(this, gateway)
            try {
                store.start()
                advanceUntilIdle()

                val ready = assertIs<UIState.Ready>(store.state.value)
                ready.intents.runAgent(
                    id = "agent-1",
                    prompt = "manual after unschedule",
                    llm = testRunLlm(),
                    fileSystem = testFileSystem(),
                    cron = null,
                    clearExistingScheduleBeforeRun = true,
                )
                advanceUntilIdle()

                assertEquals(1, gateway.clearScheduleCalls)
                assertTrue(gateway.runNowPrompts.isEmpty())
                val failed = assertIs<UIState.Error>(store.state.value)
                assertTrue(failed.message.contains("clear_failed"))
            } finally {
                store.stop()
                advanceUntilIdle()
            }
        }

    @Test
    fun listCodexModels_success_updatesSnapshotCache() =
        runTest {
            val gateway =
                FakeAgentGateway(
                    agents = mutableListOf(testAgent(isRunning = false)),
                    providerSettings = UiAgentProviderSettings(),
                ).apply {
                    codexModelsResult = Result.success(listOf("z-model", "a-model", "z-model"))
                }
            val store = createStore(this, gateway, now = { 1_234L })
            try {
                store.start()
                advanceUntilIdle()

                val result = store.listCodexModels(forceRefresh = false)

                assertTrue(result.isSuccess)
                assertEquals(listOf("z-model", "a-model", "z-model"), result.getOrThrow())
                val ready = assertIs<UIState.Ready>(store.state.value)
                assertEquals(listOf("a-model", "z-model"), ready.agentProviderSettings.modelCache.codex)
                assertEquals(1_234L, ready.agentProviderSettings.modelCache.codexFetchedAtEpochMillis)
            } finally {
                store.stop()
                advanceUntilIdle()
            }
        }

    @Test
    fun listCodexModels_failure_keepsExistingSnapshotCache() =
        runTest {
            val gateway =
                FakeAgentGateway(
                    agents = mutableListOf(testAgent(isRunning = false)),
                    providerSettings =
                        UiAgentProviderSettings(
                            modelCache =
                                io.qent.broxy.ui.adapter.models.UiAgentProviderModelCache(
                                    codex = listOf("cached-model"),
                                    codexFetchedAtEpochMillis = 99L,
                                ),
                        ),
                ).apply {
                    codexModelsResult = Result.failure(IllegalStateException("codex failed"))
                }
            val store = createStore(this, gateway, now = { 2_000L })
            try {
                store.start()
                advanceUntilIdle()

                val result = store.listCodexModels(forceRefresh = true)

                assertTrue(result.isFailure)
                val ready = assertIs<UIState.Ready>(store.state.value)
                assertEquals(listOf("cached-model"), ready.agentProviderSettings.modelCache.codex)
                assertEquals(99L, ready.agentProviderSettings.modelCache.codexFetchedAtEpochMillis)
            } finally {
                store.stop()
                advanceUntilIdle()
            }
        }

    @Test
    fun previewAgentSchedule_forwardsToGateway() =
        runTest {
            val gateway =
                FakeAgentGateway(
                    agents = mutableListOf(testAgent(isRunning = false)),
                    providerSettings = UiAgentProviderSettings(),
                ).apply {
                    previewResult = Result.success(UiSchedulePreview(nextRunsEpochMillis = listOf(1L, 2L, 3L)))
                }
            val store = createStore(this, gateway)
            try {
                val result = store.previewAgentSchedule("*/5 * * * *")
                assertTrue(result.isSuccess)
                assertEquals(listOf(1L, 2L, 3L), result.getOrThrow().nextRunsEpochMillis)
                assertEquals(listOf(PreviewCall("*/5 * * * *", "UTC", 3)), gateway.previewCalls)
            } finally {
                store.stop()
                advanceUntilIdle()
            }
        }

    @Test
    fun generateAgentDescription_delegatesToGateway() =
        runTest {
            val gateway =
                FakeAgentGateway(
                    agents = mutableListOf(testAgent(isRunning = false)),
                    providerSettings = UiAgentProviderSettings(),
                ).apply {
                    generatedDescriptionResult =
                        Result.success(
                            "Helps coordinate MCP tools to analyze requests, gather relevant context, and return actionable responses for developers, especially when tasks require selecting servers, invoking precise capabilities, and summarizing outcomes quickly for operational follow-up.",
                        )
                }
            val store = createStore(this, gateway)
            try {
                store.start()
                advanceUntilIdle()

                val result =
                    store.generateAgentDescription(
                        UiAgentDraft(
                            id = "agent-1",
                            name = "Agent 1",
                            systemPrompt = "You are helpful",
                            description = null,
                            tools = emptyList(),
                            prompts = emptyList(),
                            resources = emptyList(),
                            promptsConfigured = true,
                            resourcesConfigured = true,
                        ),
                    )

                assertTrue(result.isSuccess)
                assertEquals(1, gateway.descriptionCalls.size)
                assertEquals(
                    "agent-1",
                    gateway.descriptionCalls
                        .first()
                        .draft.id,
                )
                assertEquals(0, gateway.descriptionCalls.first().snapshotCount)
            } finally {
                store.stop()
                advanceUntilIdle()
            }
        }

    @Test
    fun startGenerateAgentFromRequest_autosavesAndPublishesCompletion() =
        runTest {
            val gateway =
                FakeAgentGateway(
                    agents = mutableListOf(testAgent(isRunning = false)),
                    providerSettings = UiAgentProviderSettings(),
                ).apply {
                    generatedAgentResult =
                        Result.success(
                            UiGeneratedAgentDraft(
                                name = "Issue Triage Agent",
                                description = "Routes incidents and applies first-line triage actions.",
                                systemPrompt = "You triage incidents using the selected MCP capabilities.",
                                tools =
                                    listOf(
                                        io.qent.broxy.ui.adapter.models.UiToolRef(
                                            serverId = "s1",
                                            toolName = "search_issues",
                                            enabled = true,
                                        ),
                                    ),
                            ),
                        )
                }
            val store = createStore(this, gateway)
            try {
                store.start()
                advanceUntilIdle()
                store.updateAgentGenerationRequest(" triage production incidents ")

                store.startGenerateAgentFromRequest(testGenerationAiFeatures())
                advanceUntilIdle()

                assertEquals(1, gateway.generatedAgentCalls.size)
                val generationCall = gateway.generatedAgentCalls.first()
                assertEquals("triage production incidents", generationCall.request)
                assertEquals(
                    UiAgentRuntime.LANGCHAIN,
                    generationCall.aiFeaturesOverride.runtime,
                )
                assertEquals(
                    UiLlmProvider.OPENAI,
                    generationCall.aiFeaturesOverride.llm.provider,
                )
                assertEquals(1, gateway.upsertCalls.size)
                assertEquals("Issue Triage Agent", gateway.upsertCalls.first().name)
                val generationState = store.agentGenerationState.value
                assertEquals(false, generationState.isRunning)
                assertEquals("issue-triage-agent", generationState.generatedAgentId)

                store.acknowledgeAgentGenerationCompletion()
                assertEquals(null, store.agentGenerationState.value.generatedAgentId)
            } finally {
                store.stop()
                advanceUntilIdle()
            }
        }

    @Test
    fun startGenerateAgentFromRequest_isSingleFlight() =
        runTest {
            val gateway =
                FakeAgentGateway(
                    agents = mutableListOf(testAgent(isRunning = false)),
                    providerSettings = UiAgentProviderSettings(),
                ).apply {
                    generateAgentDelayMillis = 1_000
                    generatedAgentResult =
                        Result.success(
                            UiGeneratedAgentDraft(
                                name = "Delayed Agent",
                                systemPrompt = "Prompt",
                                tools =
                                    listOf(
                                        io.qent.broxy.ui.adapter.models.UiToolRef(
                                            serverId = "s1",
                                            toolName = "search",
                                            enabled = true,
                                        ),
                                    ),
                            ),
                        )
                }
            val store = createStore(this, gateway)
            try {
                store.start()
                advanceUntilIdle()
                store.updateAgentGenerationRequest("delayed generation")

                store.startGenerateAgentFromRequest(testGenerationAiFeatures())
                runCurrent()
                store.startGenerateAgentFromRequest(testGenerationAiFeatures())
                runCurrent()

                assertEquals(1, gateway.generatedAgentCalls.size)
                assertEquals(true, store.agentGenerationState.value.isRunning)
                assertEquals(AGENT_GENERATION_ERROR_ALREADY_RUNNING, store.agentGenerationState.value.errorMessage)

                advanceUntilIdle()
                assertEquals(false, store.agentGenerationState.value.isRunning)
            } finally {
                store.stop()
                advanceUntilIdle()
            }
        }

    @Test
    fun startGenerateAgentFromRequest_usesAllConfiguredServersInCacheFirstContext() =
        runTest {
            val configurationRepository =
                FakeConfigurationRepository().apply {
                    saveMcpConfig(
                        McpServersConfig(
                            servers =
                                listOf(
                                    io.qent.broxy.core.models.McpServerConfig(
                                        id = "s1",
                                        name = "Server 1",
                                        transport = TransportConfig.StdioTransport(command = "cmd1"),
                                        enabled = true,
                                    ),
                                    io.qent.broxy.core.models.McpServerConfig(
                                        id = "s2",
                                        name = "Server 2",
                                        transport = TransportConfig.StdioTransport(command = "cmd2"),
                                        enabled = false,
                                    ),
                                ),
                        ),
                    )
                }
            val gateway =
                FakeAgentGateway(
                    agents = mutableListOf(testAgent(isRunning = false)),
                    providerSettings = UiAgentProviderSettings(),
                ).apply {
                    generatedAgentResult =
                        Result.success(
                            UiGeneratedAgentDraft(
                                name = "Config Scope Agent",
                                systemPrompt = "Prompt",
                                tools =
                                    listOf(
                                        io.qent.broxy.ui.adapter.models.UiToolRef(
                                            serverId = "s1",
                                            toolName = "search",
                                            enabled = true,
                                        ),
                                    ),
                            ),
                        )
                }
            val store = createStore(this, gateway, configurationRepository = configurationRepository)
            try {
                store.start()
                advanceUntilIdle()
                store.updateAgentGenerationRequest("scope check")

                store.startGenerateAgentFromRequest(testGenerationAiFeatures())
                advanceUntilIdle()

                assertEquals(1, gateway.generatedAgentCalls.size)
                assertEquals(2, gateway.generatedAgentCalls.first().snapshotCount)
            } finally {
                store.stop()
                advanceUntilIdle()
            }
        }

    @Test
    fun clearAgentGenerationError_clearsLocalErrorState() =
        runTest {
            val gateway =
                FakeAgentGateway(
                    agents = mutableListOf(testAgent(isRunning = false)),
                    providerSettings = UiAgentProviderSettings(),
                )
            val store = createStore(this, gateway)
            try {
                store.updateAgentGenerationRequest("   ")
                store.startGenerateAgentFromRequest(testGenerationAiFeatures())
                runCurrent()
                assertNotNull(store.agentGenerationState.value.errorMessage)
                store.clearAgentGenerationError()
                assertEquals(null, store.agentGenerationState.value.errorMessage)
            } finally {
                store.stop()
                advanceUntilIdle()
            }
        }

    private fun createStore(
        scope: TestScope,
        gateway: FakeAgentGateway,
        configurationRepository: ConfigurationRepository = FakeConfigurationRepository(),
        uiSettingsRepository: UiSettingsRepository = FakeUiSettingsRepository(),
        now: () -> Long = { System.currentTimeMillis() },
    ): AppStore {
        val logger = CollectingLogger(delegate = NoopLogger)
        return AppStore(
            configurationRepository = configurationRepository,
            uiSettingsRepository = uiSettingsRepository,
            proxyRuntime = FakeProxyRuntime(),
            capabilityFetcher = { _, _, _, _ -> Result.success(ServerCapabilities()) },
            logger = logger,
            aiClientConnectors = emptyList(),
            agentGateway = gateway,
            scope = scope,
            ioDispatcher = ioDispatcher(scope),
            now = now,
            remoteConnector = NoOpRemoteConnector(defaultRemoteState()),
            enableBackgroundRefresh = false,
        )
    }

    private fun ioDispatcher(scope: TestScope): CoroutineDispatcher = requireNotNull(scope.coroutineContext[CoroutineDispatcher])
}

private class FakeUiSettingsRepository(
    private var settings: UiSettings = UiSettings(),
) : UiSettingsRepository {
    override fun loadUiSettings(): UiSettings = settings

    override fun saveUiSettings(settings: UiSettings) {
        this.settings = settings
    }
}

private class FakeConfigurationRepository : ConfigurationRepository {
    private var config: McpServersConfig = McpServersConfig()
    private val presets = linkedMapOf<String, Preset>()

    override fun loadMcpConfig(): McpServersConfig = config

    override fun saveMcpConfig(config: McpServersConfig) {
        this.config = config
    }

    override fun loadPreset(id: String): Preset = requireNotNull(presets[id]) { "Preset '$id' not found" }

    override fun savePreset(preset: Preset) {
        presets[preset.id] = preset
    }

    override fun listPresets(): List<Preset> = presets.values.toList()

    override fun deletePreset(id: String) {
        presets.remove(id)
    }
}

private class FakeProxyRuntime : ProxyRuntimeFacade {
    override val capabilityUpdates = MutableSharedFlow<Map<String, ServerCapabilities>>(replay = 1)
    override val serverStatusUpdates = MutableSharedFlow<ServerConnectionUpdate>(extraBufferCapacity = 16)
    override val isRunning: Boolean = false

    override fun start(
        config: McpServersConfig,
        preset: Preset,
        inbound: TransportConfig,
    ): Result<Unit> = Result.success(Unit)

    override fun stop(): Result<Unit> = Result.success(Unit)

    override fun applyPreset(preset: Preset): Result<Unit> = Result.success(Unit)

    override fun updateServers(config: McpServersConfig): Result<Unit> = Result.success(Unit)

    override fun refreshServerCapabilities(serverId: String): Result<Unit> = Result.success(Unit)

    override fun refreshFilteredCapabilities(): Result<Unit> = Result.success(Unit)

    override fun updateCallTimeout(seconds: Int) {}

    override fun updateCapabilitiesTimeout(seconds: Int) {}

    override fun updateConnectionRetryCount(count: Int) {}

    override fun updateIgnoreHttpsCertificateErrors(enabled: Boolean) {}

    override fun updateFallbackPromptsAndResourcesToTools(enabled: Boolean) {}

    override fun updateAdapterMode(enabled: Boolean) {}

    override fun registerPresetManagementBackend(backend: PresetManagementBackend) {}

    override fun clearPresetManagementBackend() {}
}

private class FakeAgentGateway(
    val agents: MutableList<UiAgent>,
    private var providerSettings: UiAgentProviderSettings,
    val runs: MutableList<UiRunSummary> = mutableListOf(),
    val runDetailsById: MutableMap<String, UiRunDetails> = linkedMapOf(),
) : AgentGateway {
    private val updatesFlow = MutableSharedFlow<UiAgentExecutionUpdate>(extraBufferCapacity = 16)
    override val updates: Flow<UiAgentExecutionUpdate> = updatesFlow

    val runNowPrompts = mutableListOf<String>()
    val runNowFileSystems = mutableListOf<UiAgentFileSystemSettings>()
    val scheduleCalls = mutableListOf<ScheduleCall>()
    val previewCalls = mutableListOf<PreviewCall>()
    var stopCalls: Int = 0
    var clearScheduleCalls: Int = 0
    var runNowResult: Result<Unit> = Result.success(Unit)
    var clearScheduleResult: Result<UiAgent>? = null
    var previewResult: Result<UiSchedulePreview> = Result.success(UiSchedulePreview())
    var savedProviderSettings: UiAgentProviderSettings = providerSettings
    val savedApiKeys = mutableListOf<Pair<UiLlmProvider, String>>()
    val clearedApiKeys = mutableListOf<UiLlmProvider>()
    var codexModelsResult: Result<List<String>> = Result.success(emptyList())
    var generatedDescriptionResult: Result<String> = Result.success("description")
    val descriptionCalls = mutableListOf<DescriptionCall>()
    var generatedAgentResult: Result<UiGeneratedAgentDraft> =
        Result.success(
            UiGeneratedAgentDraft(
                name = "AI Agent",
                systemPrompt = "You are an AI agent.",
            ),
        )
    var generateAgentDelayMillis: Long = 0
    val generatedAgentCalls = mutableListOf<GenerateAgentCall>()
    val upsertCalls = mutableListOf<UiAgentDraft>()
    val reorderCalls = mutableListOf<List<String>>()

    override fun start() {}

    override fun stop() {}

    override suspend fun listAgents(): List<UiAgent> = agents.toList()

    override suspend fun getAgentDraft(id: String): UiAgentDraft? = null

    override suspend fun upsertAgent(draft: UiAgentDraft): Result<UiAgent> {
        upsertCalls += draft
        val updated =
            UiAgent(
                id = draft.id,
                name = draft.name,
                systemPrompt = draft.systemPrompt,
                description = draft.description,
                tools = draft.tools,
                prompts = draft.prompts,
                resources = draft.resources,
                promptsConfigured = draft.promptsConfigured,
                resourcesConfigured = draft.resourcesConfigured,
                toolsCount = draft.tools.count { it.enabled },
                promptsCount = draft.prompts.count { it.enabled },
                resourcesCount = draft.resources.count { it.enabled },
                orderIndex = draft.orderIndex,
                schedule = draft.schedule,
                manualLaunchDefaults = draft.manualLaunchDefaults,
            )
        agents.removeAll { it.id == updated.id }
        agents += updated
        agents.sortBy { it.orderIndex }
        return Result.success(updated)
    }

    override suspend fun deleteAgent(id: String): Result<Unit> = Result.success(Unit)

    override suspend fun reorderAgents(agentIds: List<String>): Result<List<UiAgent>> {
        reorderCalls += agentIds
        if (agentIds.size != agents.size || agentIds.toSet().size != agentIds.size) {
            return Result.failure(IllegalArgumentException("Invalid agent reorder request"))
        }
        val byId = agents.associateBy { it.id }
        if (!agentIds.all { it in byId }) {
            return Result.failure(IllegalArgumentException("Invalid agent reorder request"))
        }
        val reordered =
            agentIds.mapIndexed { index, id ->
                byId.getValue(id).copy(orderIndex = index)
            }
        agents.clear()
        agents += reordered
        return Result.success(reordered)
    }

    override suspend fun runAgentNow(
        id: String,
        prompt: String,
        llm: UiAgentLlmConfig,
        fileSystem: UiAgentFileSystemSettings,
        runtime: io.qent.broxy.ui.adapter.models.UiAgentRuntime,
        codex: io.qent.broxy.ui.adapter.models.UiAgentCodexConfig?,
    ): Result<Unit> {
        runNowPrompts += prompt
        runNowFileSystems += fileSystem
        return runNowResult
    }

    override suspend fun stopAgent(id: String): Result<Unit> {
        stopCalls += 1
        return Result.success(Unit)
    }

    override suspend fun saveSchedule(
        id: String,
        cron: String,
        prompt: String,
        timezoneId: String,
        llm: UiAgentLlmConfig,
        fileSystem: UiAgentFileSystemSettings,
        runtime: io.qent.broxy.ui.adapter.models.UiAgentRuntime,
        codex: io.qent.broxy.ui.adapter.models.UiAgentCodexConfig?,
    ): Result<UiAgent> {
        scheduleCalls += ScheduleCall(id, cron, prompt, timezoneId, fileSystem)
        return Result.success(requireNotNull(agents.firstOrNull { it.id == id }))
    }

    override suspend fun clearSchedule(id: String): Result<UiAgent> {
        clearScheduleCalls += 1
        return clearScheduleResult ?: Result.success(requireNotNull(agents.firstOrNull { it.id == id }))
    }

    override suspend fun listRuns(): List<UiRunSummary> = runs.toList()

    override suspend fun loadRun(runId: String): UiRunDetails? = runDetailsById[runId]

    override suspend fun runningAgentIds(): Set<String> = agents.filter { it.isRunning }.mapTo(linkedSetOf()) { it.id }

    override suspend fun runningAgents(): Map<String, Long> =
        agents
            .filter { it.isRunning }
            .associate { agent -> agent.id to (agent.runningSinceEpochMillis ?: 0L) }

    override suspend fun loadProviderSettings(): UiAgentProviderSettings = providerSettings

    override suspend fun saveProviderSettings(settings: UiAgentProviderSettings): Result<UiAgentProviderSettings> {
        savedProviderSettings = settings
        providerSettings = settings
        return Result.success(settings)
    }

    override suspend fun saveProviderApiKey(
        provider: UiLlmProvider,
        apiKey: String,
    ): Result<Unit> {
        savedApiKeys += provider to apiKey
        return Result.success(Unit)
    }

    override suspend fun clearProviderApiKey(provider: UiLlmProvider): Result<Unit> {
        clearedApiKeys += provider
        return Result.success(Unit)
    }

    override suspend fun listProviderModels(
        provider: UiLlmProvider,
        forceRefresh: Boolean,
    ): Result<List<String>> = Result.success(emptyList())

    override suspend fun listCodexModels(forceRefresh: Boolean): Result<List<String>> = codexModelsResult

    override suspend fun generateAgentDescription(
        draft: UiAgentDraft,
        capabilitySnapshots: List<io.qent.broxy.ui.adapter.models.UiServerCapsSnapshot>,
    ): Result<String> {
        descriptionCalls += DescriptionCall(draft = draft, snapshotCount = capabilitySnapshots.size)
        return generatedDescriptionResult
    }

    override suspend fun generateAgentFromRequest(
        request: String,
        capabilitySnapshots: List<io.qent.broxy.ui.adapter.models.UiServerCapsSnapshot>,
        aiFeaturesOverride: UiAgentAiFeaturesSettings?,
        onProgress: (UiAgentGenerationStage) -> Unit,
    ): Result<UiGeneratedAgentDraft> {
        generatedAgentCalls +=
            GenerateAgentCall(
                request = request,
                snapshotCount = capabilitySnapshots.size,
                aiFeaturesOverride =
                    requireNotNull(aiFeaturesOverride) {
                        "Expected AI features override for generation"
                    },
            )
        if (generateAgentDelayMillis > 0) {
            delay(generateAgentDelayMillis)
        }
        onProgress(UiAgentGenerationStage.SELECTING_SERVERS)
        onProgress(UiAgentGenerationStage.SELECTING_CAPABILITIES)
        onProgress(UiAgentGenerationStage.FINALIZING_AGENT)
        return generatedAgentResult
    }

    override suspend fun previewSchedule(
        cron: String,
        timezoneId: String,
        limit: Int,
    ): Result<UiSchedulePreview> {
        previewCalls += PreviewCall(cron, timezoneId, limit)
        return previewResult
    }

    fun emitUpdate(update: UiAgentExecutionUpdate) {
        if (update is UiAgentExecutionUpdate.Finished) {
            runs.removeAll { it.runId == update.run.runId }
            runs += update.run
        }
        updatesFlow.tryEmit(update)
    }
}

private data class ScheduleCall(
    val id: String,
    val cron: String,
    val prompt: String,
    val timezoneId: String,
    val fileSystem: UiAgentFileSystemSettings,
)

private data class PreviewCall(
    val cron: String,
    val timezoneId: String,
    val limit: Int,
)

private data class DescriptionCall(
    val draft: UiAgentDraft,
    val snapshotCount: Int,
)

private data class GenerateAgentCall(
    val request: String,
    val snapshotCount: Int,
    val aiFeaturesOverride: UiAgentAiFeaturesSettings,
)

private fun testAgent(isRunning: Boolean): UiAgent =
    UiAgent(
        id = "agent-1",
        name = "Agent 1",
        systemPrompt = "You are helpful",
        toolsCount = 1,
        promptsCount = 0,
        resourcesCount = 0,
        isRunning = isRunning,
        runningSinceEpochMillis = if (isRunning) 1_000L else null,
    )

private fun testAgentWithSchedule(isRunning: Boolean): UiAgent =
    testAgent(isRunning = isRunning).copy(
        schedule =
            UiAgentSchedule(
                cron = "*/5 * * * *",
                prompt = "scheduled prompt",
                timezoneId = "UTC",
                llm = testRunLlm(),
            ),
    )

private fun testRunLlm(): UiAgentLlmConfig =
    UiAgentLlmConfig(
        provider = UiLlmProvider.OPENAI,
        model = "gpt-4o-mini",
        temperature = 0.2,
    )

private fun testGenerationAiFeatures(): UiAgentAiFeaturesSettings =
    UiAgentAiFeaturesSettings(
        enabled = true,
        runtime = UiAgentRuntime.LANGCHAIN,
        llm = testRunLlm(),
        codex = UiAgentCodexConfig(model = "gpt-5.1-codex-mini"),
    )

private fun testFileSystem(): UiAgentFileSystemSettings =
    UiAgentFileSystemSettings(
        path = "/tmp/broxy/agents",
    )

private fun testRunSummary(
    runId: String = "run-1",
    status: UiAgentRunStatus,
    response: String? = null,
    errorMessage: String? = null,
    startedAt: Long = 1_000L,
    finishedAt: Long = 2_000L,
): UiRunSummary =
    UiRunSummary(
        runId = runId,
        agentId = "agent-1",
        agentName = "Agent 1",
        trigger = UiAgentRunTrigger.MANUAL,
        status = status,
        runtime = UiAgentRuntime.LANGCHAIN,
        prompt = "hello",
        response = response,
        errorMessage = errorMessage,
        startedAtEpochMillis = startedAt,
        finishedAtEpochMillis = finishedAt,
    )

private object NoopLogger : Logger {
    override fun debug(message: String) = Unit

    override fun info(message: String) = Unit

    override fun warn(
        message: String,
        throwable: Throwable?,
    ) = Unit

    override fun error(
        message: String,
        throwable: Throwable?,
    ) = Unit
}
