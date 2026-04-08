@file:Suppress("MaxLineLength", "LargeClass", "LongMethod")

package io.qent.broxy.agents

import io.qent.broxy.agents.application.DefaultAgentService
import io.qent.broxy.agents.runtime.models.AgentModelCatalog
import io.qent.broxy.agents.runtime.models.CodexModelCatalog
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.PromptReference
import io.qent.broxy.core.models.ResourceReference
import io.qent.broxy.core.models.ToolReference
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultAgentServiceTest {
    private val codexModelCacheTtlMillis = 24 * 60 * 60 * 1000L

    @Test
    fun upsertAgent_keepsExistingOrderIndexAndManualDefaults() =
        runTest {
            val repository = InMemoryAgentRepository()
            val scheduler = RecordingScheduler()
            val existingDefaults =
                AgentManualLaunchDefaults(
                    prompt = "manual",
                    runtime = AgentRuntime.LANGCHAIN,
                    llm = runLlm(),
                    fileSystem = runFileSystem(),
                )
            repository.saveAgent(
                baseAgent().copy(
                    orderIndex = 3,
                    manualLaunchDefaults = existingDefaults,
                ),
            )
            val service =
                DefaultAgentService(
                    agentRepository = repository,
                    runRepository = InMemoryAgentRunRepository(),
                    settingsRepository = InMemoryProviderSettingsRepository(),
                    secretsStore = InMemorySecretsStore(),
                    configurationProvider = { McpServersConfig() },
                    executor = SuccessfulExecutor(),
                    scheduler = scheduler,
                    logger = NoopLogger,
                    scope = this,
                    now = { 1_000L },
                )

            val agent = baseAgent().copy(name = "Updated")

            val saved = service.upsertAgent(agent).getOrThrow()

            assertEquals(3, saved.orderIndex)
            assertEquals(existingDefaults, saved.manualLaunchDefaults)
            assertEquals(existingDefaults, service.loadAgent(agent.id)?.manualLaunchDefaults)
        }

    @Test
    fun reorderAgents_reindexesAndPersistsOrder() =
        runTest {
            val repository = InMemoryAgentRepository()
            repository.saveAgent(baseAgent().copy(id = "a1", name = "A1", orderIndex = 0))
            repository.saveAgent(baseAgent().copy(id = "a2", name = "A2", orderIndex = 1))
            repository.saveAgent(baseAgent().copy(id = "a3", name = "A3", orderIndex = 2))
            val service =
                DefaultAgentService(
                    agentRepository = repository,
                    runRepository = InMemoryAgentRunRepository(),
                    settingsRepository = InMemoryProviderSettingsRepository(),
                    secretsStore = InMemorySecretsStore(),
                    configurationProvider = { McpServersConfig() },
                    executor = SuccessfulExecutor(),
                    scheduler = RecordingScheduler(),
                    logger = NoopLogger,
                    scope = this,
                )

            val result = service.reorderAgents(listOf("a3", "a1", "a2"))

            assertTrue(result.isSuccess)
            assertEquals(listOf("a3", "a1", "a2"), result.getOrThrow().map { it.id })
            assertEquals(0, repository.loadAgent("a3").orderIndex)
            assertEquals(1, repository.loadAgent("a1").orderIndex)
            assertEquals(2, repository.loadAgent("a2").orderIndex)
        }

    @Test
    fun runAgent_skipsOverlapAndAppendsSkippedRecord() =
        runTest {
            val repository = InMemoryAgentRepository()
            val secrets = InMemorySecretsStore()
            val scheduler = RecordingScheduler()
            val runRepository = InMemoryAgentRunRepository()
            secrets.saveApiKey(LlmProvider.OPENAI, "test-key")
            repository.saveAgent(baseAgent())

            val service =
                DefaultAgentService(
                    agentRepository = repository,
                    runRepository = runRepository,
                    settingsRepository = InMemoryProviderSettingsRepository(),
                    secretsStore = secrets,
                    configurationProvider = { McpServersConfig() },
                    executor = DelayedExecutor(delayMillis = 1_000L),
                    scheduler = scheduler,
                    logger = NoopLogger,
                    scope = this,
                    now = { testScheduler.currentTime },
                )

            service.runAgent(agentId = "agent-1", prompt = "first", llm = runLlm(), fileSystem = runFileSystem())
            runCurrent()
            service.runAgent(agentId = "agent-1", prompt = "second", llm = runLlm(), fileSystem = runFileSystem())

            advanceTimeBy(1_100L)
            advanceUntilIdle()

            val runs = runRepository.listRuns()
            assertTrue(runs.any { it.status == AgentRunStatus.SKIPPED })
            assertTrue(runs.any { it.status == AgentRunStatus.SUCCESS })
        }

    @Test
    fun start_restoresSchedulesFromRepository() =
        runTest {
            val repository = InMemoryAgentRepository()
            val scheduler = RecordingScheduler()
            repository.saveAgent(
                baseAgent().copy(
                    schedule =
                        AgentSchedule(
                            cron = "*/5 * * * *",
                            prompt = "scheduled prompt",
                            timezoneId = "UTC",
                            runtime = AgentRuntime.LANGCHAIN,
                            llm = runLlm(),
                            fileSystem = runFileSystem(),
                        ),
                ),
            )
            repository.saveAgent(baseAgent().copy(id = "agent-2", schedule = null))

            val service =
                DefaultAgentService(
                    agentRepository = repository,
                    runRepository = InMemoryAgentRunRepository(),
                    settingsRepository = InMemoryProviderSettingsRepository(),
                    secretsStore = InMemorySecretsStore(),
                    configurationProvider = { McpServersConfig() },
                    executor = SuccessfulExecutor(),
                    scheduler = scheduler,
                    logger = NoopLogger,
                    scope = this,
                )

            service.start()

            val schedules = scheduler.snapshotSchedules()
            assertEquals(setOf("agent-1"), schedules.keys)
            assertEquals("*/5 * * * *", schedules.getValue("agent-1").cron)
        }

    @Test
    fun saveSchedule_rejectsInvalidCron() =
        runTest {
            val repository = InMemoryAgentRepository()
            repository.saveAgent(baseAgent())
            val service =
                DefaultAgentService(
                    agentRepository = repository,
                    runRepository = InMemoryAgentRunRepository(),
                    settingsRepository = InMemoryProviderSettingsRepository(),
                    secretsStore = InMemorySecretsStore(),
                    configurationProvider = { McpServersConfig() },
                    executor = SuccessfulExecutor(),
                    scheduler = RecordingScheduler(),
                    logger = NoopLogger,
                    scope = this,
                )

            val result =
                service.saveSchedule(
                    agentId = "agent-1",
                    cron = "bad cron",
                    prompt = "scheduled",
                    timezoneId = "UTC",
                    llm = runLlm(),
                    fileSystem = runFileSystem(),
                )

            assertTrue(result.isFailure)
            assertEquals(null, service.loadAgent("agent-1")?.schedule)
        }

    @Test
    fun saveSchedule_persistsFileSystemSettings() =
        runTest {
            val repository = InMemoryAgentRepository()
            repository.saveAgent(baseAgent())
            val service =
                DefaultAgentService(
                    agentRepository = repository,
                    runRepository = InMemoryAgentRunRepository(),
                    settingsRepository = InMemoryProviderSettingsRepository(),
                    secretsStore = InMemorySecretsStore(),
                    configurationProvider = { McpServersConfig() },
                    executor = SuccessfulExecutor(),
                    scheduler = RecordingScheduler(),
                    logger = NoopLogger,
                    scope = this,
                )

            val result =
                service.saveSchedule(
                    agentId = "agent-1",
                    cron = "*/15 * * * *",
                    prompt = "scheduled",
                    timezoneId = "UTC",
                    llm = runLlm(),
                    fileSystem =
                        AgentFileSystemSettings(
                            path = "  /tmp/broxy/agents  ",
                            access = AgentFileSystemAccess.READ_ONLY,
                        ),
                )

            assertTrue(result.isSuccess)
            val schedule = service.loadAgent("agent-1")?.schedule
            assertEquals("/tmp/broxy/agents", schedule?.fileSystem?.path)
            assertEquals(AgentFileSystemAccess.READ_ONLY, schedule?.fileSystem?.access)
        }

    @Test
    fun runAgent_recordsFailedWhenApiKeyIsMissing() =
        runTest {
            val repository = InMemoryAgentRepository()
            val runRepository = InMemoryAgentRunRepository()
            repository.saveAgent(baseAgent())
            val service =
                DefaultAgentService(
                    agentRepository = repository,
                    runRepository = runRepository,
                    settingsRepository = InMemoryProviderSettingsRepository(),
                    secretsStore = InMemorySecretsStore(),
                    configurationProvider = { McpServersConfig() },
                    executor = SuccessfulExecutor(),
                    scheduler = RecordingScheduler(),
                    logger = NoopLogger,
                    scope = this,
                    now = { testScheduler.currentTime },
                )

            val runResult = service.runAgent(agentId = "agent-1", prompt = "hello", llm = runLlm(), fileSystem = runFileSystem())
            assertTrue(runResult.isSuccess)
            advanceUntilIdle()

            val runs = runRepository.listRuns()
            assertEquals(1, runs.size)
            assertEquals(AgentRunStatus.FAILED, runs.first().status)
            val errorMessage = runs.first().errorMessage.orEmpty()
            assertTrue(errorMessage.contains("Missing API key"))
        }

    @Test
    fun runAgent_recordsFailedWhenExecutorReturnsFailure() =
        runTest {
            val repository = InMemoryAgentRepository()
            val secrets = InMemorySecretsStore()
            val runRepository = InMemoryAgentRunRepository()
            secrets.saveApiKey(LlmProvider.OPENAI, "test-key")
            repository.saveAgent(baseAgent())
            val service =
                DefaultAgentService(
                    agentRepository = repository,
                    runRepository = runRepository,
                    settingsRepository = InMemoryProviderSettingsRepository(),
                    secretsStore = secrets,
                    configurationProvider = { McpServersConfig() },
                    executor = FailingExecutor("downstream failed"),
                    scheduler = RecordingScheduler(),
                    logger = NoopLogger,
                    scope = this,
                    now = { testScheduler.currentTime },
                )

            val runResult = service.runAgent(agentId = "agent-1", prompt = "hello", llm = runLlm(), fileSystem = runFileSystem())
            assertTrue(runResult.isSuccess)
            advanceUntilIdle()

            val runs = runRepository.listRuns()
            assertEquals(1, runs.size)
            assertEquals(AgentRunStatus.FAILED, runs.first().status)
            assertEquals("downstream failed", runs.first().errorMessage)
            assertTrue(runs.first().response.isNullOrBlank())
        }

    @Test
    fun runAgent_manualLaunch_persistsManualLaunchDefaults() =
        runTest {
            val repository = InMemoryAgentRepository()
            val secrets = InMemorySecretsStore()
            secrets.saveApiKey(LlmProvider.OPENAI, "test-key")
            repository.saveAgent(baseAgent())
            val service =
                DefaultAgentService(
                    agentRepository = repository,
                    runRepository = InMemoryAgentRunRepository(),
                    settingsRepository = InMemoryProviderSettingsRepository(),
                    secretsStore = secrets,
                    configurationProvider = { McpServersConfig() },
                    executor = SuccessfulExecutor(),
                    scheduler = RecordingScheduler(),
                    logger = NoopLogger,
                    scope = this,
                    now = { testScheduler.currentTime },
                )

            val runResult =
                service.runAgent(
                    agentId = "agent-1",
                    prompt = "  remember this prompt  ",
                    llm =
                        AgentLlmConfig(
                            provider = LlmProvider.OPENAI,
                            model = " gpt-4o-mini ",
                            temperature = 0.6,
                        ),
                    fileSystem = runFileSystem(),
                )
            assertTrue(runResult.isSuccess)
            advanceUntilIdle()

            val persisted = service.loadAgent("agent-1")?.manualLaunchDefaults
            assertEquals("remember this prompt", persisted?.prompt)
            assertEquals(LlmProvider.OPENAI, persisted?.llm?.provider)
            assertEquals("gpt-4o-mini", persisted?.llm?.model)
            assertEquals(0.6, persisted?.llm?.temperature)
            assertEquals(DEFAULT_AGENT_WORKSPACE_PATH, persisted?.fileSystem?.path)
            assertEquals(AgentFileSystemAccess.NONE, persisted?.fileSystem?.access)
        }

    @Test
    fun runAgent_codexRuntimeBlockedWhenProviderDisabled() =
        runTest {
            val repository = InMemoryAgentRepository()
            val runRepository = InMemoryAgentRunRepository()
            repository.saveAgent(baseAgent())
            val service =
                DefaultAgentService(
                    agentRepository = repository,
                    runRepository = runRepository,
                    settingsRepository =
                        InMemoryProviderSettingsRepository(
                            initial = AgentProviderSettings(enableCodexProvider = false),
                        ),
                    secretsStore = InMemorySecretsStore(),
                    configurationProvider = { McpServersConfig() },
                    executor = SuccessfulExecutor(),
                    scheduler = RecordingScheduler(),
                    logger = NoopLogger,
                    scope = this,
                )

            val runResult =
                service.runAgent(
                    agentId = "agent-1",
                    prompt = "codex run",
                    runtime = AgentRuntime.CODEX_CLI,
                    llm = runLlm(),
                    codex = AgentCodexConfig(model = "gpt-5"),
                    fileSystem = runFileSystem(),
                )

            assertTrue(runResult.isFailure)
            assertEquals("Codex provider is disabled in Agent Settings", runResult.exceptionOrNull()?.message)
            assertTrue(runRepository.listRuns().isEmpty())
        }

    @Test
    fun saveSchedule_codexRuntimeBlockedWhenProviderDisabled() =
        runTest {
            val repository = InMemoryAgentRepository()
            repository.saveAgent(baseAgent())
            val service =
                DefaultAgentService(
                    agentRepository = repository,
                    runRepository = InMemoryAgentRunRepository(),
                    settingsRepository =
                        InMemoryProviderSettingsRepository(
                            initial = AgentProviderSettings(enableCodexProvider = false),
                        ),
                    secretsStore = InMemorySecretsStore(),
                    configurationProvider = { McpServersConfig() },
                    executor = SuccessfulExecutor(),
                    scheduler = RecordingScheduler(),
                    logger = NoopLogger,
                    scope = this,
                )

            val result =
                service.saveSchedule(
                    agentId = "agent-1",
                    cron = "*/30 * * * *",
                    prompt = "scheduled codex",
                    timezoneId = "UTC",
                    runtime = AgentRuntime.CODEX_CLI,
                    llm = runLlm(),
                    codex = AgentCodexConfig(model = "gpt-5"),
                    fileSystem = runFileSystem(),
                )

            assertTrue(result.isFailure)
            assertEquals("Codex provider is disabled in Agent Settings", result.exceptionOrNull()?.message)
            assertEquals(null, service.loadAgent("agent-1")?.schedule)
        }

    @Test
    fun runAgent_codexRuntimePersistsNormalizedDefaultsAndSkipsApiKeyLookup() =
        runTest {
            val repository = InMemoryAgentRepository()
            val executor = CapturingExecutor()
            repository.saveAgent(baseAgent())
            val service =
                DefaultAgentService(
                    agentRepository = repository,
                    runRepository = InMemoryAgentRunRepository(),
                    settingsRepository =
                        InMemoryProviderSettingsRepository(
                            initial = AgentProviderSettings(enableCodexProvider = true),
                        ),
                    secretsStore = InMemorySecretsStore(),
                    configurationProvider = { McpServersConfig() },
                    executor = executor,
                    scheduler = RecordingScheduler(),
                    logger = NoopLogger,
                    scope = this,
                    now = { testScheduler.currentTime },
                )

            val runResult =
                service.runAgent(
                    agentId = "agent-1",
                    prompt = "  codex prompt  ",
                    runtime = AgentRuntime.CODEX_CLI,
                    llm = runLlm(),
                    codex =
                        AgentCodexConfig(
                            model = "  gpt-5  ",
                            reasoningEffort = AgentCodexReasoningEffort.MEDIUM,
                        ),
                    fileSystem =
                        AgentFileSystemSettings(
                            path = "   ",
                            access = AgentFileSystemAccess.READ_ONLY,
                        ),
                )

            assertTrue(runResult.isSuccess)
            advanceUntilIdle()

            val captured = executor.lastRequest
            assertEquals(AgentRuntime.CODEX_CLI, captured?.runtime)
            assertEquals("gpt-5", captured?.codex?.model)
            assertEquals(AgentCodexReasoningEffort.MEDIUM, captured?.codex?.reasoningEffort)
            assertEquals(null, captured?.apiKey)
            assertEquals(DEFAULT_AGENT_WORKSPACE_PATH, captured?.fileSystem?.path)

            val defaults = service.loadAgent("agent-1")?.manualLaunchDefaults
            assertEquals("codex prompt", defaults?.prompt)
            assertEquals(AgentRuntime.CODEX_CLI, defaults?.runtime)
            assertEquals("gpt-5", defaults?.codex?.model)
            assertEquals(AgentCodexReasoningEffort.MEDIUM, defaults?.codex?.reasoningEffort)
            assertEquals(AgentFileSystemAccess.READ_ONLY, defaults?.fileSystem?.access)
        }

    @Test
    fun runAgent_scheduledLaunch_doesNotOverwriteManualLaunchDefaults() =
        runTest {
            val repository = InMemoryAgentRepository()
            repository.saveAgent(
                baseAgent().copy(
                    manualLaunchDefaults =
                        AgentManualLaunchDefaults(
                            prompt = "manual prompt",
                            runtime = AgentRuntime.LANGCHAIN,
                            llm = runLlm(),
                            fileSystem = runFileSystem(),
                        ),
                ),
            )
            val service =
                DefaultAgentService(
                    agentRepository = repository,
                    runRepository = InMemoryAgentRunRepository(),
                    settingsRepository = InMemoryProviderSettingsRepository(),
                    secretsStore = InMemorySecretsStore(),
                    configurationProvider = { McpServersConfig() },
                    executor = SuccessfulExecutor(),
                    scheduler = RecordingScheduler(),
                    logger = NoopLogger,
                    scope = this,
                    now = { testScheduler.currentTime },
                )

            val runResult =
                service.runAgent(
                    agentId = "agent-1",
                    prompt = "scheduled prompt",
                    llm =
                        AgentLlmConfig(
                            provider = LlmProvider.LM_STUDIO,
                            model = "local-model",
                            temperature = 0.1,
                        ),
                    fileSystem = runFileSystem(),
                    trigger = AgentRunTrigger.SCHEDULED,
                )
            assertTrue(runResult.isSuccess)
            advanceUntilIdle()

            val persisted = service.loadAgent("agent-1")?.manualLaunchDefaults
            assertEquals("manual prompt", persisted?.prompt)
            assertEquals(runLlm(), persisted?.llm)
        }

    @Test
    fun runAgent_passesNormalizedFileSystemToExecutionRequest() =
        runTest {
            val repository = InMemoryAgentRepository()
            val secrets = InMemorySecretsStore()
            val executor = CapturingExecutor()
            secrets.saveApiKey(LlmProvider.OPENAI, "test-key")
            repository.saveAgent(baseAgent())
            val service =
                DefaultAgentService(
                    agentRepository = repository,
                    runRepository = InMemoryAgentRunRepository(),
                    settingsRepository = InMemoryProviderSettingsRepository(),
                    secretsStore = secrets,
                    configurationProvider = { McpServersConfig() },
                    executor = executor,
                    scheduler = RecordingScheduler(),
                    logger = NoopLogger,
                    scope = this,
                    now = { testScheduler.currentTime },
                )

            val runResult =
                service.runAgent(
                    agentId = "agent-1",
                    prompt = "hello",
                    llm = runLlm(),
                    fileSystem =
                        AgentFileSystemSettings(
                            path = "   ",
                            access = AgentFileSystemAccess.READ_WRITE,
                        ),
                )
            assertTrue(runResult.isSuccess)
            advanceUntilIdle()

            val captured = executor.lastRequest
            assertEquals(DEFAULT_AGENT_WORKSPACE_PATH, captured?.fileSystem?.path)
            assertEquals(AgentFileSystemAccess.READ_WRITE, captured?.fileSystem?.access)
        }

    @Test
    fun runAgent_logsStartedAndFinishedEvents() =
        runTest {
            val repository = InMemoryAgentRepository()
            val secrets = InMemorySecretsStore()
            val logger = ServiceRecordingLogger()
            secrets.saveApiKey(LlmProvider.OPENAI, "test-key")
            repository.saveAgent(baseAgent())
            val service =
                DefaultAgentService(
                    agentRepository = repository,
                    runRepository = InMemoryAgentRunRepository(),
                    settingsRepository = InMemoryProviderSettingsRepository(),
                    secretsStore = secrets,
                    configurationProvider = { McpServersConfig() },
                    executor = SuccessfulExecutor(),
                    scheduler = RecordingScheduler(),
                    logger = logger,
                    scope = this,
                    now = { testScheduler.currentTime },
                )

            val runResult = service.runAgent(agentId = "agent-1", prompt = "hello", llm = runLlm(), fileSystem = runFileSystem())
            assertTrue(runResult.isSuccess)
            advanceUntilIdle()

            assertTrue(logger.hasEvent("agent.run.started"))
            assertTrue(logger.hasEvent("agent.run.finished"))
            assertTrue(logger.hasEvent("agent.run.operation"))
        }

    @Test
    fun runAgent_codexRuntime_logsCodexModelInFinishedEvent() =
        runTest {
            val repository = InMemoryAgentRepository()
            val logger = ServiceRecordingLogger()
            repository.saveAgent(baseAgent())
            val service =
                DefaultAgentService(
                    agentRepository = repository,
                    runRepository = InMemoryAgentRunRepository(),
                    settingsRepository =
                        InMemoryProviderSettingsRepository(
                            initial = AgentProviderSettings(enableCodexProvider = true),
                        ),
                    secretsStore = InMemorySecretsStore(),
                    configurationProvider = { McpServersConfig() },
                    executor = SuccessfulExecutor(),
                    scheduler = RecordingScheduler(),
                    logger = logger,
                    scope = this,
                    now = { testScheduler.currentTime },
                )

            val runResult =
                service.runAgent(
                    agentId = "agent-1",
                    prompt = "hello",
                    runtime = AgentRuntime.CODEX_CLI,
                    llm =
                        AgentLlmConfig(
                            provider = LlmProvider.OPENAI,
                            model = "babbage-002",
                            temperature = 0.2,
                        ),
                    codex = AgentCodexConfig(model = "gpt-5.1-codex"),
                    fileSystem = runFileSystem(),
                )
            assertTrue(runResult.isSuccess)
            advanceUntilIdle()

            val finished = logger.entryForEvent("agent.run.finished")
            assertTrue(finished.contains("\"runtime\":\"CODEX_CLI\""))
            assertTrue(finished.contains("\"provider\":\"CODEX_CLI\""))
            assertTrue(finished.contains("\"model\":\"gpt-5.1-codex\""))
            assertFalse(finished.contains("\"model\":\"babbage-002\""))
            assertFalse(finished.contains("\"temperature\""))
        }

    @Test
    fun runAgent_emitsRunningOperationAndFinishedUpdates() =
        runTest {
            val repository = InMemoryAgentRepository()
            val secrets = InMemorySecretsStore()
            secrets.saveApiKey(LlmProvider.OPENAI, "test-key")
            repository.saveAgent(baseAgent())
            val service =
                DefaultAgentService(
                    agentRepository = repository,
                    runRepository = InMemoryAgentRunRepository(),
                    settingsRepository = InMemoryProviderSettingsRepository(),
                    secretsStore = secrets,
                    configurationProvider = { McpServersConfig() },
                    executor = OperationReportingExecutor(),
                    scheduler = RecordingScheduler(),
                    logger = NoopLogger,
                    scope = this,
                    now = { testScheduler.currentTime },
                )
            val updates = mutableListOf<AgentExecutionUpdate>()
            val collector =
                launch {
                    service.updates.take(4).toList(updates)
                }

            val runResult = service.runAgent(agentId = "agent-1", prompt = "hello", llm = runLlm(), fileSystem = runFileSystem())
            assertTrue(runResult.isSuccess)
            advanceUntilIdle()
            collector.join()

            assertIs<AgentExecutionUpdate.Running>(updates[0])
            val preparing = assertIs<AgentExecutionUpdate.Operation>(updates[1])
            assertEquals(AgentExecutionOperation.PreparingRun, preparing.operation)
            val loading = assertIs<AgentExecutionUpdate.Operation>(updates[2])
            assertEquals(AgentExecutionOperation.LoadingCapabilities, loading.operation)
            assertIs<AgentExecutionUpdate.Finished>(updates[3])
        }

    @Test
    fun stopAgent_cancelsRunAndClearsRunningState() =
        runTest {
            val repository = InMemoryAgentRepository()
            val secrets = InMemorySecretsStore()
            val runRepository = InMemoryAgentRunRepository()
            secrets.saveApiKey(LlmProvider.OPENAI, "test-key")
            repository.saveAgent(baseAgent())
            val service =
                DefaultAgentService(
                    agentRepository = repository,
                    runRepository = runRepository,
                    settingsRepository = InMemoryProviderSettingsRepository(),
                    secretsStore = secrets,
                    configurationProvider = { McpServersConfig() },
                    executor = DelayedExecutor(delayMillis = 5_000L),
                    scheduler = RecordingScheduler(),
                    logger = NoopLogger,
                    scope = this,
                    now = { testScheduler.currentTime },
                )

            service.runAgent(agentId = "agent-1", prompt = "hello", llm = runLlm(), fileSystem = runFileSystem())
            runCurrent()
            assertTrue(service.runningAgents().containsKey("agent-1"))

            val stopResult = service.stopAgent("agent-1")
            assertTrue(stopResult.isSuccess)
            advanceUntilIdle()

            assertTrue(service.runningAgentIds().isEmpty())
            assertTrue(service.runningAgents().isEmpty())
            val runs = runRepository.listRuns()
            assertTrue(runs.any { it.status == AgentRunStatus.FAILED })
        }

    @Test
    fun listProviderModels_returnsCachedModels_withoutNetworkCall() =
        runTest {
            val modelCatalog =
                RecordingModelCatalog(
                    result = Result.success(listOf("remote-model")),
                )
            val settingsRepository =
                InMemoryProviderSettingsRepository(
                    initial =
                        AgentProviderSettings(
                            modelCache = AgentProviderModelCache(openAi = listOf("cached-model")),
                        ),
                )
            val service =
                DefaultAgentService(
                    agentRepository = InMemoryAgentRepository(),
                    runRepository = InMemoryAgentRunRepository(),
                    settingsRepository = settingsRepository,
                    secretsStore = InMemorySecretsStore(),
                    configurationProvider = { McpServersConfig() },
                    executor = SuccessfulExecutor(),
                    scheduler = RecordingScheduler(),
                    modelCatalog = modelCatalog,
                    logger = NoopLogger,
                    scope = this,
                )

            val result = service.listProviderModels(LlmProvider.OPENAI, forceRefresh = false)

            assertTrue(result.isSuccess)
            assertEquals(listOf("cached-model"), result.getOrThrow())
            assertEquals(0, modelCatalog.calls)
        }

    @Test
    fun listProviderModels_fetchesAndPersistsCacheWhenMissing() =
        runTest {
            val modelCatalog =
                RecordingModelCatalog(
                    result = Result.success(listOf("new-model", "new-model")),
                )
            val settingsRepository = InMemoryProviderSettingsRepository()
            val secretsStore =
                InMemorySecretsStore().apply {
                    saveApiKey(LlmProvider.OPENAI, "test-key")
                }
            val service =
                DefaultAgentService(
                    agentRepository = InMemoryAgentRepository(),
                    runRepository = InMemoryAgentRunRepository(),
                    settingsRepository = settingsRepository,
                    secretsStore = secretsStore,
                    configurationProvider = { McpServersConfig() },
                    executor = SuccessfulExecutor(),
                    scheduler = RecordingScheduler(),
                    modelCatalog = modelCatalog,
                    logger = NoopLogger,
                    scope = this,
                )

            val result = service.listProviderModels(LlmProvider.OPENAI, forceRefresh = false)

            assertTrue(result.isSuccess)
            assertEquals(listOf("new-model"), result.getOrThrow())
            assertEquals(listOf("new-model"), settingsRepository.loadSettings().modelCache.openAi)
            assertEquals(1, modelCatalog.calls)
        }

    @Test
    fun listCodexModels_returnsFreshCachedModels_withoutCatalogCall() =
        runTest {
            val codexCatalog =
                RecordingCodexModelCatalog(
                    result = Result.success(listOf("remote-model")),
                )
            val nowEpochMillis = codexModelCacheTtlMillis
            val settingsRepository =
                InMemoryProviderSettingsRepository(
                    initial =
                        AgentProviderSettings(
                            modelCache =
                                AgentProviderModelCache(
                                    codex = listOf("cached-codex"),
                                    codexFetchedAtEpochMillis = nowEpochMillis - 1L,
                                ),
                        ),
                )
            val service =
                DefaultAgentService(
                    agentRepository = InMemoryAgentRepository(),
                    runRepository = InMemoryAgentRunRepository(),
                    settingsRepository = settingsRepository,
                    secretsStore = InMemorySecretsStore(),
                    configurationProvider = { McpServersConfig() },
                    executor = SuccessfulExecutor(),
                    scheduler = RecordingScheduler(),
                    codexModelCatalog = codexCatalog,
                    logger = NoopLogger,
                    scope = this,
                    now = { nowEpochMillis },
                )

            val result = service.listCodexModels(forceRefresh = false)

            assertTrue(result.isSuccess)
            assertEquals(listOf("cached-codex"), result.getOrThrow())
            assertEquals(0, codexCatalog.calls)
        }

    @Test
    fun listCodexModels_refreshesStaleCache_andPersistsTimestamp() =
        runTest {
            val codexCatalog =
                RecordingCodexModelCatalog(
                    result = Result.success(listOf("new-codex", "new-codex")),
                )
            val nowEpochMillis = codexModelCacheTtlMillis + 10L
            val settingsRepository =
                InMemoryProviderSettingsRepository(
                    initial =
                        AgentProviderSettings(
                            modelCache =
                                AgentProviderModelCache(
                                    codex = listOf("old-codex"),
                                    codexFetchedAtEpochMillis = 0L,
                                ),
                        ),
                )
            val service =
                DefaultAgentService(
                    agentRepository = InMemoryAgentRepository(),
                    runRepository = InMemoryAgentRunRepository(),
                    settingsRepository = settingsRepository,
                    secretsStore = InMemorySecretsStore(),
                    configurationProvider = { McpServersConfig() },
                    executor = SuccessfulExecutor(),
                    scheduler = RecordingScheduler(),
                    codexModelCatalog = codexCatalog,
                    logger = NoopLogger,
                    scope = this,
                    now = { nowEpochMillis },
                )

            val result = service.listCodexModels(forceRefresh = false)

            assertTrue(result.isSuccess)
            assertEquals(listOf("new-codex"), result.getOrThrow())
            val persisted = settingsRepository.loadSettings().modelCache
            assertEquals(listOf("new-codex"), persisted.codex)
            assertEquals(nowEpochMillis, persisted.codexFetchedAtEpochMillis)
            assertEquals(1, codexCatalog.calls)
        }

    @Test
    fun listCodexModels_forceRefresh_ignoresFreshCache() =
        runTest {
            val codexCatalog =
                RecordingCodexModelCatalog(
                    result = Result.success(listOf("forced-codex")),
                )
            val nowEpochMillis = 10_000L
            val settingsRepository =
                InMemoryProviderSettingsRepository(
                    initial =
                        AgentProviderSettings(
                            modelCache =
                                AgentProviderModelCache(
                                    codex = listOf("cached-codex"),
                                    codexFetchedAtEpochMillis = nowEpochMillis,
                                ),
                        ),
                )
            val service =
                DefaultAgentService(
                    agentRepository = InMemoryAgentRepository(),
                    runRepository = InMemoryAgentRunRepository(),
                    settingsRepository = settingsRepository,
                    secretsStore = InMemorySecretsStore(),
                    configurationProvider = { McpServersConfig() },
                    executor = SuccessfulExecutor(),
                    scheduler = RecordingScheduler(),
                    codexModelCatalog = codexCatalog,
                    logger = NoopLogger,
                    scope = this,
                    now = { nowEpochMillis },
                )

            val result = service.listCodexModels(forceRefresh = true)

            assertTrue(result.isSuccess)
            assertEquals(listOf("forced-codex"), result.getOrThrow())
            assertEquals(1, codexCatalog.calls)
        }

    @Test
    fun listCodexModels_usesCacheWhenRefreshFails() =
        runTest {
            val codexCatalog =
                RecordingCodexModelCatalog(
                    result = Result.failure(IllegalStateException("codex unavailable")),
                )
            val nowEpochMillis = codexModelCacheTtlMillis + 1L
            val settingsRepository =
                InMemoryProviderSettingsRepository(
                    initial =
                        AgentProviderSettings(
                            modelCache =
                                AgentProviderModelCache(
                                    codex = listOf("cached-codex"),
                                    codexFetchedAtEpochMillis = 0L,
                                ),
                        ),
                )
            val service =
                DefaultAgentService(
                    agentRepository = InMemoryAgentRepository(),
                    runRepository = InMemoryAgentRunRepository(),
                    settingsRepository = settingsRepository,
                    secretsStore = InMemorySecretsStore(),
                    configurationProvider = { McpServersConfig() },
                    executor = SuccessfulExecutor(),
                    scheduler = RecordingScheduler(),
                    codexModelCatalog = codexCatalog,
                    logger = NoopLogger,
                    scope = this,
                    now = { nowEpochMillis },
                )

            val result = service.listCodexModels(forceRefresh = false)

            assertTrue(result.isSuccess)
            assertEquals(listOf("cached-codex"), result.getOrThrow())
            assertEquals(1, codexCatalog.calls)
        }

    @Test
    fun listCodexModels_returnsFailureWhenNoCacheAndRefreshFails() =
        runTest {
            val codexCatalog =
                RecordingCodexModelCatalog(
                    result = Result.failure(IllegalStateException("codex unavailable")),
                )
            val service =
                DefaultAgentService(
                    agentRepository = InMemoryAgentRepository(),
                    runRepository = InMemoryAgentRunRepository(),
                    settingsRepository = InMemoryProviderSettingsRepository(),
                    secretsStore = InMemorySecretsStore(),
                    configurationProvider = { McpServersConfig() },
                    executor = SuccessfulExecutor(),
                    scheduler = RecordingScheduler(),
                    codexModelCatalog = codexCatalog,
                    logger = NoopLogger,
                    scope = this,
                )

            val result = service.listCodexModels(forceRefresh = false)

            assertTrue(result.isFailure)
            assertEquals("codex unavailable", result.exceptionOrNull()?.message)
            assertEquals(1, codexCatalog.calls)
        }

    @Test
    fun generateAgentDescription_failsWhenAiFeaturesDisabled() =
        runTest {
            val service =
                DefaultAgentService(
                    agentRepository = InMemoryAgentRepository(),
                    runRepository = InMemoryAgentRunRepository(),
                    settingsRepository =
                        InMemoryProviderSettingsRepository(
                            initial =
                                AgentProviderSettings(
                                    aiFeatures =
                                        AgentAiFeaturesSettings(
                                            enabled = false,
                                            runtime = AgentRuntime.LANGCHAIN,
                                            llm = runLlm(),
                                        ),
                                ),
                        ),
                    secretsStore = InMemorySecretsStore(),
                    configurationProvider = { McpServersConfig() },
                    executor = SuccessfulExecutor(),
                    scheduler = RecordingScheduler(),
                    logger = NoopLogger,
                    scope = this,
                )

            val result =
                service.generateAgentDescription(
                    AgentDescriptionGenerationCommand(
                        draft = baseAgent(),
                        capabilityContext = emptyList(),
                    ),
                )

            assertTrue(result.isFailure)
            assertEquals(
                "AI features are disabled in Agent Settings",
                result.exceptionOrNull()?.message,
            )
        }

    @Test
    fun generateAgentDescription_usesConfiguredRuntimeAndModelSettings() =
        runTest {
            val executor =
                SequencedCapturingExecutor(
                    responses =
                        listOf(
                            "Helps coordinate MCP tools to analyze requests, gather relevant context, and return actionable responses for developers, especially when tasks require selecting servers, invoking precise capabilities, and summarizing outcomes quickly for operational follow-up.",
                        ),
                )
            val service =
                DefaultAgentService(
                    agentRepository = InMemoryAgentRepository(),
                    runRepository = InMemoryAgentRunRepository(),
                    settingsRepository =
                        InMemoryProviderSettingsRepository(
                            initial =
                                AgentProviderSettings(
                                    enableCodexProvider = true,
                                    codex = AgentCodexGlobalSettings(command = "codex-custom"),
                                    aiFeatures =
                                        AgentAiFeaturesSettings(
                                            enabled = true,
                                            runtime = AgentRuntime.CODEX_CLI,
                                            llm =
                                                AgentLlmConfig(
                                                    provider = LlmProvider.OPENAI,
                                                    model = "unused-for-codex",
                                                    temperature = 0.3,
                                                ),
                                            codex =
                                                AgentCodexConfig(
                                                    model = "gpt-5.1-codex-pro",
                                                    reasoningEffort = AgentCodexReasoningEffort.LOW,
                                                    webSearch = true,
                                                ),
                                        ),
                                ),
                        ),
                    secretsStore = InMemorySecretsStore(),
                    configurationProvider = { McpServersConfig() },
                    executor = executor,
                    scheduler = RecordingScheduler(),
                    logger = NoopLogger,
                    scope = this,
                )

            val result =
                service.generateAgentDescription(
                    AgentDescriptionGenerationCommand(
                        draft = baseAgent(),
                        capabilityContext = emptyList(),
                    ),
                )

            assertTrue(result.isSuccess)
            assertEquals(1, executor.requests.size)
            val request = executor.requests.single()
            assertEquals(AgentRuntime.CODEX_CLI, request.runtime)
            assertEquals("gpt-5.1-codex-pro", request.codex?.model)
            assertEquals(AgentCodexReasoningEffort.LOW, request.codex?.reasoningEffort)
            assertEquals(true, request.codex?.webSearch)
            assertEquals("codex-custom", request.providerSettings.codex.command)
        }

    @Test
    fun generateAgentDescription_retriesWhenFirstResponseHasInvalidWordCount() =
        runTest {
            val executor =
                SequencedCapturingExecutor(
                    responses =
                        listOf(
                            "Too short response.",
                            "Helps coordinate MCP tools to analyze requests, gather relevant context, and return actionable responses for developers, especially when tasks require selecting servers, invoking precise capabilities, and summarizing outcomes quickly for operational follow-up.",
                        ),
                )
            val service =
                DefaultAgentService(
                    agentRepository = InMemoryAgentRepository(),
                    runRepository = InMemoryAgentRunRepository(),
                    settingsRepository =
                        InMemoryProviderSettingsRepository(
                            initial =
                                AgentProviderSettings(
                                    aiFeatures =
                                        AgentAiFeaturesSettings(
                                            enabled = true,
                                            runtime = AgentRuntime.LANGCHAIN,
                                            llm =
                                                AgentLlmConfig(
                                                    provider = LlmProvider.LM_STUDIO,
                                                    model = "local-model",
                                                    temperature = 0.4,
                                                ),
                                        ),
                                ),
                        ),
                    secretsStore = InMemorySecretsStore(),
                    configurationProvider = { McpServersConfig() },
                    executor = executor,
                    scheduler = RecordingScheduler(),
                    logger = NoopLogger,
                    scope = this,
                )

            val result =
                service.generateAgentDescription(
                    AgentDescriptionGenerationCommand(
                        draft = baseAgent(),
                        capabilityContext = emptyList(),
                    ),
                )

            assertTrue(result.isSuccess)
            assertEquals(2, executor.requests.size)
        }

    @Test
    fun generateAgentDescription_failsAfterSecondInvalidResponse() =
        runTest {
            val executor =
                SequencedCapturingExecutor(
                    responses =
                        listOf(
                            "Still too short.",
                            "Another invalid response.",
                        ),
                )
            val service =
                DefaultAgentService(
                    agentRepository = InMemoryAgentRepository(),
                    runRepository = InMemoryAgentRunRepository(),
                    settingsRepository =
                        InMemoryProviderSettingsRepository(
                            initial =
                                AgentProviderSettings(
                                    aiFeatures =
                                        AgentAiFeaturesSettings(
                                            enabled = true,
                                            runtime = AgentRuntime.LANGCHAIN,
                                            llm =
                                                AgentLlmConfig(
                                                    provider = LlmProvider.LM_STUDIO,
                                                    model = "local-model",
                                                    temperature = 0.4,
                                                ),
                                        ),
                                ),
                        ),
                    secretsStore = InMemorySecretsStore(),
                    configurationProvider = { McpServersConfig() },
                    executor = executor,
                    scheduler = RecordingScheduler(),
                    logger = NoopLogger,
                    scope = this,
                )

            val result =
                service.generateAgentDescription(
                    AgentDescriptionGenerationCommand(
                        draft = baseAgent(),
                        capabilityContext = emptyList(),
                    ),
                )

            assertTrue(result.isFailure)
            assertTrue(
                result
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .contains("30-36 words"),
            )
            assertEquals(2, executor.requests.size)
        }

    @Test
    fun generateAgent_buildsDraftFromThreeStagePipeline() =
        runTest {
            val executor =
                SequencedCapturingExecutor(
                    responses =
                        listOf(
                            """
                            {
                              "serverIds": ["s1"]
                            }
                            """.trimIndent(),
                            """
                            {
                              "tools": ["search", "unknown_tool"],
                              "prompts": ["plan"],
                              "resources": ["incident://template"]
                            }
                            """.trimIndent(),
                            """
                            {
                              "agentName": "Incident Triage Agent",
                              "description": "Coordinates incident triage using curated MCP capabilities.",
                              "systemPrompt": "You triage incidents and use the selected MCP capabilities with concise, traceable steps.",
                              "selections": [
                                {
                                  "serverId": "s1",
                                  "tools": ["search", "search"],
                                  "prompts": ["plan"],
                                  "resources": ["incident://template"]
                                }
                              ]
                            }
                            """.trimIndent(),
                        ),
                )
            val progress = mutableListOf<AgentGenerationProgressStage>()
            val service =
                DefaultAgentService(
                    agentRepository = InMemoryAgentRepository(),
                    runRepository = InMemoryAgentRunRepository(),
                    settingsRepository =
                        InMemoryProviderSettingsRepository(
                            initial =
                                AgentProviderSettings(
                                    aiFeatures =
                                        AgentAiFeaturesSettings(
                                            enabled = true,
                                            runtime = AgentRuntime.LANGCHAIN,
                                            llm =
                                                AgentLlmConfig(
                                                    provider = LlmProvider.LM_STUDIO,
                                                    model = "local-model",
                                                    temperature = 0.2,
                                                ),
                                        ),
                                ),
                        ),
                    secretsStore = InMemorySecretsStore(),
                    configurationProvider = { McpServersConfig() },
                    executor = executor,
                    scheduler = RecordingScheduler(),
                    logger = NoopLogger,
                    scope = this,
                )

            val result =
                service.generateAgent(
                    AgentGenerationCommand(
                        userRequest = "Build an agent for incident triage and escalation checks",
                        capabilityContext = generationCapabilityContext(),
                        onProgress = { progress += it },
                    ),
                )

            assertTrue(result.isSuccess)
            val draft = result.getOrThrow()
            assertEquals("Incident Triage Agent", draft.agentName)
            assertNotNull(draft.description)
            assertTrue(draft.systemPrompt.isNotBlank())
            assertEquals(listOf(ToolReference(serverId = "s1", toolName = "search", enabled = true)), draft.tools)
            assertEquals(listOf(PromptReference(serverId = "s1", promptName = "plan", enabled = true)), draft.prompts)
            assertEquals(
                listOf(ResourceReference(serverId = "s1", resourceKey = "incident://template", enabled = true)),
                draft.resources,
            )
            assertEquals(
                listOf(
                    AgentGenerationProgressStage.SELECTING_SERVERS,
                    AgentGenerationProgressStage.SELECTING_CAPABILITIES,
                    AgentGenerationProgressStage.FINALIZING_AGENT,
                ),
                progress,
            )
            assertEquals(3, executor.requests.size)
        }

    @Test
    fun generateAgent_usesAiFeaturesOverrideWhenProvided() =
        runTest {
            val executor =
                SequencedCapturingExecutor(
                    responses =
                        listOf(
                            """
                            {
                              "serverIds": ["s1"]
                            }
                            """.trimIndent(),
                            """
                            {
                              "tools": ["search"],
                              "prompts": ["plan"],
                              "resources": ["incident://template"]
                            }
                            """.trimIndent(),
                            """
                            {
                              "agentName": "Incident Triage Agent",
                              "description": "Coordinates incident triage using curated MCP capabilities.",
                              "systemPrompt": "You triage incidents and use the selected MCP capabilities with concise, traceable steps.",
                              "selections": [
                                {
                                  "serverId": "s1",
                                  "tools": ["search"],
                                  "prompts": ["plan"],
                                  "resources": ["incident://template"]
                                }
                              ]
                            }
                            """.trimIndent(),
                        ),
                )
            val service =
                DefaultAgentService(
                    agentRepository = InMemoryAgentRepository(),
                    runRepository = InMemoryAgentRunRepository(),
                    settingsRepository =
                        InMemoryProviderSettingsRepository(
                            initial =
                                AgentProviderSettings(
                                    enableCodexProvider = true,
                                    aiFeatures =
                                        AgentAiFeaturesSettings(
                                            enabled = true,
                                            runtime = AgentRuntime.LANGCHAIN,
                                            llm =
                                                AgentLlmConfig(
                                                    provider = LlmProvider.LM_STUDIO,
                                                    model = "settings-model",
                                                    temperature = 0.2,
                                                ),
                                        ),
                                ),
                        ),
                    secretsStore = InMemorySecretsStore(),
                    configurationProvider = { McpServersConfig() },
                    executor = executor,
                    scheduler = RecordingScheduler(),
                    logger = NoopLogger,
                    scope = this,
                )

            val result =
                service.generateAgent(
                    AgentGenerationCommand(
                        userRequest = "Build an agent for incident triage and escalation checks",
                        capabilityContext = generationCapabilityContext(),
                        aiFeaturesOverride =
                            AgentAiFeaturesSettings(
                                enabled = true,
                                runtime = AgentRuntime.CODEX_CLI,
                                llm =
                                    AgentLlmConfig(
                                        provider = LlmProvider.OPENAI,
                                        model = "override-llm",
                                        temperature = 0.4,
                                    ),
                                codex =
                                    AgentCodexConfig(
                                        model = "gpt-5.1-codex-pro",
                                        reasoningEffort = AgentCodexReasoningEffort.LOW,
                                        webSearch = true,
                                    ),
                            ),
                    ),
                )

            assertTrue(result.isSuccess)
            assertEquals(3, executor.requests.size)
            executor.requests.forEach { request ->
                assertEquals(AgentRuntime.CODEX_CLI, request.runtime)
                assertEquals("gpt-5.1-codex-pro", request.codex?.model)
                assertEquals(AgentCodexReasoningEffort.LOW, request.codex?.reasoningEffort)
                assertEquals(true, request.codex?.webSearch)
            }
        }

    @Test
    fun generateAgent_failsWhenAiFeaturesDisabled() =
        runTest {
            val service =
                DefaultAgentService(
                    agentRepository = InMemoryAgentRepository(),
                    runRepository = InMemoryAgentRunRepository(),
                    settingsRepository =
                        InMemoryProviderSettingsRepository(
                            initial =
                                AgentProviderSettings(
                                    aiFeatures =
                                        AgentAiFeaturesSettings(
                                            enabled = false,
                                            runtime = AgentRuntime.LANGCHAIN,
                                            llm = runLlm(),
                                        ),
                                ),
                        ),
                    secretsStore = InMemorySecretsStore(),
                    configurationProvider = { McpServersConfig() },
                    executor = SuccessfulExecutor(),
                    scheduler = RecordingScheduler(),
                    logger = NoopLogger,
                    scope = this,
                )

            val result =
                service.generateAgent(
                    AgentGenerationCommand(
                        userRequest = "Generate an agent",
                        capabilityContext = generationCapabilityContext(),
                    ),
                )

            assertTrue(result.isFailure)
            assertEquals("AI features are disabled in Agent Settings", result.exceptionOrNull()?.message)
        }

    @Test
    fun generateAgent_codexRuntimeBlockedWhenProviderDisabled() =
        runTest {
            val service =
                DefaultAgentService(
                    agentRepository = InMemoryAgentRepository(),
                    runRepository = InMemoryAgentRunRepository(),
                    settingsRepository =
                        InMemoryProviderSettingsRepository(
                            initial =
                                AgentProviderSettings(
                                    enableCodexProvider = false,
                                    aiFeatures =
                                        AgentAiFeaturesSettings(
                                            enabled = true,
                                            runtime = AgentRuntime.CODEX_CLI,
                                            llm = runLlm(),
                                            codex = AgentCodexConfig(model = "gpt-5.1-codex"),
                                        ),
                                ),
                        ),
                    secretsStore = InMemorySecretsStore(),
                    configurationProvider = { McpServersConfig() },
                    executor = SuccessfulExecutor(),
                    scheduler = RecordingScheduler(),
                    logger = NoopLogger,
                    scope = this,
                )

            val result =
                service.generateAgent(
                    AgentGenerationCommand(
                        userRequest = "Generate an agent",
                        capabilityContext = generationCapabilityContext(),
                    ),
                )

            assertTrue(result.isFailure)
            assertEquals("Codex provider is disabled in Agent Settings", result.exceptionOrNull()?.message)
        }

    @Test
    fun generateAgent_overrideCodexRuntimeBlockedWhenProviderDisabled() =
        runTest {
            val service =
                DefaultAgentService(
                    agentRepository = InMemoryAgentRepository(),
                    runRepository = InMemoryAgentRunRepository(),
                    settingsRepository =
                        InMemoryProviderSettingsRepository(
                            initial =
                                AgentProviderSettings(
                                    enableCodexProvider = false,
                                    aiFeatures =
                                        AgentAiFeaturesSettings(
                                            enabled = true,
                                            runtime = AgentRuntime.LANGCHAIN,
                                            llm = runLlm(),
                                        ),
                                ),
                        ),
                    secretsStore = InMemorySecretsStore(),
                    configurationProvider = { McpServersConfig() },
                    executor = SuccessfulExecutor(),
                    scheduler = RecordingScheduler(),
                    logger = NoopLogger,
                    scope = this,
                )

            val result =
                service.generateAgent(
                    AgentGenerationCommand(
                        userRequest = "Generate an agent",
                        capabilityContext = generationCapabilityContext(),
                        aiFeaturesOverride =
                            AgentAiFeaturesSettings(
                                enabled = true,
                                runtime = AgentRuntime.CODEX_CLI,
                                llm = runLlm(),
                                codex = AgentCodexConfig(model = "gpt-5.1-codex"),
                            ),
                    ),
                )

            assertTrue(result.isFailure)
            assertEquals("Codex provider is disabled in Agent Settings", result.exceptionOrNull()?.message)
        }

    @Test
    fun generateAgent_failsWhenModelReturnsInvalidJson() =
        runTest {
            val executor =
                SequencedCapturingExecutor(
                    responses =
                        listOf(
                            "not a json payload",
                        ),
                )
            val service =
                DefaultAgentService(
                    agentRepository = InMemoryAgentRepository(),
                    runRepository = InMemoryAgentRunRepository(),
                    settingsRepository =
                        InMemoryProviderSettingsRepository(
                            initial =
                                AgentProviderSettings(
                                    aiFeatures =
                                        AgentAiFeaturesSettings(
                                            enabled = true,
                                            runtime = AgentRuntime.LANGCHAIN,
                                            llm = runLlm(),
                                        ),
                                ),
                        ),
                    secretsStore = InMemorySecretsStore(),
                    configurationProvider = { McpServersConfig() },
                    executor = executor,
                    scheduler = RecordingScheduler(),
                    logger = NoopLogger,
                    scope = this,
                )

            val result =
                service.generateAgent(
                    AgentGenerationCommand(
                        userRequest = "Generate an incident agent",
                        capabilityContext = generationCapabilityContext(),
                    ),
                )

            assertTrue(result.isFailure)
            assertTrue(
                result
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .isNotBlank(),
            )
        }

    @Test
    fun generateAgent_failsWhenFinalSystemPromptIsBlank() =
        runTest {
            val executor =
                SequencedCapturingExecutor(
                    responses =
                        listOf(
                            """
                            {
                              "serverIds": ["s1"]
                            }
                            """.trimIndent(),
                            """
                            {
                              "tools": ["search"],
                              "prompts": [],
                              "resources": []
                            }
                            """.trimIndent(),
                            """
                            {
                              "agentName": "Broken Agent",
                              "description": "Invalid output.",
                              "systemPrompt": "   ",
                              "selections": [
                                {
                                  "serverId": "s1",
                                  "tools": ["search"],
                                  "prompts": [],
                                  "resources": []
                                }
                              ]
                            }
                            """.trimIndent(),
                        ),
                )
            val service =
                DefaultAgentService(
                    agentRepository = InMemoryAgentRepository(),
                    runRepository = InMemoryAgentRunRepository(),
                    settingsRepository =
                        InMemoryProviderSettingsRepository(
                            initial =
                                AgentProviderSettings(
                                    aiFeatures =
                                        AgentAiFeaturesSettings(
                                            enabled = true,
                                            runtime = AgentRuntime.LANGCHAIN,
                                            llm =
                                                AgentLlmConfig(
                                                    provider = LlmProvider.LM_STUDIO,
                                                    model = "local-model",
                                                    temperature = 0.3,
                                                ),
                                        ),
                                ),
                        ),
                    secretsStore = InMemorySecretsStore(),
                    configurationProvider = { McpServersConfig() },
                    executor = executor,
                    scheduler = RecordingScheduler(),
                    logger = NoopLogger,
                    scope = this,
                )

            val result =
                service.generateAgent(
                    AgentGenerationCommand(
                        userRequest = "Generate an incident agent",
                        capabilityContext = generationCapabilityContext(),
                    ),
                )

            assertTrue(result.isFailure)
            assertTrue(
                result
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .contains("system prompt cannot be blank"),
            )
        }

    @Test
    fun generateAgent_failsWhenFinalSelectionContainsNoValidCapabilities() =
        runTest {
            val executor =
                SequencedCapturingExecutor(
                    responses =
                        listOf(
                            """
                            {
                              "serverIds": ["s1"]
                            }
                            """.trimIndent(),
                            """
                            {
                              "tools": ["search"],
                              "prompts": [],
                              "resources": []
                            }
                            """.trimIndent(),
                            """
                            {
                              "agentName": "Broken Agent",
                              "description": "Invalid output.",
                              "systemPrompt": "You are broken.",
                              "selections": [
                                {
                                  "serverId": "s1",
                                  "tools": ["missing_tool"],
                                  "prompts": [],
                                  "resources": []
                                }
                              ]
                            }
                            """.trimIndent(),
                        ),
                )
            val service =
                DefaultAgentService(
                    agentRepository = InMemoryAgentRepository(),
                    runRepository = InMemoryAgentRunRepository(),
                    settingsRepository =
                        InMemoryProviderSettingsRepository(
                            initial =
                                AgentProviderSettings(
                                    aiFeatures =
                                        AgentAiFeaturesSettings(
                                            enabled = true,
                                            runtime = AgentRuntime.LANGCHAIN,
                                            llm =
                                                AgentLlmConfig(
                                                    provider = LlmProvider.LM_STUDIO,
                                                    model = "local-model",
                                                    temperature = 0.3,
                                                ),
                                        ),
                                ),
                        ),
                    secretsStore = InMemorySecretsStore(),
                    configurationProvider = { McpServersConfig() },
                    executor = executor,
                    scheduler = RecordingScheduler(),
                    logger = NoopLogger,
                    scope = this,
                )

            val result =
                service.generateAgent(
                    AgentGenerationCommand(
                        userRequest = "Generate an incident agent",
                        capabilityContext = generationCapabilityContext(),
                    ),
                )

            assertTrue(result.isFailure)
            assertTrue(
                result
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .contains("at least one capability"),
            )
        }

    private fun baseAgent(): AgentDefinition =
        AgentDefinition(
            id = "agent-1",
            name = "Agent 1",
            systemPrompt = "You are helpful",
            tools = listOf(ToolReference(serverId = "s1", toolName = "search", enabled = true)),
        )

    private fun runLlm(): AgentLlmConfig =
        AgentLlmConfig(
            provider = LlmProvider.OPENAI,
            model = "gpt-4o-mini",
            temperature = 0.2,
        )

    private fun runFileSystem(): AgentFileSystemSettings =
        AgentFileSystemSettings(
            path = DEFAULT_AGENT_WORKSPACE_PATH,
            access = AgentFileSystemAccess.NONE,
        )

    private fun generationCapabilityContext(): List<AgentServerCapabilitySummary> =
        listOf(
            AgentServerCapabilitySummary(
                serverId = "s1",
                serverName = "Incidents",
                tools =
                    listOf(
                        AgentCapabilityToolSummary(
                            name = "search",
                            description = "Search incidents",
                            arguments =
                                listOf(
                                    AgentCapabilityArgumentSummary(
                                        name = "query",
                                        type = "string",
                                        required = true,
                                    ),
                                ),
                        ),
                    ),
                prompts = listOf(AgentCapabilityPromptSummary(name = "plan", description = "Build triage plan")),
                resources = listOf(AgentCapabilityResourceSummary(key = "incident://template", description = "Template")),
            ),
            AgentServerCapabilitySummary(
                serverId = "s2",
                serverName = "Knowledge",
                tools = listOf(AgentCapabilityToolSummary(name = "lookup", description = "Lookup docs")),
            ),
        )
}

