package io.qent.broxy.ui.adapter.agents

import io.qent.broxy.agents.AgentAiFeaturesSettings
import io.qent.broxy.agents.AgentCapabilityArgumentSummary
import io.qent.broxy.agents.AgentCapabilityPromptSummary
import io.qent.broxy.agents.AgentCapabilityResourceSummary
import io.qent.broxy.agents.AgentCapabilityToolSummary
import io.qent.broxy.agents.AgentCodexConfig
import io.qent.broxy.agents.AgentCodexGlobalSettings
import io.qent.broxy.agents.AgentCodexReasoningEffort
import io.qent.broxy.agents.AgentDefinition
import io.qent.broxy.agents.AgentDescriptionGenerationCommand
import io.qent.broxy.agents.AgentExecutionOperation
import io.qent.broxy.agents.AgentExecutionUpdate
import io.qent.broxy.agents.AgentFileSystemAccess
import io.qent.broxy.agents.AgentFileSystemSettings
import io.qent.broxy.agents.AgentGeneratedDraft
import io.qent.broxy.agents.AgentGenerationCommand
import io.qent.broxy.agents.AgentGenerationProgressStage
import io.qent.broxy.agents.AgentLlmConfig
import io.qent.broxy.agents.AgentManualLaunchDefaults
import io.qent.broxy.agents.AgentProviderConfig
import io.qent.broxy.agents.AgentProviderModelCache
import io.qent.broxy.agents.AgentProviderSettings
import io.qent.broxy.agents.AgentRunActionEntry
import io.qent.broxy.agents.AgentRunActionType
import io.qent.broxy.agents.AgentRunCommand
import io.qent.broxy.agents.AgentRunDetails
import io.qent.broxy.agents.AgentRunDialogueEntry
import io.qent.broxy.agents.AgentRunDialogueRole
import io.qent.broxy.agents.AgentRunStatus
import io.qent.broxy.agents.AgentRunSummary
import io.qent.broxy.agents.AgentRunTrigger
import io.qent.broxy.agents.AgentRuntime
import io.qent.broxy.agents.AgentSchedule
import io.qent.broxy.agents.AgentScheduleCommand
import io.qent.broxy.agents.AgentServerCapabilitySummary
import io.qent.broxy.agents.AgentService
import io.qent.broxy.agents.DEFAULT_ANTHROPIC_BASE_URL
import io.qent.broxy.agents.DEFAULT_LM_STUDIO_BASE_URL
import io.qent.broxy.agents.DEFAULT_OPENAI_BASE_URL
import io.qent.broxy.agents.LlmProvider
import io.qent.broxy.core.models.AgentToolReference
import io.qent.broxy.core.models.PromptReference
import io.qent.broxy.core.models.ResourceReference
import io.qent.broxy.core.models.ToolReference
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.ui.adapter.models.UiAgent
import io.qent.broxy.ui.adapter.models.UiAgentAiFeaturesSettings
import io.qent.broxy.ui.adapter.models.UiAgentCodexConfig
import io.qent.broxy.ui.adapter.models.UiAgentCodexGlobalSettings
import io.qent.broxy.ui.adapter.models.UiAgentCodexReasoningEffort
import io.qent.broxy.ui.adapter.models.UiAgentDraft
import io.qent.broxy.ui.adapter.models.UiAgentFileSystemAccess
import io.qent.broxy.ui.adapter.models.UiAgentFileSystemSettings
import io.qent.broxy.ui.adapter.models.UiAgentGenerationStage
import io.qent.broxy.ui.adapter.models.UiAgentLlmConfig
import io.qent.broxy.ui.adapter.models.UiAgentManualLaunchDefaults
import io.qent.broxy.ui.adapter.models.UiAgentOperation
import io.qent.broxy.ui.adapter.models.UiAgentProviderConfig
import io.qent.broxy.ui.adapter.models.UiAgentProviderModelCache
import io.qent.broxy.ui.adapter.models.UiAgentProviderSettings
import io.qent.broxy.ui.adapter.models.UiAgentRunStatus
import io.qent.broxy.ui.adapter.models.UiAgentRunTrigger
import io.qent.broxy.ui.adapter.models.UiAgentRuntime
import io.qent.broxy.ui.adapter.models.UiAgentSchedule
import io.qent.broxy.ui.adapter.models.UiAgentToolRef
import io.qent.broxy.ui.adapter.models.UiCapabilityArgument
import io.qent.broxy.ui.adapter.models.UiGeneratedAgentDraft
import io.qent.broxy.ui.adapter.models.UiLlmProvider
import io.qent.broxy.ui.adapter.models.UiPromptRef
import io.qent.broxy.ui.adapter.models.UiResourceRef
import io.qent.broxy.ui.adapter.models.UiRunActionEntry
import io.qent.broxy.ui.adapter.models.UiRunActionType
import io.qent.broxy.ui.adapter.models.UiRunDetails
import io.qent.broxy.ui.adapter.models.UiRunDialogueEntry
import io.qent.broxy.ui.adapter.models.UiRunDialogueRole
import io.qent.broxy.ui.adapter.models.UiRunSummary
import io.qent.broxy.ui.adapter.models.UiSchedulePreview
import io.qent.broxy.ui.adapter.models.UiServerCapsSnapshot
import io.qent.broxy.ui.adapter.models.UiToolRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AgentGatewayJvm(
    private val service: AgentService,
    private val configurationRepository: ConfigurationRepository? = null,
) : AgentGateway {
    override val updates: Flow<UiAgentExecutionUpdate> =
        service.updates.map { update ->
            when (update) {
                is AgentExecutionUpdate.Running ->
                    UiAgentExecutionUpdate.Running(update.agentId, update.startedAtEpochMillis)
                is AgentExecutionUpdate.Operation ->
                    UiAgentExecutionUpdate.Operation(update.agentId, update.operation.toUi())
                is AgentExecutionUpdate.Finished ->
                    UiAgentExecutionUpdate.Finished(update.agentId, update.run.toUi())
            }
        }

    override fun start() {
        service.start()
    }

    override fun stop() {
        service.stop()
    }

    override suspend fun listAgents(): List<UiAgent> {
        val running = service.runningAgents()
        return service.listAgents().map { it.toUiSummary(runningSinceEpochMillis = running[it.id]) }
    }

    override suspend fun getAgentDraft(id: String): UiAgentDraft? = service.loadAgent(id)?.toUiDraft()

    override suspend fun upsertAgent(draft: UiAgentDraft): Result<UiAgent> {
        val originalId = draft.originalId?.trim()?.takeIf { it.isNotBlank() }
        val target = draft.toCoreAgent()
        val saved = service.upsertAgent(target)
        if (saved.isFailure) {
            return saved.map { it.toUiSummary(runningSinceEpochMillis = null) }
        }

        if (originalId != null && originalId != target.id) {
            val migration =
                migrateAgentToolReferences(
                    oldId = originalId,
                    newId = target.id,
                )
            if (migration.isFailure) {
                return Result.failure(checkNotNull(migration.exceptionOrNull()))
            }
            service.deleteAgent(originalId)
        }

        return saved.map { it.toUiSummary(runningSinceEpochMillis = service.runningAgents()[it.id]) }
    }

    override suspend fun deleteAgent(id: String): Result<Unit> = service.deleteAgent(id)

    override suspend fun reorderAgents(agentIds: List<String>): Result<List<UiAgent>> {
        val running = service.runningAgents()
        return service
            .reorderAgents(agentIds)
            .map { agents ->
                agents.map { agent -> agent.toUiSummary(runningSinceEpochMillis = running[agent.id]) }
            }
    }

    override suspend fun runAgentNow(
        id: String,
        prompt: String,
        llm: UiAgentLlmConfig,
        fileSystem: UiAgentFileSystemSettings,
        runtime: UiAgentRuntime,
        codex: UiAgentCodexConfig?,
    ): Result<Unit> =
        service.runAgent(
            AgentRunCommand(
                agentId = id,
                prompt = prompt,
                runtime = runtime.toCore(),
                llm = llm.toCore(),
                codex = codex?.toCore(),
                fileSystem = fileSystem.toCore(),
                trigger = AgentRunTrigger.MANUAL,
            ),
        )

    override suspend fun stopAgent(id: String): Result<Unit> = service.stopAgent(id)

    override suspend fun saveSchedule(
        id: String,
        cron: String,
        prompt: String,
        timezoneId: String,
        llm: UiAgentLlmConfig,
        fileSystem: UiAgentFileSystemSettings,
        runtime: UiAgentRuntime,
        codex: UiAgentCodexConfig?,
    ): Result<UiAgent> =
        service
            .saveSchedule(
                AgentScheduleCommand(
                    agentId = id,
                    cron = cron,
                    prompt = prompt,
                    timezoneId = timezoneId,
                    runtime = runtime.toCore(),
                    llm = llm.toCore(),
                    codex = codex?.toCore(),
                    fileSystem = fileSystem.toCore(),
                ),
            ).map { it.toUiSummary(runningSinceEpochMillis = service.runningAgents()[it.id]) }

    override suspend fun clearSchedule(id: String): Result<UiAgent> =
        service
            .clearSchedule(id)
            .map { it.toUiSummary(runningSinceEpochMillis = service.runningAgents()[it.id]) }

    override suspend fun listRuns(): List<UiRunSummary> = service.listRuns().map { it.toUi() }

    override suspend fun loadRun(runId: String): UiRunDetails? = service.loadRun(runId)?.toUi()

    override suspend fun runningAgentIds(): Set<String> = service.runningAgentIds()

    override suspend fun runningAgents(): Map<String, Long> = service.runningAgents()

    override suspend fun loadProviderSettings(): UiAgentProviderSettings = service.loadProviderSettings().toUi(service)

    override suspend fun saveProviderSettings(settings: UiAgentProviderSettings): Result<UiAgentProviderSettings> =
        service.saveProviderSettings(settings.toCore()).map { it.toUi(service) }

    override suspend fun saveProviderApiKey(
        provider: UiLlmProvider,
        apiKey: String,
    ): Result<Unit> = service.saveProviderApiKey(provider.toCore(), apiKey)

    override suspend fun clearProviderApiKey(provider: UiLlmProvider): Result<Unit> = service.clearProviderApiKey(provider.toCore())

    override suspend fun listProviderModels(
        provider: UiLlmProvider,
        forceRefresh: Boolean,
    ): Result<List<String>> = service.listProviderModels(provider.toCore(), forceRefresh)

    override suspend fun listCodexModels(forceRefresh: Boolean): Result<List<String>> = service.listCodexModels(forceRefresh)

    override suspend fun generateAgentDescription(
        draft: UiAgentDraft,
        capabilitySnapshots: List<UiServerCapsSnapshot>,
    ): Result<String> = service.generateAgentDescription(mapDescriptionGenerationCommand(draft, capabilitySnapshots))

    override suspend fun generateAgentFromRequest(
        request: String,
        capabilitySnapshots: List<UiServerCapsSnapshot>,
        aiFeaturesOverride: UiAgentAiFeaturesSettings?,
        onProgress: (UiAgentGenerationStage) -> Unit,
    ): Result<UiGeneratedAgentDraft> =
        service
            .generateAgent(
                AgentGenerationCommand(
                    userRequest = request,
                    capabilityContext = capabilitySnapshots.map { it.toCore() },
                    aiFeaturesOverride = aiFeaturesOverride?.toCore(),
                    onProgress = { stage -> onProgress(stage.toUi()) },
                ),
            ).map { it.toUi() }

    override suspend fun previewSchedule(
        cron: String,
        timezoneId: String,
        limit: Int,
    ): Result<UiSchedulePreview> = previewScheduleFromCron(cron, timezoneId, limit)

    private fun migrateAgentToolReferences(
        oldId: String,
        newId: String,
    ): Result<Unit> =
        runCatching {
            val normalizedOldId = oldId.trim()
            val normalizedNewId = newId.trim()
            if (normalizedOldId.isBlank() || normalizedOldId == normalizedNewId) {
                return@runCatching
            }

            val presets = configurationRepository?.listPresets().orEmpty()
            presets.forEach { preset ->
                val updatedRefs =
                    preset.agentTools.map { ref ->
                        if (ref.agentId == normalizedOldId) {
                            ref.copy(agentId = normalizedNewId)
                        } else {
                            ref
                        }
                    }
                if (updatedRefs != preset.agentTools) {
                    configurationRepository?.savePreset(preset.copy(agentTools = updatedRefs))
                }
            }

            service.listAgents().forEach { agent ->
                val updatedRefs =
                    agent.agentTools.map { ref ->
                        if (ref.agentId == normalizedOldId) {
                            ref.copy(agentId = normalizedNewId)
                        } else {
                            ref
                        }
                    }
                if (updatedRefs != agent.agentTools) {
                    service
                        .upsertAgent(agent.copy(agentTools = updatedRefs))
                        .getOrElse { throw it }
                }
            }
        }
}

