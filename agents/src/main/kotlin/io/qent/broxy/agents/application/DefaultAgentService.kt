package io.qent.broxy.agents.application

import io.qent.broxy.agents.AgentCapabilityArgumentSummary
import io.qent.broxy.agents.AgentCodexConfig
import io.qent.broxy.agents.AgentDefinition
import io.qent.broxy.agents.AgentDescriptionGenerationCommand
import io.qent.broxy.agents.AgentExecutionOperation
import io.qent.broxy.agents.AgentExecutionRequest
import io.qent.broxy.agents.AgentExecutionUpdate
import io.qent.broxy.agents.AgentExecutor
import io.qent.broxy.agents.AgentFileSystemAccess
import io.qent.broxy.agents.AgentFileSystemSettings
import io.qent.broxy.agents.AgentGeneratedDraft
import io.qent.broxy.agents.AgentGenerationCommand
import io.qent.broxy.agents.AgentGenerationProgressStage
import io.qent.broxy.agents.AgentLlmConfig
import io.qent.broxy.agents.AgentManualLaunchDefaults
import io.qent.broxy.agents.AgentProviderModelCache
import io.qent.broxy.agents.AgentProviderSettings
import io.qent.broxy.agents.AgentProviderSettingsRepository
import io.qent.broxy.agents.AgentRepository
import io.qent.broxy.agents.AgentRunActionEntry
import io.qent.broxy.agents.AgentRunActionType
import io.qent.broxy.agents.AgentRunCommand
import io.qent.broxy.agents.AgentRunDetails
import io.qent.broxy.agents.AgentRunDialogueEntry
import io.qent.broxy.agents.AgentRunRepository
import io.qent.broxy.agents.AgentRunStatus
import io.qent.broxy.agents.AgentRunSummary
import io.qent.broxy.agents.AgentRunTrigger
import io.qent.broxy.agents.AgentRuntime
import io.qent.broxy.agents.AgentSchedule
import io.qent.broxy.agents.AgentScheduleCommand
import io.qent.broxy.agents.AgentScheduler
import io.qent.broxy.agents.AgentSecretsStore
import io.qent.broxy.agents.AgentServerCapabilitySummary
import io.qent.broxy.agents.AgentService
import io.qent.broxy.agents.DEFAULT_AGENT_WORKSPACE_PATH
import io.qent.broxy.agents.DEFAULT_CODEX_MODEL
import io.qent.broxy.agents.LlmProvider
import io.qent.broxy.agents.application.scheduler.CronScheduleValidator
import io.qent.broxy.agents.runtime.models.AgentModelCatalog
import io.qent.broxy.agents.runtime.models.CodexCliModelCatalog
import io.qent.broxy.agents.runtime.models.CodexModelCatalog
import io.qent.broxy.agents.runtime.models.HttpAgentModelCatalog
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.PromptReference
import io.qent.broxy.core.models.ResourceReference
import io.qent.broxy.core.models.ToolReference
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger
import io.qent.broxy.core.utils.errorJson
import io.qent.broxy.core.utils.infoJson
import io.qent.broxy.core.utils.warnJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import java.util.UUID

private const val CODEX_MODEL_CACHE_TTL_MILLIS = 24 * 60 * 60 * 1000L
private const val AGENT_DESCRIPTION_WORD_MIN = 30
private const val AGENT_DESCRIPTION_WORD_MAX = 36
private const val AGENT_DESCRIPTION_RUNTIME_AGENT_ID = "agent-description-generator"
private const val AGENT_DESCRIPTION_RUNTIME_AGENT_NAME = "Agent Description Generator"
private const val AGENT_GENERATION_RUNTIME_AGENT_ID = "agent-generator"
private const val AGENT_GENERATION_RUNTIME_AGENT_NAME = "Agent Generator"
private const val AGENT_GENERATION_MIN_CAPABILITIES = 1
private const val AGENT_GENERATION_SERVER_SELECTION_PROMPT_PATH = "/prompts/agent_generation_server_selection.md"
private const val AGENT_GENERATION_SERVER_CAPABILITIES_PROMPT_PATH = "/prompts/agent_generation_server_capabilities.md"
private const val AGENT_GENERATION_FINALIZE_PROMPT_PATH = "/prompts/agent_generation_finalize.md"
private const val AGENT_SYSTEM_PROMPT_TEMPLATE_PATH = "/prompts/agent_system_prompt_template.md"
private const val AGENT_GENERATION_EVENT_STARTED = "agent.generation.started"
private const val AGENT_GENERATION_EVENT_STAGE_STARTED = "agent.generation.stage.started"
private const val AGENT_GENERATION_EVENT_STAGE_SUCCEEDED = "agent.generation.stage.succeeded"
private const val AGENT_GENERATION_EVENT_SUCCEEDED = "agent.generation.succeeded"
private const val AGENT_GENERATION_EVENT_FAILED = "agent.generation.failed"
private const val MARKDOWN_CODE_FENCE_MIN_LINES = 3
private typealias AgentCapabilityArguments = List<AgentCapabilityArgumentSummary>

private val AGENT_DESCRIPTION_WORD_REGEX = Regex("[A-Za-z]+(?:[-'][A-Za-z]+)*")
private val AGENT_DESCRIPTION_WHITESPACE_REGEX = Regex("\\s+")
private val AGENT_DESCRIPTION_SYSTEM_PROMPT =
    """
    You write concise English descriptions for software agents.
    Output exactly one plain sentence with 30-36 words.
    Keep it practical: what the agent does, what capabilities it can use, and when to invoke it.
    Do not add markdown, lists, quotes, labels, or extra commentary.
    Use only facts from the provided input.
    """.trimIndent()
private val AGENT_GENERATION_JSON_CONFIG =
    Json {
        ignoreUnknownKeys = false
    }