private class InMemoryAgentRepository : AgentRepository {
    private val data = linkedMapOf<String, AgentDefinition>()

    override fun listAgents(): List<AgentDefinition> =
        data.values
            .sortedWith(
                compareBy<AgentDefinition> { it.orderIndex }
                    .thenBy { it.id },
            )

    override fun loadAgent(id: String): AgentDefinition = requireNotNull(data[id]) { "Agent '$id' not found" }

    override fun saveAgent(agent: AgentDefinition) {
        data[agent.id] = agent
    }

    override fun deleteAgent(id: String) {
        data.remove(id)
    }
}

private class InMemoryAgentRunRepository : AgentRunRepository {
    private val runs = linkedMapOf<String, AgentRunDetails>()

    override fun listRuns(): List<AgentRunSummary> =
        runs.values
            .map { it.summary }
            .sortedWith(
                compareByDescending<AgentRunSummary> { it.startedAtEpochMillis }
                    .thenByDescending { it.finishedAtEpochMillis }
                    .thenBy { it.runId },
            )

    override fun loadRun(runId: String): AgentRunDetails = requireNotNull(runs[runId]) { "Run '$runId' not found" }

    override fun saveRun(details: AgentRunDetails) {
        runs[details.summary.runId] = details
    }
}

private class InMemoryProviderSettingsRepository(
    initial: AgentProviderSettings = AgentProviderSettings(),
) : AgentProviderSettingsRepository {
    private var settings: AgentProviderSettings = initial

    override fun loadSettings(): AgentProviderSettings = settings

    override fun saveSettings(settings: AgentProviderSettings) {
        this.settings = settings
    }
}