internal fun mapDescriptionGenerationCommand(
    draft: UiAgentDraft,
    capabilitySnapshots: List<UiServerCapsSnapshot>,
): AgentDescriptionGenerationCommand =
    AgentDescriptionGenerationCommand(
        draft =
            draft
                .toCoreAgent()
                .copy(id = draft.id.trim().ifBlank { "draft-agent" }),
        capabilityContext = capabilitySnapshots.map { it.toCore() },
    )

private fun AgentGeneratedDraft.toUi(): UiGeneratedAgentDraft =
    UiGeneratedAgentDraft(
        name = agentName,
        description = description,
        systemPrompt = systemPrompt,
        tools = tools.map { it.toUi() },
        prompts = prompts.map { it.toUi() },
        resources = resources.map { it.toUi() },
    )

private fun AgentGenerationProgressStage.toUi(): UiAgentGenerationStage =
    when (this) {
        AgentGenerationProgressStage.SELECTING_SERVERS -> UiAgentGenerationStage.SELECTING_SERVERS
        AgentGenerationProgressStage.SELECTING_CAPABILITIES -> UiAgentGenerationStage.SELECTING_CAPABILITIES
        AgentGenerationProgressStage.FINALIZING_AGENT -> UiAgentGenerationStage.FINALIZING_AGENT
    }