@Suppress("LongParameterList", "TooManyFunctions", "LargeClass")
class DefaultAgentService(
    private val agentRepository: AgentRepository,
    private val runRepository: AgentRunRepository,
    private val settingsRepository: AgentProviderSettingsRepository,
    private val secretsStore: AgentSecretsStore,
    private val configurationProvider: () -> McpServersConfig,
    private val executor: AgentExecutor,
    private val scheduler: AgentScheduler,
    private val modelCatalog: AgentModelCatalog = HttpAgentModelCatalog(),
    private val codexModelCatalog: CodexModelCatalog = CodexCliModelCatalog(),
    private val now: () -> Long = { System.currentTimeMillis() },
    private val logger: Logger = ConsoleLogger,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AgentService {
    private val updatesFlow = MutableSharedFlow<AgentExecutionUpdate>(extraBufferCapacity = 128)
    private val running = linkedMapOf<String, Long>()
    private val runningLock = Any()
    private val serviceScope = scope
    private val jobs = mutableMapOf<String, Job>()
    private val generationServerSelectionPromptTemplate by lazy {
        loadPromptTemplate(AGENT_GENERATION_SERVER_SELECTION_PROMPT_PATH)
    }
    private val generationServerCapabilitiesPromptTemplate by lazy {
        loadPromptTemplate(AGENT_GENERATION_SERVER_CAPABILITIES_PROMPT_PATH)
    }
    private val generationFinalizePromptTemplate by lazy {
        loadPromptTemplate(AGENT_GENERATION_FINALIZE_PROMPT_PATH)
    }
    private val generationSystemPromptTemplate by lazy {
        loadPromptTemplate(AGENT_SYSTEM_PROMPT_TEMPLATE_PATH)
    }

    override val updates: Flow<AgentExecutionUpdate> = updatesFlow

    override fun start() {
        val schedules =
            runCatching {
                agentRepository
                    .listAgents()
                    .mapNotNull { agent ->
                        val schedule = agent.schedule ?: return@mapNotNull null
                        agent.id to schedule
                    }.toMap()
            }.getOrDefault(emptyMap())
        scheduler.start(schedules, ::onScheduledTrigger)
    }

    override fun stop() {
        scheduler.stop()
        synchronized(runningLock) {
            jobs.values.forEach { it.cancel() }
            jobs.clear()
            running.clear()
        }
    }

    override fun listAgents(): List<AgentDefinition> =
        runCatching { agentRepository.listAgents() }
            .onFailure { logger.warn("Failed to list agents: ${it.message}", it) }
            .getOrDefault(emptyList())

    override fun loadAgent(id: String): AgentDefinition? =
        runCatching { agentRepository.loadAgent(id) }
            .onFailure { logger.warn("Failed to load agent '$id': ${it.message}") }
            .getOrNull()

    override fun upsertAgent(agent: AgentDefinition): Result<AgentDefinition> =
        runCatching {
            val existing = runCatching { agentRepository.loadAgent(agent.id) }.getOrNull()
            val resolvedOrderIndex =
                (existing?.orderIndex ?: agent.orderIndex)
                    .coerceAtLeast(0)
            val merged =
                agent.copy(
                    orderIndex = resolvedOrderIndex,
                    manualLaunchDefaults = agent.manualLaunchDefaults ?: existing?.manualLaunchDefaults,
                )
            agentRepository.saveAgent(merged)
            scheduler.updateSchedule(merged.id, merged.schedule)
            merged
        }.onFailure {
            logger.warn("Failed to save agent '${agent.id}': ${it.message}", it)
        }

    override fun deleteAgent(id: String): Result<Unit> =
        runCatching {
            scheduler.updateSchedule(id, null)
            synchronized(runningLock) {
                jobs.remove(id)?.cancel()
            }
            synchronized(runningLock) {
                running.remove(id)
            }
            agentRepository.deleteAgent(id)
        }.onFailure {
            logger.warn("Failed to delete agent '$id': ${it.message}", it)
        }

    override fun reorderAgents(agentIds: List<String>): Result<List<AgentDefinition>> =
        runCatching {
            val listedAgents = agentRepository.listAgents()
            val reordered =
                reorderByIds(listedAgents, agentIds) { it.id }
                    ?: error("Invalid agent reorder request")
            val reindexed =
                reordered.mapIndexed { index, agent ->
                    agent.copy(orderIndex = index)
                }
            reindexed.forEach { agentRepository.saveAgent(it) }
            reindexed
        }.onFailure {
            logger.warn("Failed to reorder agents: ${it.message}", it)
        }

    override fun runAgent(command: AgentRunCommand): Result<Unit> =
        when (val normalizedPrompt = command.prompt.trim()) {
            "" -> Result.failure(IllegalArgumentException("Prompt cannot be blank"))
            else ->
                runCatching {
                    val settings = loadProviderSettings()
                    val normalizedRuntime = command.runtime
                    if (normalizedRuntime == AgentRuntime.CODEX_CLI && !settings.enableCodexProvider) {
                        error("Codex provider is disabled in Agent Settings")
                    }
                    val normalizedLlm = normalizeLlm(command.llm)
                    val normalizedCodex = normalizeCodex(command.codex)
                    val normalizedFileSystem = normalizeFileSystem(command.fileSystem)
                    Triple(normalizedRuntime, normalizedLlm, normalizedCodex) to normalizedFileSystem
                }.fold(
                    onSuccess = { (runtimeModelPair, normalizedFileSystem) ->
                        val (normalizedRuntime, normalizedLlm, normalizedCodex) = runtimeModelPair
                        if (command.trigger == AgentRunTrigger.MANUAL) {
                            persistManualLaunchDefaults(
                                agentId = command.agentId,
                                prompt = normalizedPrompt,
                                runtime = normalizedRuntime,
                                llm = normalizedLlm,
                                codex = normalizedCodex,
                                fileSystem = normalizedFileSystem,
                            )
                        }
                        runCatching {
                            serviceScope.launch {
                                executeAgent(
                                    agentId = command.agentId,
                                    prompt = normalizedPrompt,
                                    runtime = normalizedRuntime,
                                    llm = normalizedLlm,
                                    codex = normalizedCodex,
                                    fileSystem = normalizedFileSystem,
                                    trigger = command.trigger,
                                )
                            }
                        }.map { Unit }
                    },
                    onFailure = { error -> Result.failure(error) },
                )
        }

    override suspend fun stopAgent(agentId: String): Result<Unit> {
        val activeJob =
            synchronized(runningLock) {
                jobs[agentId]?.takeIf { it.isActive }
            } ?: return Result.success(Unit)

        return runCatching {
            activeJob.cancelAndJoin()
        }.onFailure {
            logger.warn("Failed to stop agent '$agentId': ${it.message}", it)
        }
    }

    override fun saveSchedule(command: AgentScheduleCommand): Result<AgentDefinition> =
        runCatching {
            val normalizedCron = command.cron.trim()
            val normalizedPrompt = command.prompt.trim()
            val normalizedTimezone = command.timezoneId.trim()
            val settings = loadProviderSettings()
            val normalizedRuntime = command.runtime
            if (normalizedRuntime == AgentRuntime.CODEX_CLI && !settings.enableCodexProvider) {
                error("Codex provider is disabled in Agent Settings")
            }
            val normalizedLlm = normalizeLlm(command.llm)
            val normalizedCodex = normalizeCodex(command.codex)
            val normalizedFileSystem = normalizeFileSystem(command.fileSystem)
            require(normalizedPrompt.isNotBlank()) { "Schedule prompt cannot be blank" }
            CronScheduleValidator
                .validate(normalizedCron, normalizedTimezone)
                .getOrElse { throw IllegalArgumentException("Invalid schedule: ${it.message}", it) }

            val agent = agentRepository.loadAgent(command.agentId)
            val schedule =
                AgentSchedule(
                    cron = normalizedCron,
                    prompt = normalizedPrompt,
                    timezoneId = normalizedTimezone,
                    runtime = normalizedRuntime,
                    llm = normalizedLlm,
                    codex = normalizedCodex,
                    fileSystem = normalizedFileSystem,
                )
            val updated = agent.copy(schedule = schedule)
            agentRepository.saveAgent(updated)
            scheduler.updateSchedule(command.agentId, schedule)
            updated
        }.onFailure {
            logger.warn("Failed to save schedule for '${command.agentId}': ${it.message}", it)
        }

    override fun clearSchedule(agentId: String): Result<AgentDefinition> =
        runCatching {
            val agent = agentRepository.loadAgent(agentId)
            val updated = agent.copy(schedule = null)
            agentRepository.saveAgent(updated)
            scheduler.updateSchedule(agentId, null)
            updated
        }.onFailure {
            logger.warn("Failed to clear schedule for '$agentId': ${it.message}", it)
        }

    override fun runningAgentIds(): Set<String> =
        runCatching { synchronized(runningLock) { running.keys.toSet() } }
            .getOrDefault(emptySet())

    override fun runningAgents(): Map<String, Long> =
        runCatching { synchronized(runningLock) { running.toMap() } }
            .getOrDefault(emptyMap())

    override fun listRuns(): List<AgentRunSummary> =
        runCatching { runRepository.listRuns() }
            .onFailure { logger.warn("Failed to list agent runs: ${it.message}", it) }
            .getOrDefault(emptyList())

    override fun loadRun(runId: String): AgentRunDetails? =
        runCatching { runRepository.loadRun(runId) }
            .onFailure { logger.warn("Failed to load run '$runId': ${it.message}", it) }
            .getOrNull()

    override fun loadProviderSettings(): AgentProviderSettings =
        runCatching { settingsRepository.loadSettings() }
            .onFailure { logger.warn("Failed to load provider settings: ${it.message}", it) }
            .getOrDefault(AgentProviderSettings())

    override fun saveProviderSettings(settings: AgentProviderSettings): Result<AgentProviderSettings> =
        runCatching {
            settingsRepository.saveSettings(settings)
            settings
        }.onFailure {
            logger.warn("Failed to save provider settings: ${it.message}", it)
        }

    override fun saveProviderApiKey(
        provider: LlmProvider,
        apiKey: String,
    ): Result<Unit> =
        runCatching {
            require(provider.requiresApiKey()) { "Provider $provider does not require API key" }
            secretsStore.saveApiKey(provider, apiKey)
        }.onFailure {
            logger.warn("Failed to save provider api key for $provider: ${it.message}", it)
        }

    override fun hasProviderApiKey(provider: LlmProvider): Boolean {
        if (!provider.requiresApiKey()) {
            return false
        }
        return runCatching {
            !secretsStore.loadApiKey(provider).isNullOrBlank()
        }.getOrDefault(false)
    }

    override fun clearProviderApiKey(provider: LlmProvider): Result<Unit> {
        if (!provider.requiresApiKey()) {
            return Result.success(Unit)
        }
        return runCatching {
            secretsStore.clearApiKey(provider)
        }.onFailure {
            logger.warn("Failed to clear provider api key for $provider: ${it.message}", it)
        }
    }

    override suspend fun listProviderModels(
        provider: LlmProvider,
        forceRefresh: Boolean,
    ): Result<List<String>> =
        runCatching {
            val settings = loadProviderSettings()
            val cachedModels = settings.modelCache.modelsFor(provider)
            if (!forceRefresh && cachedModels.isNotEmpty()) {
                cachedModels
            } else {
                val apiKey =
                    if (provider.requiresApiKey()) {
                        checkNotNull(secretsStore.loadApiKey(provider)?.trim()?.takeIf { it.isNotBlank() }) {
                            "Missing API key for provider $provider"
                        }
                    } else {
                        null
                    }
                val mcpConfig = configurationProvider()
                val fetched =
                    modelCatalog
                        .listModels(
                            provider = provider,
                            providerSettings = settings,
                            apiKey = apiKey,
                            requestTimeoutSeconds = mcpConfig.requestTimeoutSeconds,
                            ignoreHttpsCertificateErrors = mcpConfig.ignoreHttpsCertificateErrors,
                        ).getOrElse { throw it }
                val models = sanitizeModelList(fetched)
                val updatedSettings = settings.copy(modelCache = settings.modelCache.withModels(provider, models))
                settingsRepository.saveSettings(updatedSettings)
                models
            }
        }.onFailure {
            logger.warn("Failed to load models for provider $provider: ${it.message}", it)
        }

    override suspend fun listCodexModels(forceRefresh: Boolean): Result<List<String>> =
        runCatching {
            val settings = loadProviderSettings()
            val cachedModels = sanitizeModelList(settings.modelCache.codex)
            val cachedAt = settings.modelCache.codexFetchedAtEpochMillis
            val currentTime = now()
            val isCacheFresh =
                !forceRefresh &&
                    cachedAt != null &&
                    (currentTime - cachedAt).coerceAtLeast(0L) < CODEX_MODEL_CACHE_TTL_MILLIS
            if (isCacheFresh) {
                cachedModels
            } else {
                val fetched = codexModelCatalog.listModels(settings.codex.command)
                if (fetched.isSuccess) {
                    val models = sanitizeModelList(fetched.getOrThrow())
                    val updatedSettings =
                        settings.copy(
                            modelCache =
                                settings.modelCache.copy(
                                    codex = models,
                                    codexFetchedAtEpochMillis = currentTime,
                                ),
                        )
                    settingsRepository.saveSettings(updatedSettings)
                    models
                } else {
                    val failure = checkNotNull(fetched.exceptionOrNull())
                    if (cachedModels.isNotEmpty()) {
                        logger.warn("Failed to refresh Codex models, using cached list: ${failure.message}", failure)
                        cachedModels
                    } else {
                        throw failure
                    }
                }
            }
        }.onFailure {
            logger.warn("Failed to load Codex models: ${it.message}", it)
        }

    @Suppress("LongMethod")
    override suspend fun generateAgentDescription(command: AgentDescriptionGenerationCommand): Result<String> {
        var runtime = AgentRuntime.LANGCHAIN
        var retryUsed = false
        var wordCountForLog: Int? = null

        return runCatching {
            val settings = loadProviderSettings()
            val aiFeatures = settings.aiFeatures
            require(aiFeatures.enabled) { "AI features are disabled in Agent Settings" }

            runtime = aiFeatures.runtime
            if (runtime == AgentRuntime.CODEX_CLI && !settings.enableCodexProvider) {
                error("Codex provider is disabled in Agent Settings")
            }

            val normalizedDraft = normalizeDescriptionDraft(command.draft)
            val normalizedLlm = normalizeLlm(aiFeatures.llm)
            val normalizedCodex = normalizeCodex(aiFeatures.codex)
            val contextSummary =
                buildCapabilityContextSummary(
                    draft = normalizedDraft,
                    capabilityContext = command.capabilityContext,
                )

            logger.infoJson("agent.description.generation.started") {
                put("agentId", JsonPrimitive(normalizedDraft.id))
                put("runtime", JsonPrimitive(runtime.name))
                put("contextLength", JsonPrimitive(contextSummary.length))
                when (runtime) {
                    AgentRuntime.LANGCHAIN -> {
                        put("provider", JsonPrimitive(normalizedLlm.provider.name))
                        put("model", JsonPrimitive(normalizedLlm.model))
                        put("temperature", JsonPrimitive(normalizedLlm.temperature))
                    }
                    AgentRuntime.CODEX_CLI -> {
                        put("provider", JsonPrimitive(AgentRuntime.CODEX_CLI.name))
                        put("model", JsonPrimitive(normalizedCodex.model))
                        put("reasoningEffort", JsonPrimitive(normalizedCodex.reasoningEffort.name))
                    }
                }
            }

            val firstPrompt =
                buildDescriptionPrompt(
                    draft = normalizedDraft,
                    capabilitySummary = contextSummary,
                )
            val firstRaw =
                executeDescriptionGenerationAttempt(
                    runtime = runtime,
                    llm = normalizedLlm,
                    codex = normalizedCodex,
                    settings = settings,
                    prompt = firstPrompt,
                )
            var validation = validateDescriptionCandidate(firstRaw)
            wordCountForLog = validation.wordCount

            if (!validation.isValid) {
                retryUsed = true
                val retryPrompt =
                    buildDescriptionRewritePrompt(
                        originalPrompt = firstPrompt,
                        invalidResponse = validation.normalizedText,
                        failureReason = validation.failureReason,
                    )
                val retryRaw =
                    executeDescriptionGenerationAttempt(
                        runtime = runtime,
                        llm = normalizedLlm,
                        codex = normalizedCodex,
                        settings = settings,
                        prompt = retryPrompt,
                    )
                validation = validateDescriptionCandidate(retryRaw)
                wordCountForLog = validation.wordCount
            }

            check(validation.isValid) {
                validation.failureReason
            }

            logger.infoJson("agent.description.generation.succeeded") {
                put("agentId", JsonPrimitive(normalizedDraft.id))
                put("runtime", JsonPrimitive(runtime.name))
                put("wordCount", JsonPrimitive(validation.wordCount))
                put("retryUsed", JsonPrimitive(retryUsed))
            }

            validation.normalizedText
        }.onFailure { failure ->
            logger.errorJson("agent.description.generation.failed", failure) {
                put("agentId", JsonPrimitive(command.draft.id))
                put("runtime", JsonPrimitive(runtime.name))
                wordCountForLog?.let { wordCount ->
                    put("wordCount", JsonPrimitive(wordCount))
                }
                put("retryUsed", JsonPrimitive(retryUsed))
                put("errorMessage", JsonPrimitive(failure.message ?: "Description generation failed"))
            }
        }
    }

    @Suppress("LongMethod")
    override suspend fun generateAgent(command: AgentGenerationCommand): Result<AgentGeneratedDraft> {
        var runtime = AgentRuntime.LANGCHAIN
        var stage: AgentGenerationProgressStage? = null

        return runCatching {
            val normalizedRequest = command.userRequest.trim()
            require(normalizedRequest.isNotBlank()) { "User request cannot be blank" }

            val settings = loadProviderSettings()
            val aiFeatures = command.aiFeaturesOverride ?: settings.aiFeatures
            require(aiFeatures.enabled) { "AI features are disabled in Agent Settings" }

            runtime = aiFeatures.runtime
            if (runtime == AgentRuntime.CODEX_CLI && !settings.enableCodexProvider) {
                error("Codex provider is disabled in Agent Settings")
            }

            val normalizedLlm = normalizeLlm(aiFeatures.llm)
            val normalizedCodex = normalizeCodex(aiFeatures.codex)
            val normalizedContext = normalizeCapabilityContext(command.capabilityContext)
            require(normalizedContext.isNotEmpty()) { "No MCP server capability context is available" }
            val contextByServerId = normalizedContext.associateBy { it.serverId }

            logger.infoJson(AGENT_GENERATION_EVENT_STARTED) {
                put("runtime", JsonPrimitive(runtime.name))
                put("requestLength", JsonPrimitive(normalizedRequest.length))
                put("serverCount", JsonPrimitive(normalizedContext.size))
                put("toolCount", JsonPrimitive(normalizedContext.sumOf { it.tools.size }))
                put("promptCount", JsonPrimitive(normalizedContext.sumOf { it.prompts.size }))
                put("resourceCount", JsonPrimitive(normalizedContext.sumOf { it.resources.size }))
            }

            stage = AgentGenerationProgressStage.SELECTING_SERVERS
            emitGenerationProgress(command, stage)
            logGenerationStageStarted(stage, normalizedContext.size)
            val selectedServerIds =
                selectRelevantServers(
                    runtime = runtime,
                    llm = normalizedLlm,
                    codex = normalizedCodex,
                    settings = settings,
                    request = normalizedRequest,
                    capabilityContext = normalizedContext,
                )
            require(selectedServerIds.isNotEmpty()) { "No relevant MCP servers were selected" }
            logGenerationStageSucceeded(stage, selectedServerIds.size)

            stage = AgentGenerationProgressStage.SELECTING_CAPABILITIES
            emitGenerationProgress(command, stage)
            logGenerationStageStarted(stage, selectedServerIds.size)
            val candidateSelections =
                selectCandidateCapabilities(
                    runtime = runtime,
                    llm = normalizedLlm,
                    codex = normalizedCodex,
                    settings = settings,
                    request = normalizedRequest,
                    selectedServerIds = selectedServerIds,
                    capabilityContextByServerId = contextByServerId,
                )
            val candidateCapabilityCount =
                candidateSelections.sumOf { it.tools.size + it.prompts.size + it.resources.size }
            require(candidateCapabilityCount > 0) { "No relevant capabilities were selected" }
            logGenerationStageSucceeded(stage, candidateCapabilityCount)

            stage = AgentGenerationProgressStage.FINALIZING_AGENT
            emitGenerationProgress(command, stage)
            logGenerationStageStarted(stage, candidateSelections.size)
            val finalized =
                finalizeGeneratedAgent(
                    runtime = runtime,
                    llm = normalizedLlm,
                    codex = normalizedCodex,
                    settings = settings,
                    request = normalizedRequest,
                    candidateSelections = candidateSelections,
                    capabilityContextByServerId = contextByServerId,
                )

            require(finalized.systemPrompt.isNotBlank()) { "Generated system prompt cannot be blank" }
            val enabledCapabilities = finalized.tools.size + finalized.prompts.size + finalized.resources.size
            require(enabledCapabilities >= AGENT_GENERATION_MIN_CAPABILITIES) {
                "Generated agent must include at least one capability"
            }

            logger.infoJson(AGENT_GENERATION_EVENT_SUCCEEDED) {
                put("runtime", JsonPrimitive(runtime.name))
                put("agentName", JsonPrimitive(finalized.agentName))
                put("toolCount", JsonPrimitive(finalized.tools.size))
                put("promptCount", JsonPrimitive(finalized.prompts.size))
                put("resourceCount", JsonPrimitive(finalized.resources.size))
                put("selectedServerCount", JsonPrimitive(selectedServerIds.size))
                put("candidateCapabilityCount", JsonPrimitive(candidateCapabilityCount))
            }

            finalized
        }.onFailure { failure ->
            logger.errorJson(AGENT_GENERATION_EVENT_FAILED, failure) {
                put("runtime", JsonPrimitive(runtime.name))
                stage?.let { put("stage", JsonPrimitive(it.name)) }
                put("errorMessage", JsonPrimitive(failure.message ?: "Agent generation failed"))
            }
        }
    }

    private suspend fun onScheduledTrigger(
        agentId: String,
        schedule: AgentSchedule,
    ) {
        runAgent(
            AgentRunCommand(
                agentId = agentId,
                prompt = schedule.prompt,
                runtime = schedule.runtime,
                llm = schedule.llm,
                codex = schedule.codex,
                fileSystem = normalizeFileSystem(schedule.fileSystem),
                trigger = AgentRunTrigger.SCHEDULED,
            ),
        )
    }

    @Suppress("LongMethod", "TooGenericExceptionCaught")
    private suspend fun executeAgent(
        agentId: String,
        prompt: String,
        runtime: AgentRuntime,
        llm: AgentLlmConfig,
        codex: AgentCodexConfig?,
        fileSystem: AgentFileSystemSettings,
        trigger: AgentRunTrigger,
    ) {
        val startedAt = now()
        val runId = generateRunId()
        val traceBuffer = AgentRunTraceBuffer(now)
        val currentJob = currentCoroutineContext()[Job]
        val overlap =
            synchronized(runningLock) {
                if (running.containsKey(agentId)) {
                    true
                } else {
                    running[agentId] = startedAt
                    if (currentJob != null) {
                        jobs[agentId] = currentJob
                    }
                    false
                }
            }
        if (overlap) {
            val finishedAt = now()
            val fallbackAgent = runCatching { agentRepository.loadAgent(agentId) }.getOrNull()
            val skippedSummary =
                AgentRunSummary(
                    runId = runId,
                    agentId = agentId,
                    agentName = fallbackAgent?.name ?: agentId,
                    trigger = trigger,
                    status = AgentRunStatus.SKIPPED,
                    runtime = runtime,
                    prompt = prompt,
                    response = null,
                    errorMessage = "Already running",
                    startedAtEpochMillis = startedAt,
                    finishedAtEpochMillis = finishedAt,
                )
            traceBuffer.addAction(
                AgentRunActionEntry(
                    type = AgentRunActionType.RUNTIME_EVENT,
                    message = "Run skipped because agent is already running",
                    timestampEpochMillis = finishedAt,
                ),
            )
            persistRun(
                AgentRunDetails(
                    summary = skippedSummary,
                    systemPrompt = fallbackAgent?.systemPrompt.orEmpty(),
                    llm = llm,
                    codex = codex,
                    fileSystem = fileSystem,
                    dialogue = traceBuffer.dialogueSnapshot(),
                    actions = traceBuffer.actionSnapshot(),
                ),
            )
            logger.warnJson("agent.run.skipped") {
                put("agentId", JsonPrimitive(agentId))
                put("trigger", JsonPrimitive(trigger.name))
                put("reason", JsonPrimitive("already_running"))
                put("promptLength", JsonPrimitive(prompt.length))
                put("provider", JsonPrimitive(llm.provider.name))
                put("model", JsonPrimitive(llm.model))
            }
            updatesFlow.tryEmit(AgentExecutionUpdate.Finished(agentId, skippedSummary))
            return
        }

        updatesFlow.tryEmit(AgentExecutionUpdate.Running(agentId = agentId, startedAtEpochMillis = startedAt))
        emitOperationUpdate(agentId, AgentExecutionOperation.PreparingRun, traceBuffer::addActionFromOperation)
        logger.infoJson("agent.run.started") {
            put("agentId", JsonPrimitive(agentId))
            put("trigger", JsonPrimitive(trigger.name))
            put("runtime", JsonPrimitive(runtime.name))
            put("provider", JsonPrimitive(llm.provider.name))
            put("model", JsonPrimitive(llm.model))
            put("temperature", JsonPrimitive(llm.temperature))
            put("startedAtEpochMillis", JsonPrimitive(startedAt))
            put("promptLength", JsonPrimitive(prompt.length))
        }

        var loadedAgent: AgentDefinition? = null
        try {
            val agent = agentRepository.loadAgent(agentId)
            loadedAgent = agent
            val settings = loadProviderSettings()
            val apiKeys = loadAvailableApiKeys()
            val apiKey = resolveApiKeyForRequest(runtime, llm.provider, apiKeys)

            val executionResult =
                executor.execute(
                    AgentExecutionRequest(
                        agent = agent,
                        runtime = runtime,
                        llm = llm,
                        codex = codex,
                        prompt = prompt,
                        fileSystem = fileSystem,
                        providerSettings = settings,
                        mcpConfig = configurationProvider(),
                        apiKey = apiKey,
                        apiKeys = apiKeys,
                        resolveAgentById = { id -> runCatching { agentRepository.loadAgent(id) }.getOrNull() },
                        executeNestedAgent = { nestedRequest -> executor.execute(nestedRequest) },
                        onOperation = { operation ->
                            emitOperationUpdate(
                                agentId = agentId,
                                operation = operation,
                                onTraceOperation = traceBuffer::addActionFromOperation,
                            )
                        },
                        onTraceDialogue = traceBuffer::addDialogue,
                        onTraceAction = traceBuffer::addAction,
                    ),
                )

            val finishedAt = now()
            val summary =
                if (executionResult.isSuccess) {
                    AgentRunSummary(
                        runId = runId,
                        agentId = agentId,
                        agentName = agent.name,
                        trigger = trigger,
                        status = AgentRunStatus.SUCCESS,
                        runtime = runtime,
                        prompt = prompt,
                        response = executionResult.getOrThrow().response,
                        errorMessage = null,
                        startedAtEpochMillis = startedAt,
                        finishedAtEpochMillis = finishedAt,
                    )
                } else {
                    AgentRunSummary(
                        runId = runId,
                        agentId = agentId,
                        agentName = agent.name,
                        trigger = trigger,
                        status = AgentRunStatus.FAILED,
                        runtime = runtime,
                        prompt = prompt,
                        response = null,
                        errorMessage = executionResult.exceptionOrNull()?.message,
                        startedAtEpochMillis = startedAt,
                        finishedAtEpochMillis = finishedAt,
                    )
                }
            logRunFinished(
                agentId = agentId,
                summary = summary,
                runtime = runtime,
                llm = llm,
                codex = codex,
                failure = executionResult.exceptionOrNull(),
            )
            persistRun(
                AgentRunDetails(
                    summary = summary,
                    systemPrompt = agent.systemPrompt,
                    llm = llm,
                    codex = codex,
                    fileSystem = fileSystem,
                    dialogue = traceBuffer.dialogueSnapshot(),
                    actions = traceBuffer.actionSnapshot(),
                ),
            )
            updatesFlow.tryEmit(AgentExecutionUpdate.Finished(agentId, summary))
        } catch (error: Throwable) {
            val failedSummary =
                AgentRunSummary(
                    runId = runId,
                    agentId = agentId,
                    agentName = loadedAgent?.name ?: agentId,
                    trigger = trigger,
                    status = AgentRunStatus.FAILED,
                    runtime = runtime,
                    prompt = prompt,
                    response = null,
                    errorMessage = error.message,
                    startedAtEpochMillis = startedAt,
                    finishedAtEpochMillis = now(),
                )
            logRunFinished(
                agentId = agentId,
                summary = failedSummary,
                runtime = runtime,
                llm = llm,
                codex = codex,
                failure = error,
            )
            persistRun(
                AgentRunDetails(
                    summary = failedSummary,
                    systemPrompt = loadedAgent?.systemPrompt.orEmpty(),
                    llm = llm,
                    codex = codex,
                    fileSystem = fileSystem,
                    dialogue = traceBuffer.dialogueSnapshot(),
                    actions = traceBuffer.actionSnapshot(),
                ),
            )
            updatesFlow.tryEmit(AgentExecutionUpdate.Finished(agentId, failedSummary))
        } finally {
            synchronized(runningLock) {
                running.remove(agentId)
                jobs.remove(agentId)
            }
        }
    }

    private fun persistManualLaunchDefaults(
        agentId: String,
        prompt: String,
        runtime: AgentRuntime,
        llm: AgentLlmConfig,
        codex: AgentCodexConfig?,
        fileSystem: AgentFileSystemSettings,
    ) {
        runCatching {
            val agent = agentRepository.loadAgent(agentId)
            val defaults =
                AgentManualLaunchDefaults(
                    prompt = prompt,
                    runtime = runtime,
                    llm = llm,
                    codex = codex,
                    fileSystem = fileSystem,
                )
            if (agent.manualLaunchDefaults == defaults) {
                return@runCatching
            }
            agentRepository.saveAgent(agent.copy(manualLaunchDefaults = defaults))
        }.onFailure {
            logger.warn("Failed to save manual launch defaults for '$agentId': ${it.message}", it)
        }
    }

    private fun persistRun(details: AgentRunDetails) {
        runCatching {
            runRepository.saveRun(details)
        }.onFailure {
            val message =
                "Failed to persist run '${details.summary.runId}' for agent '${details.summary.agentId}': " +
                    it.message
            logger.warn(
                message,
                it,
            )
        }
    }

    private fun logRunFinished(
        agentId: String,
        summary: AgentRunSummary,
        runtime: AgentRuntime,
        llm: AgentLlmConfig,
        codex: AgentCodexConfig?,
        failure: Throwable? = null,
    ) {
        val durationMillis = (summary.finishedAtEpochMillis - summary.startedAtEpochMillis).coerceAtLeast(0L)
        val codexConfig = codex ?: AgentCodexConfig()
        val payload: JsonObjectBuilder.() -> Unit = {
            put("agentId", JsonPrimitive(agentId))
            put("runId", JsonPrimitive(summary.runId))
            put("trigger", JsonPrimitive(summary.trigger.name))
            put("status", JsonPrimitive(summary.status.name))
            put("runtime", JsonPrimitive(runtime.name))
            when (runtime) {
                AgentRuntime.LANGCHAIN -> {
                    put("provider", JsonPrimitive(llm.provider.name))
                    put("model", JsonPrimitive(llm.model))
                    put("temperature", JsonPrimitive(llm.temperature))
                }
                AgentRuntime.CODEX_CLI -> {
                    put("provider", JsonPrimitive(AgentRuntime.CODEX_CLI.name))
                    put("model", JsonPrimitive(codexConfig.model))
                    put("reasoningEffort", JsonPrimitive(codexConfig.reasoningEffort.name))
                }
            }
            put("durationMillis", JsonPrimitive(durationMillis))
            put("promptLength", JsonPrimitive(summary.prompt.length))
            put("startedAtEpochMillis", JsonPrimitive(summary.startedAtEpochMillis))
            put("finishedAtEpochMillis", JsonPrimitive(summary.finishedAtEpochMillis))
            summary.response?.let { response ->
                put("responseLength", JsonPrimitive(response.length))
            }
            summary.errorMessage?.let { errorMessage ->
                put("errorMessage", JsonPrimitive(errorMessage))
            }
        }
        when (summary.status) {
            AgentRunStatus.SUCCESS -> logger.infoJson("agent.run.finished", payload)
            AgentRunStatus.SKIPPED -> logger.warnJson("agent.run.finished", failure, payload)
            AgentRunStatus.FAILED -> logger.errorJson("agent.run.finished", failure, payload)
        }
    }

    private fun emitOperationUpdate(
        agentId: String,
        operation: AgentExecutionOperation,
        onTraceOperation: ((AgentExecutionOperation) -> Unit)? = null,
    ) {
        updatesFlow.tryEmit(AgentExecutionUpdate.Operation(agentId = agentId, operation = operation))
        onTraceOperation?.invoke(operation)
        logger.infoJson("agent.run.operation") {
            put("agentId", JsonPrimitive(agentId))
            when (operation) {
                AgentExecutionOperation.PreparingRun -> {
                    put("operation", JsonPrimitive("PREPARING_RUN"))
                }
                AgentExecutionOperation.LoadingCapabilities -> {
                    put("operation", JsonPrimitive("LOADING_CAPABILITIES"))
                }
                is AgentExecutionOperation.LlmRequest -> {
                    put("operation", JsonPrimitive("LLM_REQUEST"))
                    put("step", JsonPrimitive(operation.step))
                }
                is AgentExecutionOperation.LlmThinking -> {
                    put("operation", JsonPrimitive("LLM_THINKING"))
                    put("step", JsonPrimitive(operation.step))
                }
                is AgentExecutionOperation.LlmResponseGeneration -> {
                    put("operation", JsonPrimitive("LLM_RESPONSE_GENERATION"))
                    put("step", JsonPrimitive(operation.step))
                }
                is AgentExecutionOperation.ToolExecution -> {
                    put("operation", JsonPrimitive("TOOL_EXECUTION"))
                    put("step", JsonPrimitive(operation.step))
                    put("serverId", JsonPrimitive(operation.serverId))
                    put("toolName", JsonPrimitive(operation.toolName))
                }
            }
        }
    }

    private fun normalizeDescriptionDraft(draft: AgentDefinition): AgentDefinition {
        val normalizedName = draft.name.trim()
        val normalizedSystemPrompt = draft.systemPrompt.trim()
        require(normalizedName.isNotBlank()) { "Agent name cannot be blank" }
        require(normalizedSystemPrompt.isNotBlank()) { "System prompt cannot be blank" }
        return draft.copy(
            id = draft.id.trim().ifBlank { AGENT_DESCRIPTION_RUNTIME_AGENT_ID },
            name = normalizedName,
            systemPrompt = normalizedSystemPrompt,
        )
    }

    private suspend fun executeDescriptionGenerationAttempt(
        runtime: AgentRuntime,
        llm: AgentLlmConfig,
        codex: AgentCodexConfig,
        settings: AgentProviderSettings,
        prompt: String,
    ): String {
        val apiKey =
            if (runtime == AgentRuntime.LANGCHAIN && llm.provider.requiresApiKey()) {
                checkNotNull(secretsStore.loadApiKey(llm.provider)?.trim()?.takeIf { it.isNotBlank() }) {
                    "Missing API key for provider ${llm.provider}"
                }
            } else {
                null
            }
        val request =
            AgentExecutionRequest(
                agent =
                    AgentDefinition(
                        id = AGENT_DESCRIPTION_RUNTIME_AGENT_ID,
                        name = AGENT_DESCRIPTION_RUNTIME_AGENT_NAME,
                        systemPrompt = AGENT_DESCRIPTION_SYSTEM_PROMPT,
                    ),
                runtime = runtime,
                llm = llm,
                codex = if (runtime == AgentRuntime.CODEX_CLI) codex else null,
                prompt = prompt,
                fileSystem =
                    AgentFileSystemSettings(
                        path = DEFAULT_AGENT_WORKSPACE_PATH,
                        access = AgentFileSystemAccess.NONE,
                    ),
                providerSettings = settings,
                mcpConfig = configurationProvider(),
                apiKey = apiKey,
            )
        val response = executor.execute(request).getOrElse { throw it }.response
        return normalizeDescriptionText(response)
    }

    private fun buildDescriptionPrompt(
        draft: AgentDefinition,
        capabilitySummary: String,
    ): String =
        """
        Agent name:
        ${draft.name}

        Agent system prompt:
        ${draft.systemPrompt}

        Agent capabilities context:
        $capabilitySummary

        Write one concise English sentence of exactly $AGENT_DESCRIPTION_WORD_MIN-$AGENT_DESCRIPTION_WORD_MAX words.
        Explain what this agent does, what it can use, and when it should be invoked.
        """.trimIndent()

    private fun buildDescriptionRewritePrompt(
        originalPrompt: String,
        invalidResponse: String,
        failureReason: String,
    ): String =
        """
        Rewrite the invalid response so it satisfies every requirement.

        Original task:
        $originalPrompt

        Invalid response:
        $invalidResponse

        Validation failure:
        $failureReason

        Return one plain English sentence with exactly $AGENT_DESCRIPTION_WORD_MIN-$AGENT_DESCRIPTION_WORD_MAX words.
        """.trimIndent()

    @Suppress("LongMethod")
    private fun buildCapabilityContextSummary(
        draft: AgentDefinition,
        capabilityContext: List<AgentServerCapabilitySummary>,
    ): String {
        val snapshotsByServer = capabilityContext.associateBy { it.serverId }
        val toolLines =
            draft.tools
                .asSequence()
                .filter { it.enabled }
                .map { ref ->
                    val server = snapshotsByServer[ref.serverId]
                    val tool = server?.tools?.firstOrNull { it.name == ref.toolName }
                    val serverName =
                        server
                            ?.serverName
                            ?.trim()
                            .orEmpty()
                            .ifBlank { ref.serverId }
                    val description =
                        tool
                            ?.description
                            ?.trim()
                            .orEmpty()
                            .ifBlank { "No description." }
                    val arguments = tool?.arguments.orEmpty().formatArgumentSummary()
                    "$serverName/${ref.toolName}: $description Arguments: $arguments"
                }.toList()
                .ifEmpty { listOf("none") }

        val promptLines =
            when (val prompts = draft.prompts) {
                null -> listOf("unconfigured (agent may use prompt defaults).")
                else ->
                    prompts
                        .asSequence()
                        .filter { it.enabled }
                        .map { ref ->
                            val server = snapshotsByServer[ref.serverId]
                            val prompt = server?.prompts?.firstOrNull { it.name == ref.promptName }
                            val serverName =
                                server
                                    ?.serverName
                                    ?.trim()
                                    .orEmpty()
                                    .ifBlank { ref.serverId }
                            val description =
                                prompt
                                    ?.description
                                    ?.trim()
                                    .orEmpty()
                                    .ifBlank { "No description." }
                            val arguments = prompt?.arguments.orEmpty().formatArgumentSummary()
                            "$serverName/${ref.promptName}: $description Arguments: $arguments"
                        }.toList()
                        .ifEmpty { listOf("none") }
            }

        val resourceLines =
            when (val resources = draft.resources) {
                null -> listOf("unconfigured (agent may use resource defaults).")
                else ->
                    resources
                        .asSequence()
                        .filter { it.enabled }
                        .map { ref ->
                            val server = snapshotsByServer[ref.serverId]
                            val resource = server?.resources?.firstOrNull { it.key == ref.resourceKey }
                            val serverName =
                                server
                                    ?.serverName
                                    ?.trim()
                                    .orEmpty()
                                    .ifBlank { ref.serverId }
                            val resourceName =
                                resource
                                    ?.name
                                    ?.trim()
                                    .orEmpty()
                                    .ifBlank { ref.resourceKey }
                            val description =
                                resource
                                    ?.description
                                    ?.trim()
                                    .orEmpty()
                                    .ifBlank { "No description." }
                            val arguments = resource?.arguments.orEmpty().formatArgumentSummary()
                            "$serverName/$resourceName: $description Arguments: $arguments"
                        }.toList()
                        .ifEmpty { listOf("none") }
            }

        return buildString {
            appendLine("Tools:")
            toolLines.forEach { line -> appendLine("- $line") }
            appendLine("Prompts:")
            promptLines.forEach { line -> appendLine("- $line") }
            appendLine("Resources:")
            resourceLines.forEach { line -> appendLine("- $line") }
        }.trim()
    }

    private fun emitGenerationProgress(
        command: AgentGenerationCommand,
        stage: AgentGenerationProgressStage,
    ) {
        runCatching { command.onProgress(stage) }
    }

    private fun logGenerationStageStarted(
        stage: AgentGenerationProgressStage?,
        candidateCount: Int,
    ) {
        logger.infoJson(AGENT_GENERATION_EVENT_STAGE_STARTED) {
            stage?.let { put("stage", JsonPrimitive(it.name)) }
            put("candidateCount", JsonPrimitive(candidateCount))
        }
    }

    private fun logGenerationStageSucceeded(
        stage: AgentGenerationProgressStage?,
        selectedCount: Int,
    ) {
        logger.infoJson(AGENT_GENERATION_EVENT_STAGE_SUCCEEDED) {
            stage?.let { put("stage", JsonPrimitive(it.name)) }
            put("selectedCount", JsonPrimitive(selectedCount))
        }
    }

    @Suppress("LongMethod", "MaxLineLength")
    private fun normalizeCapabilityContext(capabilityContext: List<AgentServerCapabilitySummary>): List<AgentServerCapabilitySummary> {
        if (capabilityContext.isEmpty()) return emptyList()
        val merged = linkedMapOf<String, AgentServerCapabilitySummary>()
        capabilityContext.forEach { rawServer ->
            val serverId = rawServer.serverId.trim()
            if (serverId.isBlank()) return@forEach
            val normalized =
                AgentServerCapabilitySummary(
                    serverId = serverId,
                    serverName = rawServer.serverName.trim(),
                    tools =
                        rawServer.tools
                            .mapNotNull { rawTool ->
                                val name = rawTool.name.trim()
                                if (name.isBlank()) {
                                    null
                                } else {
                                    rawTool.copy(
                                        name = name,
                                        description = rawTool.description.trim(),
                                        arguments = normalizeCapabilityArguments(rawTool.arguments),
                                    )
                                }
                            }.distinctByName { it.name },
                    prompts =
                        rawServer.prompts
                            .mapNotNull { rawPrompt ->
                                val name = rawPrompt.name.trim()
                                if (name.isBlank()) {
                                    null
                                } else {
                                    rawPrompt.copy(
                                        name = name,
                                        description = rawPrompt.description.trim(),
                                        arguments = normalizeCapabilityArguments(rawPrompt.arguments),
                                    )
                                }
                            }.distinctByName { it.name },
                    resources =
                        rawServer.resources
                            .mapNotNull { rawResource ->
                                val key = rawResource.key.trim()
                                if (key.isBlank()) {
                                    null
                                } else {
                                    rawResource.copy(
                                        key = key,
                                        name = rawResource.name.trim(),
                                        description = rawResource.description.trim(),
                                        arguments = normalizeCapabilityArguments(rawResource.arguments),
                                    )
                                }
                            }.distinctByName { it.key },
                )
            val previous = merged[serverId]
            if (previous == null) {
                merged[serverId] = normalized
            } else {
                merged[serverId] =
                    previous.copy(
                        serverName = previous.serverName.ifBlank { normalized.serverName },
                        tools = (previous.tools + normalized.tools).distinctByName { it.name },
                        prompts = (previous.prompts + normalized.prompts).distinctByName { it.name },
                        resources = (previous.resources + normalized.resources).distinctByName { it.key },
                    )
            }
        }
        return merged.values.toList()
    }

    private fun normalizeCapabilityArguments(arguments: AgentCapabilityArguments): AgentCapabilityArguments =
        arguments
            .mapNotNull { argument ->
                val name = argument.name.trim()
                if (name.isBlank()) {
                    null
                } else {
                    argument.copy(name = name, type = argument.type.trim().ifBlank { "unspecified" })
                }
            }.distinctByName { it.name }

    private suspend fun selectRelevantServers(
        runtime: AgentRuntime,
        llm: AgentLlmConfig,
        codex: AgentCodexConfig,
        settings: AgentProviderSettings,
        request: String,
        capabilityContext: List<AgentServerCapabilitySummary>,
    ): List<String> {
        val prompt =
            """
            User request:
            $request

            Available MCP servers:
            ${formatServerList(capabilityContext)}
            """.trimIndent()
        val response =
            executeAgentGenerationAttempt(
                runtime = runtime,
                llm = llm,
                codex = codex,
                settings = settings,
                systemPrompt = generationServerSelectionPromptTemplate,
                prompt = prompt,
            )
        val payload = decodeAgentGenerationJson<AgentServerSelectionResponse>(response)
        return resolveSelectedServerIds(payload.serverIds, capabilityContext)
    }

    private fun resolveSelectedServerIds(
        rawSelection: List<String>,
        capabilityContext: List<AgentServerCapabilitySummary>,
    ): List<String> {
        if (rawSelection.isEmpty()) return emptyList()
        val byId = capabilityContext.associateBy { it.serverId.lowercase() }
        val byName =
            capabilityContext
                .filter { it.serverName.isNotBlank() }
                .associateBy { it.serverName.lowercase() }
        return rawSelection
            .mapNotNull { rawValue ->
                val normalized = rawValue.trim()
                if (normalized.isBlank()) {
                    null
                } else {
                    byId[normalized.lowercase()]?.serverId
                        ?: byName[normalized.lowercase()]?.serverId
                }
            }.distinct()
    }

    @Suppress("LongMethod")
    private suspend fun selectCandidateCapabilities(
        runtime: AgentRuntime,
        llm: AgentLlmConfig,
        codex: AgentCodexConfig,
        settings: AgentProviderSettings,
        request: String,
        selectedServerIds: List<String>,
        capabilityContextByServerId: Map<String, AgentServerCapabilitySummary>,
    ): List<CandidateCapabilitySelection> {
        val selections = mutableListOf<CandidateCapabilitySelection>()
        selectedServerIds.forEach { serverId ->
            val server = capabilityContextByServerId[serverId] ?: return@forEach
            val availableCapabilityCount = server.tools.size + server.prompts.size + server.resources.size
            if (availableCapabilityCount == 0) return@forEach

            val prompt =
                """
                User request:
                $request

                Select capabilities only from this server.

                ${formatServerCapabilities(server)}
                """.trimIndent()
            val response =
                executeAgentGenerationAttempt(
                    runtime = runtime,
                    llm = llm,
                    codex = codex,
                    settings = settings,
                    systemPrompt = generationServerCapabilitiesPromptTemplate,
                    prompt = prompt,
                )
            val payload = decodeAgentGenerationJson<AgentServerCapabilitiesSelectionResponse>(response)
            val toolNames = selectAvailableNames(payload.tools, server.tools.map { it.name })
            val promptNames = selectAvailableNames(payload.prompts, server.prompts.map { it.name })
            val resourceKeys = selectAvailableNames(payload.resources, server.resources.map { it.key })
            val selectedCount = toolNames.size + promptNames.size + resourceKeys.size
            if (selectedCount == 0) return@forEach
            selections +=
                CandidateCapabilitySelection(
                    serverId = server.serverId,
                    tools = toolNames,
                    prompts = promptNames,
                    resources = resourceKeys,
                )
        }
        return selections
    }

    private fun selectAvailableNames(
        requested: List<String>,
        available: List<String>,
    ): List<String> {
        if (requested.isEmpty() || available.isEmpty()) return emptyList()
        val availableByLower = available.associateBy { it.trim().lowercase() }
        return requested
            .mapNotNull { raw ->
                val normalized = raw.trim().lowercase()
                if (normalized.isBlank()) {
                    null
                } else {
                    availableByLower[normalized]
                }
            }.distinct()
    }

    @Suppress("LongMethod")
    private suspend fun finalizeGeneratedAgent(
        runtime: AgentRuntime,
        llm: AgentLlmConfig,
        codex: AgentCodexConfig,
        settings: AgentProviderSettings,
        request: String,
        candidateSelections: List<CandidateCapabilitySelection>,
        capabilityContextByServerId: Map<String, AgentServerCapabilitySummary>,
    ): AgentGeneratedDraft {
        val prompt =
            """
            User request:
            $request

            System prompt authoring requirements:
            $generationSystemPromptTemplate

            Candidate capability selections:
            ${formatCandidateSelections(candidateSelections, capabilityContextByServerId)}
            """.trimIndent()
        val response =
            executeAgentGenerationAttempt(
                runtime = runtime,
                llm = llm,
                codex = codex,
                settings = settings,
                systemPrompt = generationFinalizePromptTemplate,
                prompt = prompt,
            )
        val payload = decodeAgentGenerationJson<AgentFinalizeSelectionResponse>(response)
        val normalizedName = payload.agentName.trim()
        val normalizedSystemPrompt = payload.systemPrompt.trim()
        val normalizedDescription = payload.description?.trim()?.takeIf { it.isNotBlank() }
        require(normalizedName.isNotBlank()) { "Generated agent name cannot be blank" }
        require(normalizedSystemPrompt.isNotBlank()) { "Generated system prompt cannot be blank" }

        val normalizedSelections =
            payload.selections
                .mapNotNull { selection ->
                    val serverId = selection.serverId.trim()
                    if (serverId.isBlank()) {
                        null
                    } else {
                        selection.copy(
                            serverId = serverId,
                            tools = selection.tools.map { it.trim() }.filter { it.isNotBlank() },
                            prompts = selection.prompts.map { it.trim() }.filter { it.isNotBlank() },
                            resources = selection.resources.map { it.trim() }.filter { it.isNotBlank() },
                        )
                    }
                }.ifEmpty {
                    candidateSelections.map {
                        AgentFinalizeSelectionPayload(
                            serverId = it.serverId,
                            tools = it.tools,
                            prompts = it.prompts,
                            resources = it.resources,
                        )
                    }
                }

        val tools = mutableListOf<ToolReference>()
        val prompts = mutableListOf<PromptReference>()
        val resources = mutableListOf<ResourceReference>()
        val usedToolNames = linkedSetOf<String>()
        val usedPromptNames = linkedSetOf<String>()
        val usedResourceKeys = linkedSetOf<String>()

        normalizedSelections.forEach { selection ->
            val server = capabilityContextByServerId[selection.serverId] ?: return@forEach
            val availableToolsByLower = server.tools.associateBy { it.name.lowercase() }
            val availablePromptsByLower = server.prompts.associateBy { it.name.lowercase() }
            val availableResourcesByLower = server.resources.associateBy { it.key.lowercase() }

            selection.tools.forEach { toolName ->
                val resolved = availableToolsByLower[toolName.lowercase()]?.name ?: return@forEach
                val key = resolved.lowercase()
                if (usedToolNames.add(key)) {
                    tools += ToolReference(serverId = server.serverId, toolName = resolved, enabled = true)
                }
            }
            selection.prompts.forEach { promptName ->
                val resolved = availablePromptsByLower[promptName.lowercase()]?.name ?: return@forEach
                val key = resolved.lowercase()
                if (usedPromptNames.add(key)) {
                    prompts += PromptReference(serverId = server.serverId, promptName = resolved, enabled = true)
                }
            }
            selection.resources.forEach { resourceKey ->
                val resolved = availableResourcesByLower[resourceKey.lowercase()]?.key ?: return@forEach
                val key = resolved.lowercase()
                if (usedResourceKeys.add(key)) {
                    resources += ResourceReference(serverId = server.serverId, resourceKey = resolved, enabled = true)
                }
            }
        }

        return AgentGeneratedDraft(
            agentName = normalizedName,
            description = normalizedDescription,
            systemPrompt = normalizedSystemPrompt,
            tools = tools,
            prompts = prompts,
            resources = resources,
        )
    }

    private suspend fun executeAgentGenerationAttempt(
        runtime: AgentRuntime,
        llm: AgentLlmConfig,
        codex: AgentCodexConfig,
        settings: AgentProviderSettings,
        systemPrompt: String,
        prompt: String,
    ): String {
        val apiKey =
            if (runtime == AgentRuntime.LANGCHAIN && llm.provider.requiresApiKey()) {
                checkNotNull(secretsStore.loadApiKey(llm.provider)?.trim()?.takeIf { it.isNotBlank() }) {
                    "Missing API key for provider ${llm.provider}"
                }
            } else {
                null
            }
        val request =
            AgentExecutionRequest(
                agent =
                    AgentDefinition(
                        id = AGENT_GENERATION_RUNTIME_AGENT_ID,
                        name = AGENT_GENERATION_RUNTIME_AGENT_NAME,
                        systemPrompt = systemPrompt.trim(),
                    ),
                runtime = runtime,
                llm = llm,
                codex = if (runtime == AgentRuntime.CODEX_CLI) codex else null,
                prompt = prompt,
                fileSystem =
                    AgentFileSystemSettings(
                        path = DEFAULT_AGENT_WORKSPACE_PATH,
                        access = AgentFileSystemAccess.NONE,
                    ),
                providerSettings = settings,
                mcpConfig = configurationProvider(),
                apiKey = apiKey,
            )
        val response = executor.execute(request).getOrElse { throw it }.response
        return response.trim()
    }

    private fun formatServerList(capabilityContext: List<AgentServerCapabilitySummary>): String =
        buildString {
            capabilityContext.forEach { server ->
                append("- id=")
                append(server.serverId)
                append(", name=")
                append(server.serverName.ifBlank { server.serverId })
                append(", tools=")
                append(server.tools.size)
                append(", prompts=")
                append(server.prompts.size)
                append(", resources=")
                append(server.resources.size)
                appendLine()
            }
        }.trim()

    private fun formatServerCapabilities(server: AgentServerCapabilitySummary): String =
        buildString {
            appendLine("Server id: ${server.serverId}")
            appendLine("Server name: ${server.serverName.ifBlank { server.serverId }}")
            appendLine("Tools:")
            if (server.tools.isEmpty()) {
                appendLine("- none")
            } else {
                server.tools.forEach { tool ->
                    appendLine("- ${tool.name}: ${tool.description.ifBlank { "No description." }}")
                    appendLine("  Arguments: ${tool.arguments.formatArgumentSummary()}")
                }
            }
            appendLine("Prompts:")
            if (server.prompts.isEmpty()) {
                appendLine("- none")
            } else {
                server.prompts.forEach { prompt ->
                    appendLine("- ${prompt.name}: ${prompt.description.ifBlank { "No description." }}")
                    appendLine("  Arguments: ${prompt.arguments.formatArgumentSummary()}")
                }
            }
            appendLine("Resources:")
            if (server.resources.isEmpty()) {
                appendLine("- none")
            } else {
                server.resources.forEach { resource ->
                    appendLine("- ${resource.key}: ${resource.description.ifBlank { "No description." }}")
                    appendLine("  Arguments: ${resource.arguments.formatArgumentSummary()}")
                }
            }
        }.trim()

    private fun formatCandidateSelections(
        selections: List<CandidateCapabilitySelection>,
        capabilityContextByServerId: Map<String, AgentServerCapabilitySummary>,
    ): String =
        buildString {
            selections.forEach { selection ->
                val server = capabilityContextByServerId[selection.serverId] ?: return@forEach
                appendLine("- serverId=${server.serverId}, serverName=${server.serverName.ifBlank { server.serverId }}")
                appendLine("  tools=${selection.tools.joinToString(prefix = "[", postfix = "]")}")
                appendLine("  prompts=${selection.prompts.joinToString(prefix = "[", postfix = "]")}")
                appendLine("  resources=${selection.resources.joinToString(prefix = "[", postfix = "]")}")
            }
        }.trim()

    private inline fun <reified T> decodeAgentGenerationJson(response: String): T {
        val payload = parseAgentGenerationJsonObject(response)
        return runCatching {
            AGENT_GENERATION_JSON_CONFIG.decodeFromJsonElement<T>(payload)
        }.getOrElse { error ->
            throw IllegalStateException("Failed to decode model JSON response: ${error.message}", error)
        }
    }

    @Suppress("ReturnCount")
    private fun parseAgentGenerationJsonObject(response: String): JsonObject {
        val trimmed = response.trim()
        if (trimmed.isBlank()) {
            error("Model returned an empty response")
        }
        parseJsonObject(trimmed)?.let { return it }
        val unfenced = stripCodeFence(trimmed)
        parseJsonObject(unfenced)?.let { return it }
        val firstBrace = unfenced.indexOf('{')
        val lastBrace = unfenced.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            parseJsonObject(unfenced.substring(firstBrace, lastBrace + 1))?.let { return it }
        }
        error("Model response is not valid JSON object")
    }

    private fun parseJsonObject(raw: String): JsonObject? =
        runCatching {
            AGENT_GENERATION_JSON_CONFIG.parseToJsonElement(raw).jsonObject
        }.getOrNull()

    @Suppress("ReturnCount")
    private fun stripCodeFence(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val lines = trimmed.lines()
        if (lines.size < MARKDOWN_CODE_FENCE_MIN_LINES) return trimmed
        if (lines.last().trim() != "```") return trimmed
        return lines
            .drop(1)
            .dropLast(1)
            .joinToString("\n")
            .trim()
    }

    private fun loadPromptTemplate(path: String): String =
        AgentGenerationPromptMarker::class.java
            .getResourceAsStream(path)
            ?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: error("Prompt template not found or blank: $path")

    @Suppress("ReturnCount")
    private fun validateDescriptionCandidate(raw: String): DescriptionValidation {
        val normalized = normalizeDescriptionText(raw)
        if (normalized.isBlank()) {
            return DescriptionValidation(
                normalizedText = normalized,
                wordCount = 0,
                isValid = false,
                failureReason = "Description is empty.",
            )
        }
        val hasNonEnglishLetters =
            normalized.any { character ->
                character.isLetter() &&
                    character !in 'a'..'z' &&
                    character !in 'A'..'Z'
            }
        if (hasNonEnglishLetters) {
            return DescriptionValidation(
                normalizedText = normalized,
                wordCount = AGENT_DESCRIPTION_WORD_REGEX.findAll(normalized).count(),
                isValid = false,
                failureReason = "Description must be written in English.",
            )
        }
        val wordCount = AGENT_DESCRIPTION_WORD_REGEX.findAll(normalized).count()
        if (wordCount !in AGENT_DESCRIPTION_WORD_MIN..AGENT_DESCRIPTION_WORD_MAX) {
            return DescriptionValidation(
                normalizedText = normalized,
                wordCount = wordCount,
                isValid = false,
                failureReason =
                    "Description must contain $AGENT_DESCRIPTION_WORD_MIN-$AGENT_DESCRIPTION_WORD_MAX words. " +
                        "Current word count: $wordCount.",
            )
        }
        return DescriptionValidation(
            normalizedText = normalized,
            wordCount = wordCount,
            isValid = true,
            failureReason = "",
        )
    }

    private fun normalizeDescriptionText(raw: String): String {
        val collapsed =
            raw
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .replace(AGENT_DESCRIPTION_WHITESPACE_REGEX, " ")
                .trim()
        return collapsed
            .removePrefix("- ")
            .removePrefix("• ")
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .trim()
    }

    private fun List<AgentCapabilityArgumentSummary>.formatArgumentSummary(): String =
        if (isEmpty()) {
            "none."
        } else {
            joinToString(separator = ", ", postfix = ".") { argument ->
                val requiredSuffix =
                    if (argument.required) {
                        "required"
                    } else {
                        "optional"
                    }
                "${argument.name}:${argument.type} ($requiredSuffix)"
            }
        }

    private fun normalizeLlm(llm: AgentLlmConfig): AgentLlmConfig {
        val normalizedModel = llm.model.trim()
        require(normalizedModel.isNotBlank()) { "Model cannot be blank" }
        return llm.copy(model = normalizedModel)
    }

    private fun normalizeCodex(codex: AgentCodexConfig?): AgentCodexConfig {
        val normalized = codex ?: AgentCodexConfig()
        val model = normalized.model.trim().ifBlank { DEFAULT_CODEX_MODEL }
        return normalized.copy(
            model = model,
        )
    }

    private fun normalizeFileSystem(fileSystem: AgentFileSystemSettings): AgentFileSystemSettings {
        val normalizedPath = fileSystem.path.trim().ifBlank { DEFAULT_AGENT_WORKSPACE_PATH }
        return AgentFileSystemSettings(
            path = normalizedPath,
            access = fileSystem.access,
        )
    }

    private fun loadAvailableApiKeys(): Map<LlmProvider, String> =
        buildMap {
            LlmProvider
                .values()
                .asSequence()
                .filter { it.requiresApiKey() }
                .forEach { provider ->
                    val value =
                        secretsStore
                            .loadApiKey(provider)
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                    if (value != null) {
                        put(provider, value)
                    }
                }
        }

    private fun resolveApiKeyForRequest(
        runtime: AgentRuntime,
        provider: LlmProvider,
        apiKeys: Map<LlmProvider, String>,
    ): String? {
        if (runtime != AgentRuntime.LANGCHAIN || !provider.requiresApiKey()) {
            return null
        }
        return checkNotNull(apiKeys[provider]) {
            "Missing API key for provider $provider"
        }
    }

    private fun <T> reorderByIds(
        items: List<T>,
        orderedIds: List<String>,
        idSelector: (T) -> String,
    ): List<T>? =
        if (items.size != orderedIds.size || orderedIds.toSet().size != orderedIds.size) {
            null
        } else {
            val byId = items.associateBy(idSelector)
            if (orderedIds.any { it !in byId }) {
                null
            } else {
                orderedIds.map { byId.getValue(it) }
            }
        }

    private fun generateRunId(): String = UUID.randomUUID().toString()
}

