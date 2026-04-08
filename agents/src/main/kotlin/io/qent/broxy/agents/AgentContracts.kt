package io.qent.broxy.agents

import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.PromptReference
import io.qent.broxy.core.models.ResourceReference
import io.qent.broxy.core.models.ToolReference
import kotlinx.coroutines.flow.Flow

interface AgentRepository {
    fun listAgents(): List<AgentDefinition>

    fun loadAgent(id: String): AgentDefinition

    fun saveAgent(agent: AgentDefinition)

    fun deleteAgent(id: String)
}

interface AgentRunRepository {
    fun listRuns(): List<AgentRunSummary>

    fun loadRun(runId: String): AgentRunDetails

    fun saveRun(details: AgentRunDetails)
}

interface AgentProviderSettingsRepository {
    fun loadSettings(): AgentProviderSettings

    fun saveSettings(settings: AgentProviderSettings)
}

interface AgentSecretsStore {
    fun loadApiKey(provider: LlmProvider): String?

    fun saveApiKey(
        provider: LlmProvider,
        apiKey: String,
    )

    fun clearApiKey(provider: LlmProvider)
}

interface AgentScheduler {
    fun start(
        schedules: Map<String, AgentSchedule>,
        onTrigger: suspend (agentId: String, schedule: AgentSchedule) -> Unit,
    )

    fun updateSchedule(
        agentId: String,
        schedule: AgentSchedule?,
    )

    fun stop()
}

data class AgentExecutionRequest(
    val agent: AgentDefinition,
    val runtime: AgentRuntime = AgentRuntime.LANGCHAIN,
    val llm: AgentLlmConfig,
    val codex: AgentCodexConfig? = null,
    val prompt: String,
    val fileSystem: AgentFileSystemSettings,
    val providerSettings: AgentProviderSettings,
    val mcpConfig: McpServersConfig,
    val apiKey: String?,
    val apiKeys: Map<LlmProvider, String> = emptyMap(),
    val agentInvocationStack: List<String> = emptyList(),
    val resolveAgentById: ((String) -> AgentDefinition?)? = null,
    val executeNestedAgent: (suspend (AgentExecutionRequest) -> Result<AgentExecutionResult>)? = null,
    val onOperation: (AgentExecutionOperation) -> Unit = {},
    val onTraceDialogue: (AgentRunDialogueEntry) -> Unit = {},
    val onTraceAction: (AgentRunActionEntry) -> Unit = {},
)

data class AgentExecutionResult(
    val response: String,
)

interface AgentExecutor {
    suspend fun execute(request: AgentExecutionRequest): Result<AgentExecutionResult>
}

sealed interface AgentExecutionOperation {
    data object PreparingRun : AgentExecutionOperation

    data object LoadingCapabilities : AgentExecutionOperation

    data class LlmRequest(
        val step: Int,
    ) : AgentExecutionOperation

    data class LlmThinking(
        val step: Int,
    ) : AgentExecutionOperation

    data class LlmResponseGeneration(
        val step: Int,
    ) : AgentExecutionOperation

    data class ToolExecution(
        val serverId: String,
        val toolName: String,
        val step: Int,
    ) : AgentExecutionOperation
}

sealed interface AgentExecutionUpdate {
    data class Running(
        val agentId: String,
        val startedAtEpochMillis: Long,
    ) : AgentExecutionUpdate

    data class Operation(
        val agentId: String,
        val operation: AgentExecutionOperation,
    ) : AgentExecutionUpdate

    data class Finished(
        val agentId: String,
        val run: AgentRunSummary,
    ) : AgentExecutionUpdate
}

data class AgentRunCommand(
    val agentId: String,
    val prompt: String,
    val runtime: AgentRuntime = AgentRuntime.LANGCHAIN,
    val llm: AgentLlmConfig,
    val codex: AgentCodexConfig? = null,
    val fileSystem: AgentFileSystemSettings,
    val trigger: AgentRunTrigger = AgentRunTrigger.MANUAL,
)

data class AgentScheduleCommand(
    val agentId: String,
    val cron: String,
    val prompt: String,
    val timezoneId: String,
    val runtime: AgentRuntime = AgentRuntime.LANGCHAIN,
    val llm: AgentLlmConfig,
    val codex: AgentCodexConfig? = null,
    val fileSystem: AgentFileSystemSettings,
)

interface AgentCatalogService {
    fun listAgents(): List<AgentDefinition>

    fun loadAgent(id: String): AgentDefinition?

    fun upsertAgent(agent: AgentDefinition): Result<AgentDefinition>

    fun deleteAgent(id: String): Result<Unit>

    fun reorderAgents(agentIds: List<String>): Result<List<AgentDefinition>>
}

interface AgentExecutionService {
    fun runAgent(command: AgentRunCommand): Result<Unit>

    suspend fun stopAgent(agentId: String): Result<Unit>

    fun saveSchedule(command: AgentScheduleCommand): Result<AgentDefinition>

    fun clearSchedule(agentId: String): Result<AgentDefinition>
}

interface AgentProviderService {
    fun loadProviderSettings(): AgentProviderSettings

    fun saveProviderSettings(settings: AgentProviderSettings): Result<AgentProviderSettings>