private fun AgentDefinition.toUiSummary(runningSinceEpochMillis: Long?): UiAgent =
    UiAgent(
        id = id,
        name = name,
        systemPrompt = systemPrompt,
        description = description,
        tools = tools.map { it.toUi() },
        agentTools = agentTools.map { it.toUi() },
        prompts = prompts.orEmpty().map { it.toUi() },
        resources = resources.orEmpty().map { it.toUi() },
        promptsConfigured = prompts != null,
        resourcesConfigured = resources != null,
        toolsCount = tools.count { it.enabled },
        promptsCount = prompts?.count { it.enabled } ?: 0,
        resourcesCount = resources?.count { it.enabled } ?: 0,
        orderIndex = orderIndex,
        schedule = schedule?.toUi(),
        manualLaunchDefaults = manualLaunchDefaults?.toUi(),
        latestFailedRun = null,
        isRunning = runningSinceEpochMillis != null,
        runningSinceEpochMillis = runningSinceEpochMillis,
    )

private fun AgentDefinition.toUiDraft(): UiAgentDraft =
    UiAgentDraft(
        id = id,
        name = name,
        systemPrompt = systemPrompt,
        description = description,
        tools = tools.map { it.toUi() },
        agentTools = agentTools.map { it.toUi() },
        prompts = prompts.orEmpty().map { it.toUi() },
        resources = resources.orEmpty().map { it.toUi() },
        promptsConfigured = prompts != null,
        resourcesConfigured = resources != null,
        originalId = id,
        orderIndex = orderIndex,
        schedule = schedule?.toUi(),
        manualLaunchDefaults = manualLaunchDefaults?.toUi(),
    )