private class InMemorySecretsStore : AgentSecretsStore {
    private val data = linkedMapOf<LlmProvider, String>()

    override fun loadApiKey(provider: LlmProvider): String? = data[provider]

    override fun saveApiKey(
        provider: LlmProvider,
        apiKey: String,
    ) {
        data[provider] = apiKey
    }

    override fun clearApiKey(provider: LlmProvider) {
        data.remove(provider)
    }
}

private class RecordingScheduler : AgentScheduler {
    private val schedules = linkedMapOf<String, AgentSchedule>()
    private var trigger: (suspend (agentId: String, schedule: AgentSchedule) -> Unit)? = null

    override fun start(
        schedules: Map<String, AgentSchedule>,
        onTrigger: suspend (agentId: String, schedule: AgentSchedule) -> Unit,
    ) {
        this.schedules.clear()
        this.schedules.putAll(schedules)
        trigger = onTrigger
    }

    override fun updateSchedule(
        agentId: String,
        schedule: AgentSchedule?,
    ) {
        if (schedule == null) {
            schedules.remove(agentId)
        } else {
            schedules[agentId] = schedule
        }
    }

    override fun stop() {
        schedules.clear()
        trigger = null
    }

    fun snapshotSchedules(): Map<String, AgentSchedule> = schedules.toMap()
}