private data class DescriptionValidation(
    val normalizedText: String,
    val wordCount: Int,
    val isValid: Boolean,
    val failureReason: String,
)

@Serializable
private data class AgentServerSelectionResponse(
    val serverIds: List<String> = emptyList(),
)

@Serializable
private data class AgentServerCapabilitiesSelectionResponse(
    val tools: List<String> = emptyList(),
    val prompts: List<String> = emptyList(),
    val resources: List<String> = emptyList(),
)

@Serializable
private data class AgentFinalizeSelectionResponse(
    val agentName: String = "",
    val description: String? = null,
    val systemPrompt: String = "",
    val selections: List<AgentFinalizeSelectionPayload> = emptyList(),
)

@Serializable
private data class AgentFinalizeSelectionPayload(
    val serverId: String,
    val tools: List<String> = emptyList(),
    val prompts: List<String> = emptyList(),
    val resources: List<String> = emptyList(),
)

private data class CandidateCapabilitySelection(
    val serverId: String,
    val tools: List<String> = emptyList(),
    val prompts: List<String> = emptyList(),
    val resources: List<String> = emptyList(),
)

private object AgentGenerationPromptMarker

private inline fun <T> List<T>.distinctByName(selector: (T) -> String): List<T> {
    if (isEmpty()) return emptyList()
    val used = linkedSetOf<String>()
    val result = mutableListOf<T>()
    for (item in this) {
        val key = selector(item).trim().lowercase()
        if (key.isBlank()) continue
        if (used.add(key)) {
            result += item
        }
    }
    return result
}