private fun UiAgentDraft.toCoreAgent(): AgentDefinition =
    AgentDefinition(
        id = id.trim(),
        name = name.trim(),
        systemPrompt = systemPrompt.trim(),
        description = description?.trim()?.takeIf { it.isNotBlank() },
        tools = tools.map { it.toCore() },
        agentTools = agentTools.map { it.toCore() },
        prompts = if (promptsConfigured) prompts.map { it.toCore() } else null,
        resources = if (resourcesConfigured) resources.map { it.toCore() } else null,
        orderIndex = orderIndex,
        schedule = schedule?.toCore(),
        manualLaunchDefaults = manualLaunchDefaults?.toCore(),
    )

private fun AgentProviderSettings.toUi(service: AgentService): UiAgentProviderSettings =
    UiAgentProviderSettings(
        enableCodexProvider = enableCodexProvider,
        agentsDirectoryPath = agentsDirectoryPath.orEmpty(),
        openAi =
            UiAgentProviderConfig(
                baseUrl = openAi.baseUrl.orEmpty().ifBlank { DEFAULT_OPENAI_BASE_URL },
                hasSavedApiKey = service.hasProviderApiKey(LlmProvider.OPENAI),
            ),
        anthropic =
            UiAgentProviderConfig(
                baseUrl = anthropic.baseUrl.orEmpty().ifBlank { DEFAULT_ANTHROPIC_BASE_URL },
                hasSavedApiKey = service.hasProviderApiKey(LlmProvider.ANTHROPIC),
            ),
        lmStudio =
            UiAgentProviderConfig(
                baseUrl = lmStudio.baseUrl.orEmpty().ifBlank { DEFAULT_LM_STUDIO_BASE_URL },
                hasSavedApiKey = false,
            ),
        modelCache = modelCache.toUi(),
        codex = codex.toUi(),
        aiFeatures = aiFeatures.toUi(),
    )

