package io.qent.broxy.ui.adapter.models

enum class UiLlmProvider {
    OPENAI,
    ANTHROPIC,
    LM_STUDIO,
}

enum class UiAgentRuntime {
    LANGCHAIN,
    CODEX_CLI,
}

enum class UiAgentCodexReasoningEffort {
    LOW,
    MEDIUM,
    HIGH,
}

const val DEFAULT_UI_LLM_MODEL: String = "gpt-5-nano"
const val DEFAULT_UI_CODEX_MODEL: String = "gpt-5.1-codex-mini"
val DEFAULT_UI_CODEX_REASONING_EFFORT: UiAgentCodexReasoningEffort = UiAgentCodexReasoningEffort.HIGH

data class UiAgentCodexConfig(
    val model: String = DEFAULT_UI_CODEX_MODEL,
    val reasoningEffort: UiAgentCodexReasoningEffort = DEFAULT_UI_CODEX_REASONING_EFFORT,
    val webSearch: Boolean = false,
)

const val DEFAULT_UI_AGENT_WORKSPACE_PATH: String = "/tmp/broxy/agents"

data class UiAgentLlmConfig(
    val provider: UiLlmProvider,
    val model: String,
    val temperature: Double = 0.2,
)

fun defaultUiAgentLlmConfig(): UiAgentLlmConfig =
    UiAgentLlmConfig(
        provider = UiLlmProvider.OPENAI,
        model = DEFAULT_UI_LLM_MODEL,
        temperature = 1.0,
    )

enum class UiAgentFileSystemAccess {
    NONE,
    READ_ONLY,
    READ_WRITE,
}

data class UiAgentFileSystemSettings(
    val path: String = DEFAULT_UI_AGENT_WORKSPACE_PATH,
    val access: UiAgentFileSystemAccess = UiAgentFileSystemAccess.NONE,
)

enum class UiAgentRunStatus {
    SUCCESS,
    FAILED,
    SKIPPED,
}

enum class UiAgentRunTrigger {
    MANUAL,
    SCHEDULED,
}

data class UiRunSummary(
    val runId: String,
    val agentId: String,
    val agentName: String,
    val trigger: UiAgentRunTrigger,
    val status: UiAgentRunStatus,
    val runtime: UiAgentRuntime,
    val prompt: String,
    val response: String? = null,
    val errorMessage: String? = null,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long,
)

enum class UiRunDialogueRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL,
}

data class UiRunDialogueEntry(
    val role: UiRunDialogueRole,
    val content: String,
    val step: Int? = null,
    val serverId: String? = null,
    val toolName: String? = null,
    val timestampEpochMillis: Long,
)

enum class UiRunActionType {
    PREPARING_RUN,
    LOADING_CAPABILITIES,
    LLM_REQUEST,
    LLM_THINKING,
    LLM_RESPONSE_GENERATION,
    TOOL_CALL,
    TOOL_RESULT,
    RUNTIME_EVENT,
}

data class UiRunActionEntry(
    val type: UiRunActionType,
    val step: Int? = null,
    val serverId: String? = null,
    val toolName: String? = null,
    val requestPayload: String? = null,
    val responsePayload: String? = null,
    val errorMessage: String? = null,
    val message: String? = null,
    val timestampEpochMillis: Long,
)

data class UiRunDetails(
    val summary: UiRunSummary,
    val systemPrompt: String,
    val llm: UiAgentLlmConfig,
    val codex: UiAgentCodexConfig? = null,
    val fileSystem: UiAgentFileSystemSettings,
    val dialogue: List<UiRunDialogueEntry> = emptyList(),
    val actions: List<UiRunActionEntry> = emptyList(),
)

data class UiAgentSchedule(
    val cron: String,
    val prompt: String,
    val timezoneId: String,
    val runtime: UiAgentRuntime = UiAgentRuntime.LANGCHAIN,
    val llm: UiAgentLlmConfig,
    val codex: UiAgentCodexConfig? = null,
    val fileSystem: UiAgentFileSystemSettings = UiAgentFileSystemSettings(),
)

data class UiSchedulePreview(
    val nextRunsEpochMillis: List<Long> = emptyList(),
)

data class UiAgentManualLaunchDefaults(
    val prompt: String,
    val runtime: UiAgentRuntime = UiAgentRuntime.LANGCHAIN,
    val llm: UiAgentLlmConfig,
    val codex: UiAgentCodexConfig? = null,
    val fileSystem: UiAgentFileSystemSettings = UiAgentFileSystemSettings(),
)

sealed interface UiAgentOperation {
    data object PreparingRun : UiAgentOperation

    data object LoadingCapabilities : UiAgentOperation