private class SuccessfulExecutor : AgentExecutor {
    override suspend fun execute(request: AgentExecutionRequest): Result<AgentExecutionResult> =
        Result.success(AgentExecutionResult(response = "ok"))
}

private class CapturingExecutor : AgentExecutor {
    var lastRequest: AgentExecutionRequest? = null

    override suspend fun execute(request: AgentExecutionRequest): Result<AgentExecutionResult> {
        lastRequest = request
        return Result.success(AgentExecutionResult(response = "ok"))
    }
}

private class SequencedCapturingExecutor(
    private val responses: List<String>,
) : AgentExecutor {
    val requests = mutableListOf<AgentExecutionRequest>()

    override suspend fun execute(request: AgentExecutionRequest): Result<AgentExecutionResult> {
        requests += request
        val response =
            responses
                .getOrNull(requests.lastIndex)
                ?: responses.lastOrNull()
                ?: return Result.failure(IllegalStateException("No response configured for test executor"))
        return Result.success(AgentExecutionResult(response = response))
    }
}

private class DelayedExecutor(
    private val delayMillis: Long,
) : AgentExecutor {
    override suspend fun execute(request: AgentExecutionRequest): Result<AgentExecutionResult> {
        delay(delayMillis)
        return Result.success(AgentExecutionResult(response = "ok"))
    }
}