private fun UiAgentProviderSettings.toCore(): AgentProviderSettings =
    AgentProviderSettings(
        enableCodexProvider = enableCodexProvider,
        agentsDirectoryPath = agentsDirectoryPath.trim().ifBlank { null },
        openAi = AgentProviderConfig(baseUrl = openAi.baseUrl.trim().takeIf { it.isNotBlank() }),
        anthropic = AgentProviderConfig(baseUrl = anthropic.baseUrl.trim().takeIf { it.isNotBlank() }),
        lmStudio = AgentProviderConfig(baseUrl = lmStudio.baseUrl.trim().takeIf { it.isNotBlank() }),
        modelCache = modelCache.toCore(),
        codex = codex.toCore(),
        aiFeatures = aiFeatures.toCore(),
    )

private fun AgentProviderModelCache.toUi(): UiAgentProviderModelCache =
    UiAgentProviderModelCache(
        openAi = openAi,
        anthropic = anthropic,
        lmStudio = lmStudio,
        codex = codex,
        codexFetchedAtEpochMillis = codexFetchedAtEpochMillis,
    )

private fun UiAgentProviderModelCache.toCore(): AgentProviderModelCache =
    AgentProviderModelCache(
        openAi = openAi,
        anthropic = anthropic,
        lmStudio = lmStudio,
        codex = codex,
        codexFetchedAtEpochMillis = codexFetchedAtEpochMillis,
    )

private fun AgentLlmConfig.toUi(): UiAgentLlmConfig =
    UiAgentLlmConfig(
        provider = provider.toUi(),
        model = model,
        temperature = temperature,
    )

private fun UiAgentLlmConfig.toCore(): AgentLlmConfig =
    AgentLlmConfig(
        provider = provider.toCore(),
        model = model.trim(),
        temperature = temperature,
    )

private fun AgentAiFeaturesSettings.toUi(): UiAgentAiFeaturesSettings =
    UiAgentAiFeaturesSettings(
        enabled = enabled,
        runtime = runtime.toUi(),
        llm = llm.toUi(),
        codex = codex.toUi(),
    )

private fun UiAgentAiFeaturesSettings.toCore(): AgentAiFeaturesSettings =
    AgentAiFeaturesSettings(
        enabled = enabled,
        runtime = runtime.toCore(),
        llm = llm.toCore(),
        codex = codex.toCore(),
    )