    data class LlmRequest(
        val step: Int,
    ) : UiAgentOperation

    data class LlmThinking(
        val step: Int,
    ) : UiAgentOperation

    data class LlmResponseGeneration(
        val step: Int,
    ) : UiAgentOperation

    data class ToolExecution(
        val serverId: String,
        val toolName: String,
        val step: Int,
    ) : UiAgentOperation
}

enum class UiAgentGenerationStage {
    SELECTING_SERVERS,
    SELECTING_CAPABILITIES,
    FINALIZING_AGENT,
}

data class UiGeneratedAgentDraft(
    val name: String,
    val description: String? = null,
    val systemPrompt: String,
    val tools: List<UiToolRef> = emptyList(),
    val prompts: List<UiPromptRef> = emptyList(),
    val resources: List<UiResourceRef> = emptyList(),
)

data class UiAgent(
    val id: String,
    val name: String,
    val systemPrompt: String,
    val description: String? = null,
    val tools: List<UiToolRef> = emptyList(),
    val agentTools: List<UiAgentToolRef> = emptyList(),
    val prompts: List<UiPromptRef> = emptyList(),
    val resources: List<UiResourceRef> = emptyList(),
    val promptsConfigured: Boolean = true,
    val resourcesConfigured: Boolean = true,
    val toolsCount: Int,
    val promptsCount: Int,
    val resourcesCount: Int,
    val orderIndex: Int = 0,
    val schedule: UiAgentSchedule? = null,
    val manualLaunchDefaults: UiAgentManualLaunchDefaults? = null,
    val latestFailedRun: UiRunSummary? = null,
    val isRunning: Boolean = false,
    val runningSinceEpochMillis: Long? = null,
    val activeOperation: UiAgentOperation? = null,
)

data class UiAgentDraft(
    val id: String,
    val name: String,
    val systemPrompt: String,
    val description: String? = null,
    val tools: List<UiToolRef> = emptyList(),
    val agentTools: List<UiAgentToolRef> = emptyList(),
    val prompts: List<UiPromptRef> = emptyList(),
    val resources: List<UiResourceRef> = emptyList(),
    val promptsConfigured: Boolean = true,
    val resourcesConfigured: Boolean = true,
    val originalId: String? = null,
    val orderIndex: Int = 0,
    val schedule: UiAgentSchedule? = null,
    val manualLaunchDefaults: UiAgentManualLaunchDefaults? = null,
)

data class UiAgentProviderConfig(
    val baseUrl: String = "",
    val hasSavedApiKey: Boolean = false,
)

data class UiAgentAiFeaturesSettings(
    val enabled: Boolean = false,
    val runtime: UiAgentRuntime = UiAgentRuntime.LANGCHAIN,
    val llm: UiAgentLlmConfig = defaultUiAgentLlmConfig(),
    val codex: UiAgentCodexConfig = UiAgentCodexConfig(),
)

data class UiAgentProviderSettings(
    val enableCodexProvider: Boolean = false,
    val agentsDirectoryPath: String = "",
    val openAi: UiAgentProviderConfig = UiAgentProviderConfig(),
    val anthropic: UiAgentProviderConfig = UiAgentProviderConfig(),
    val lmStudio: UiAgentProviderConfig = UiAgentProviderConfig(),
    val modelCache: UiAgentProviderModelCache = UiAgentProviderModelCache(),
    val codex: UiAgentCodexGlobalSettings = UiAgentCodexGlobalSettings(),
    val aiFeatures: UiAgentAiFeaturesSettings = UiAgentAiFeaturesSettings(),
)

data class UiAgentProviderModelCache(
    val openAi: List<String> = emptyList(),
    val anthropic: List<String> = emptyList(),
    val lmStudio: List<String> = emptyList(),
    val codex: List<String> = emptyList(),
    val codexFetchedAtEpochMillis: Long? = null,
)

data class UiAgentCodexGlobalSettings(
    val command: String = "codex",
    val portRangeStart: Int = 39600,
    val portRangeEnd: Int = 39699,
)

fun latestFailedRunsByAgent(runs: List<UiRunSummary>): Map<String, UiRunSummary> {
    if (runs.isEmpty()) {
        return emptyMap()
    }
    val latestRunByAgent = linkedMapOf<String, UiRunSummary>()
    runs
        .sortedWith(
            compareByDescending<UiRunSummary> { it.startedAtEpochMillis }
                .thenByDescending { it.finishedAtEpochMillis }
                .thenBy { it.runId },
        ).forEach { run ->
            latestRunByAgent.putIfAbsent(run.agentId, run)
        }
    return latestRunByAgent
        .filterValues { it.status == UiAgentRunStatus.FAILED }
}