private class AgentRunTraceBuffer(
    private val now: () -> Long,
) {
    private val lock = Any()
    private val dialogue = mutableListOf<AgentRunDialogueEntry>()
    private val actions = mutableListOf<AgentRunActionEntry>()

    fun addDialogue(entry: AgentRunDialogueEntry) {
        val normalized =
            if (entry.timestampEpochMillis > 0L) {
                entry
            } else {
                entry.copy(timestampEpochMillis = now())
            }
        synchronized(lock) {
            dialogue += normalized
        }
    }

    fun addAction(entry: AgentRunActionEntry) {
        val normalized =
            if (entry.timestampEpochMillis > 0L) {
                entry
            } else {
                entry.copy(timestampEpochMillis = now())
            }
        synchronized(lock) {
            actions += normalized
        }
    }

    fun addActionFromOperation(operation: AgentExecutionOperation) {
        addAction(operation.toTraceAction(now()))
    }

    fun dialogueSnapshot(): List<AgentRunDialogueEntry> = synchronized(lock) { dialogue.toList() }

    fun actionSnapshot(): List<AgentRunActionEntry> = synchronized(lock) { actions.toList() }
}

private fun AgentExecutionOperation.toTraceAction(timestampEpochMillis: Long): AgentRunActionEntry =
    when (this) {
        AgentExecutionOperation.PreparingRun ->
            AgentRunActionEntry(
                type = AgentRunActionType.PREPARING_RUN,
                timestampEpochMillis = timestampEpochMillis,
            )
        AgentExecutionOperation.LoadingCapabilities ->
            AgentRunActionEntry(
                type = AgentRunActionType.LOADING_CAPABILITIES,
                timestampEpochMillis = timestampEpochMillis,
            )
        is AgentExecutionOperation.LlmRequest ->
            AgentRunActionEntry(
                type = AgentRunActionType.LLM_REQUEST,
                step = step,
                timestampEpochMillis = timestampEpochMillis,
            )
        is AgentExecutionOperation.LlmThinking ->
            AgentRunActionEntry(
                type = AgentRunActionType.LLM_THINKING,
                step = step,
                timestampEpochMillis = timestampEpochMillis,
            )
        is AgentExecutionOperation.LlmResponseGeneration ->
            AgentRunActionEntry(
                type = AgentRunActionType.LLM_RESPONSE_GENERATION,
                step = step,
                timestampEpochMillis = timestampEpochMillis,
            )
        is AgentExecutionOperation.ToolExecution ->
            AgentRunActionEntry(
                type = AgentRunActionType.TOOL_CALL,
                step = step,
                serverId = serverId,
                toolName = toolName,
                timestampEpochMillis = timestampEpochMillis,
            )
    }

private fun LlmProvider.requiresApiKey(): Boolean = this != LlmProvider.LM_STUDIO

private fun sanitizeModelList(raw: List<String>): List<String> =
    raw
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()

private fun AgentProviderModelCache.modelsFor(provider: LlmProvider): List<String> =
    when (provider) {
        LlmProvider.OPENAI -> openAi
        LlmProvider.ANTHROPIC -> anthropic
        LlmProvider.LM_STUDIO -> lmStudio
    }

private fun AgentProviderModelCache.withModels(
    provider: LlmProvider,
    models: List<String>,
): AgentProviderModelCache =
    when (provider) {
        LlmProvider.OPENAI -> copy(openAi = models)
        LlmProvider.ANTHROPIC -> copy(anthropic = models)
        LlmProvider.LM_STUDIO -> copy(lmStudio = models)
    }