private fun AgentSchedule.toUi(): UiAgentSchedule =
    UiAgentSchedule(
        cron = cron,
        prompt = prompt,
        timezoneId = timezoneId,
        runtime = runtime.toUi(),
        llm = llm.toUi(),
        codex = codex?.toUi(),
        fileSystem = fileSystem.toUi(),
    )

private fun AgentManualLaunchDefaults.toUi(): UiAgentManualLaunchDefaults =
    UiAgentManualLaunchDefaults(
        prompt = prompt,
        runtime = runtime.toUi(),
        llm = llm.toUi(),
        codex = codex?.toUi(),
        fileSystem = fileSystem.toUi(),
    )

private fun UiAgentManualLaunchDefaults.toCore(): AgentManualLaunchDefaults =
    AgentManualLaunchDefaults(
        prompt = prompt.trim(),
        runtime = runtime.toCore(),
        llm = llm.toCore(),
        codex = codex?.toCore(),
        fileSystem = fileSystem.toCore(),
    )

private fun UiAgentSchedule.toCore(): AgentSchedule =
    AgentSchedule(
        cron = cron,
        prompt = prompt,
        timezoneId = timezoneId,
        runtime = runtime.toCore(),
        llm = llm.toCore(),
        codex = codex?.toCore(),
        fileSystem = fileSystem.toCore(),
    )

private fun AgentFileSystemSettings.toUi(): UiAgentFileSystemSettings =
    UiAgentFileSystemSettings(
        path = path,
        access = access.toUi(),
    )

private fun UiAgentFileSystemSettings.toCore(): AgentFileSystemSettings =
    AgentFileSystemSettings(
        path = path.trim(),
        access = access.toCore(),
    )

private fun AgentFileSystemAccess.toUi(): UiAgentFileSystemAccess =
    when (this) {
        AgentFileSystemAccess.NONE -> UiAgentFileSystemAccess.NONE
        AgentFileSystemAccess.READ_ONLY -> UiAgentFileSystemAccess.READ_ONLY
        AgentFileSystemAccess.READ_WRITE -> UiAgentFileSystemAccess.READ_WRITE
    }

private fun UiAgentFileSystemAccess.toCore(): AgentFileSystemAccess =
    when (this) {
        UiAgentFileSystemAccess.NONE -> AgentFileSystemAccess.NONE
        UiAgentFileSystemAccess.READ_ONLY -> AgentFileSystemAccess.READ_ONLY
        UiAgentFileSystemAccess.READ_WRITE -> AgentFileSystemAccess.READ_WRITE
    }

private fun AgentRunSummary.toUi(): UiRunSummary =
    UiRunSummary(
        runId = runId,
        agentId = agentId,
        agentName = agentName,
        trigger = trigger.toUi(),
        status = status.toUi(),
        runtime = runtime.toUi(),
        prompt = prompt,
        response = response,
        errorMessage = errorMessage,
        startedAtEpochMillis = startedAtEpochMillis,
        finishedAtEpochMillis = finishedAtEpochMillis,
    )

private fun AgentRunDetails.toUi(): UiRunDetails =
    UiRunDetails(
        summary = summary.toUi(),
        systemPrompt = systemPrompt,
        llm = llm.toUi(),
        codex = codex?.toUi(),
        fileSystem = fileSystem.toUi(),
        dialogue = dialogue.map { it.toUi() },
        actions = actions.map { it.toUi() },
    )

private fun AgentRunDialogueEntry.toUi(): UiRunDialogueEntry =
    UiRunDialogueEntry(
        role = role.toUi(),
        content = content,
        step = step,
        serverId = serverId,
        toolName = toolName,
        timestampEpochMillis = timestampEpochMillis,
    )

private fun AgentRunActionEntry.toUi(): UiRunActionEntry =
    UiRunActionEntry(
        type = type.toUi(),
        step = step,
        serverId = serverId,
        toolName = toolName,
        requestPayload = requestPayload,
        responsePayload = responsePayload,
        errorMessage = errorMessage,
        message = message,
        timestampEpochMillis = timestampEpochMillis,
    )