private class FailingExecutor(
    private val message: String,
) : AgentExecutor {
    override suspend fun execute(request: AgentExecutionRequest): Result<AgentExecutionResult> =
        Result.failure(IllegalStateException(message))
}

private class OperationReportingExecutor : AgentExecutor {
    override suspend fun execute(request: AgentExecutionRequest): Result<AgentExecutionResult> {
        request.onOperation(AgentExecutionOperation.LoadingCapabilities)
        return Result.success(AgentExecutionResult(response = "ok"))
    }
}

private class RecordingModelCatalog(
    private val result: Result<List<String>>,
) : AgentModelCatalog {
    var calls: Int = 0

    override suspend fun listModels(
        provider: LlmProvider,
        providerSettings: AgentProviderSettings,
        apiKey: String?,
        requestTimeoutSeconds: Int,
        ignoreHttpsCertificateErrors: Boolean,
    ): Result<List<String>> {
        calls += 1
        return result
    }
}

private class RecordingCodexModelCatalog(
    private val result: Result<List<String>>,
) : CodexModelCatalog {
    var calls: Int = 0

    override suspend fun listModels(command: String): Result<List<String>> {
        calls += 1
        return result
    }
}

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

private class ServiceRecordingLogger : Logger {
    private val entries = mutableListOf<String>()

    override fun debug(message: String) {
        entries += message
    }

    override fun info(message: String) {
        entries += message
    }

    override fun warn(
        message: String,
        throwable: Throwable?,
    ) {
        entries += message
    }

    override fun error(
        message: String,
        throwable: Throwable?,
    ) {
        entries += message
    }

    fun hasEvent(event: String): Boolean = entries.any { it.contains("\"event\":\"$event\"") }

    fun entryForEvent(event: String): String =
        entries.firstOrNull { it.contains("\"event\":\"$event\"") }
            ?: kotlin.error("Event '$event' not found in logs")
}