    fun saveProviderApiKey(
        provider: LlmProvider,
        apiKey: String,
    ): Result<Unit>

    fun hasProviderApiKey(provider: LlmProvider): Boolean

    fun clearProviderApiKey(provider: LlmProvider): Result<Unit>

    suspend fun listProviderModels(
        provider: LlmProvider,
        forceRefresh: Boolean = false,
    ): Result<List<String>>

    suspend fun listCodexModels(forceRefresh: Boolean = false): Result<List<String>>
}

interface AgentLifecycleService {
    val updates: Flow<AgentExecutionUpdate>

    fun start()

    fun stop()

    fun runningAgentIds(): Set<String>

    fun runningAgents(): Map<String, Long>
}

interface AgentRunHistoryService {
    fun listRuns(): List<AgentRunSummary>

    fun loadRun(runId: String): AgentRunDetails?
}

data class AgentCapabilityArgumentSummary(
    val name: String,
    val type: String = "unspecified",
    val required: Boolean = false,
)

data class AgentCapabilityToolSummary(
    val name: String,
    val description: String = "",
    val arguments: List<AgentCapabilityArgumentSummary> = emptyList(),
)

data class AgentCapabilityPromptSummary(
    val name: String,
    val description: String = "",
    val arguments: List<AgentCapabilityArgumentSummary> = emptyList(),
)

data class AgentCapabilityResourceSummary(
    val key: String,
    val name: String = "",
    val description: String = "",
    val arguments: List<AgentCapabilityArgumentSummary> = emptyList(),
)

data class AgentServerCapabilitySummary(
    val serverId: String,
    val serverName: String = "",
    val tools: List<AgentCapabilityToolSummary> = emptyList(),
    val prompts: List<AgentCapabilityPromptSummary> = emptyList(),
    val resources: List<AgentCapabilityResourceSummary> = emptyList(),
)

data class AgentDescriptionGenerationCommand(
    val draft: AgentDefinition,
    val capabilityContext: List<AgentServerCapabilitySummary> = emptyList(),
)

interface AgentDescriptionService {
    suspend fun generateAgentDescription(command: AgentDescriptionGenerationCommand): Result<String>
}

enum class AgentGenerationProgressStage {
    SELECTING_SERVERS,
    SELECTING_CAPABILITIES,
    FINALIZING_AGENT,
}

data class AgentGenerationCommand(
    val userRequest: String,
    val capabilityContext: List<AgentServerCapabilitySummary> = emptyList(),
    val aiFeaturesOverride: AgentAiFeaturesSettings? = null,
    val onProgress: (AgentGenerationProgressStage) -> Unit = {},
)

data class AgentGeneratedDraft(
    val agentName: String,
    val description: String? = null,
    val systemPrompt: String,
    val tools: List<ToolReference> = emptyList(),
    val prompts: List<PromptReference> = emptyList(),
    val resources: List<ResourceReference> = emptyList(),
)

interface AgentGenerationService {
    suspend fun generateAgent(command: AgentGenerationCommand): Result<AgentGeneratedDraft>
}

interface AgentService :
    AgentCatalogService,
    AgentExecutionService,
    AgentProviderService,
    AgentLifecycleService,
    AgentRunHistoryService,
    AgentDescriptionService,
    AgentGenerationService

@Deprecated(
    message = "Use runAgent(AgentRunCommand) instead",
    replaceWith =
        ReplaceWith(
            "runAgent(AgentRunCommand(agentId, prompt, runtime, llm, codex, fileSystem, trigger))",
            "io.qent.broxy.agents.AgentRunCommand",
        ),
)
@Suppress("LongParameterList")
fun AgentExecutionService.runAgent(
    agentId: String,
    prompt: String,
    runtime: AgentRuntime = AgentRuntime.LANGCHAIN,
    llm: AgentLlmConfig,
    codex: AgentCodexConfig? = null,
    fileSystem: AgentFileSystemSettings,
    trigger: AgentRunTrigger = AgentRunTrigger.MANUAL,
): Result<Unit> =
    runAgent(
        AgentRunCommand(
            agentId = agentId,
            prompt = prompt,
            runtime = runtime,
            llm = llm,
            codex = codex,
            fileSystem = fileSystem,
            trigger = trigger,
        ),
    )

@Deprecated(
    message = "Use saveSchedule(AgentScheduleCommand) instead",
    replaceWith =
        ReplaceWith(
            "saveSchedule(AgentScheduleCommand(agentId, cron, prompt, timezoneId, runtime, llm, codex, fileSystem))",
            "io.qent.broxy.agents.AgentScheduleCommand",
        ),
)
@Suppress("LongParameterList")
fun AgentExecutionService.saveSchedule(
    agentId: String,
    cron: String,
    prompt: String,
    timezoneId: String,
    runtime: AgentRuntime = AgentRuntime.LANGCHAIN,
    llm: AgentLlmConfig,
    codex: AgentCodexConfig? = null,
    fileSystem: AgentFileSystemSettings,
): Result<AgentDefinition> =
    saveSchedule(
        AgentScheduleCommand(
            agentId = agentId,
            cron = cron,
            prompt = prompt,
            timezoneId = timezoneId,
            runtime = runtime,
            llm = llm,
            codex = codex,
            fileSystem = fileSystem,
        ),
    )