private fun LlmProvider.toUi(): UiLlmProvider =
    when (this) {
        LlmProvider.OPENAI -> UiLlmProvider.OPENAI
        LlmProvider.ANTHROPIC -> UiLlmProvider.ANTHROPIC
        LlmProvider.LM_STUDIO -> UiLlmProvider.LM_STUDIO
    }

private fun UiLlmProvider.toCore(): LlmProvider =
    when (this) {
        UiLlmProvider.OPENAI -> LlmProvider.OPENAI
        UiLlmProvider.ANTHROPIC -> LlmProvider.ANTHROPIC
        UiLlmProvider.LM_STUDIO -> LlmProvider.LM_STUDIO
    }

private fun AgentRuntime.toUi(): UiAgentRuntime =
    when (this) {
        AgentRuntime.LANGCHAIN -> UiAgentRuntime.LANGCHAIN
        AgentRuntime.CODEX_CLI -> UiAgentRuntime.CODEX_CLI
    }

private fun UiAgentRuntime.toCore(): AgentRuntime =
    when (this) {
        UiAgentRuntime.LANGCHAIN -> AgentRuntime.LANGCHAIN
        UiAgentRuntime.CODEX_CLI -> AgentRuntime.CODEX_CLI
    }

private fun AgentCodexConfig.toUi(): UiAgentCodexConfig =
    UiAgentCodexConfig(
        model = model,
        reasoningEffort = reasoningEffort.toUi(),
        webSearch = webSearch,
    )

private fun UiAgentCodexConfig.toCore(): AgentCodexConfig =
    AgentCodexConfig(
        model = model.trim(),
        reasoningEffort = reasoningEffort.toCore(),
        webSearch = webSearch,
    )

private fun AgentCodexReasoningEffort.toUi(): UiAgentCodexReasoningEffort =
    when (this) {
        AgentCodexReasoningEffort.LOW -> UiAgentCodexReasoningEffort.LOW
        AgentCodexReasoningEffort.MEDIUM -> UiAgentCodexReasoningEffort.MEDIUM
        AgentCodexReasoningEffort.HIGH -> UiAgentCodexReasoningEffort.HIGH
    }

private fun UiAgentCodexReasoningEffort.toCore(): AgentCodexReasoningEffort =
    when (this) {
        UiAgentCodexReasoningEffort.LOW -> AgentCodexReasoningEffort.LOW
        UiAgentCodexReasoningEffort.MEDIUM -> AgentCodexReasoningEffort.MEDIUM
        UiAgentCodexReasoningEffort.HIGH -> AgentCodexReasoningEffort.HIGH
    }

private fun AgentCodexGlobalSettings.toUi(): UiAgentCodexGlobalSettings =
    UiAgentCodexGlobalSettings(
        command = command,
        portRangeStart = portRangeStart,
        portRangeEnd = portRangeEnd,
    )

private fun UiAgentCodexGlobalSettings.toCore(): AgentCodexGlobalSettings =
    AgentCodexGlobalSettings(
        command = command.trim(),
        portRangeStart = portRangeStart,
        portRangeEnd = portRangeEnd,
    )

private fun AgentRunStatus.toUi(): UiAgentRunStatus =
    when (this) {
        AgentRunStatus.SUCCESS -> UiAgentRunStatus.SUCCESS
        AgentRunStatus.FAILED -> UiAgentRunStatus.FAILED
        AgentRunStatus.SKIPPED -> UiAgentRunStatus.SKIPPED
    }

private fun AgentRunTrigger.toUi(): UiAgentRunTrigger =
    when (this) {
        AgentRunTrigger.MANUAL -> UiAgentRunTrigger.MANUAL
        AgentRunTrigger.SCHEDULED -> UiAgentRunTrigger.SCHEDULED
    }

private fun AgentRunDialogueRole.toUi(): UiRunDialogueRole =
    when (this) {
        AgentRunDialogueRole.SYSTEM -> UiRunDialogueRole.SYSTEM
        AgentRunDialogueRole.USER -> UiRunDialogueRole.USER
        AgentRunDialogueRole.ASSISTANT -> UiRunDialogueRole.ASSISTANT
        AgentRunDialogueRole.TOOL -> UiRunDialogueRole.TOOL
    }

private fun AgentRunActionType.toUi(): UiRunActionType =
    when (this) {
        AgentRunActionType.PREPARING_RUN -> UiRunActionType.PREPARING_RUN
        AgentRunActionType.LOADING_CAPABILITIES -> UiRunActionType.LOADING_CAPABILITIES
        AgentRunActionType.LLM_REQUEST -> UiRunActionType.LLM_REQUEST
        AgentRunActionType.LLM_THINKING -> UiRunActionType.LLM_THINKING
        AgentRunActionType.LLM_RESPONSE_GENERATION -> UiRunActionType.LLM_RESPONSE_GENERATION
        AgentRunActionType.TOOL_CALL -> UiRunActionType.TOOL_CALL
        AgentRunActionType.TOOL_RESULT -> UiRunActionType.TOOL_RESULT
        AgentRunActionType.RUNTIME_EVENT -> UiRunActionType.RUNTIME_EVENT
    }

private fun AgentExecutionOperation.toUi(): UiAgentOperation =
    when (this) {
        AgentExecutionOperation.PreparingRun -> UiAgentOperation.PreparingRun
        AgentExecutionOperation.LoadingCapabilities -> UiAgentOperation.LoadingCapabilities
        is AgentExecutionOperation.LlmRequest -> UiAgentOperation.LlmRequest(step = step)
        is AgentExecutionOperation.LlmThinking -> UiAgentOperation.LlmThinking(step = step)
        is AgentExecutionOperation.LlmResponseGeneration -> UiAgentOperation.LlmResponseGeneration(step = step)
        is AgentExecutionOperation.ToolExecution ->
            UiAgentOperation.ToolExecution(
                serverId = serverId,
                toolName = toolName,
                step = step,
            )
    }

private fun UiServerCapsSnapshot.toCore(): AgentServerCapabilitySummary =
    AgentServerCapabilitySummary(
        serverId = serverId,
        serverName = name,
        tools =
            tools.map { tool ->
                AgentCapabilityToolSummary(
                    name = tool.name,
                    description = tool.description,
                    arguments = tool.arguments.map { it.toCore() },
                )
            },
        prompts =
            prompts.map { prompt ->
                AgentCapabilityPromptSummary(
                    name = prompt.name,
                    description = prompt.description,
                    arguments = prompt.arguments.map { it.toCore() },
                )
            },
        resources =
            resources.map { resource ->
                AgentCapabilityResourceSummary(
                    key = resource.key,
                    name = resource.name,
                    description = resource.description,
                    arguments = resource.arguments.map { it.toCore() },
                )
            },
    )

private fun UiCapabilityArgument.toCore(): AgentCapabilityArgumentSummary =
    AgentCapabilityArgumentSummary(
        name = name,
        type = type,
        required = required,
    )

private fun ToolReference.toUi(): UiToolRef =
    UiToolRef(
        serverId = serverId,
        toolName = toolName,
        enabled = enabled,
    )

private fun UiToolRef.toCore(): ToolReference =
    ToolReference(
        serverId = serverId,
        toolName = toolName,
        enabled = enabled,
    )

private fun AgentToolReference.toUi(): UiAgentToolRef =
    UiAgentToolRef(
        agentId = agentId,
        enabled = enabled,
    )

private fun UiAgentToolRef.toCore(): AgentToolReference =
    AgentToolReference(
        agentId = agentId,
        enabled = enabled,
    )

private fun PromptReference.toUi(): UiPromptRef =
    UiPromptRef(
        serverId = serverId,
        promptName = promptName,
        enabled = enabled,
    )

private fun UiPromptRef.toCore(): PromptReference =
    PromptReference(
        serverId = serverId,
        promptName = promptName,
        enabled = enabled,
    )

private fun ResourceReference.toUi(): UiResourceRef =
    UiResourceRef(
        serverId = serverId,
        resourceKey = resourceKey,
        enabled = enabled,
    )

private fun UiResourceRef.toCore(): ResourceReference =
    ResourceReference(
        serverId = serverId,
        resourceKey = resourceKey,
        enabled = enabled,
    )
