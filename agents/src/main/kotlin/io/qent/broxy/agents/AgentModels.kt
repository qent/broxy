package io.qent.broxy.agents

import io.qent.broxy.core.models.AgentToolReference
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.PromptReference
import io.qent.broxy.core.models.ResourceReference
import io.qent.broxy.core.models.ToolReference
import kotlinx.serialization.Serializable

const val DEFAULT_AGENT_WORKSPACE_PATH: String = "/tmp/broxy/agents"

@Serializable
enum class LlmProvider {
    OPENAI,
    ANTHROPIC,
    LM_STUDIO,
}

@Serializable
enum class AgentRuntime {
    LANGCHAIN,
    CODEX_CLI,
}

@Serializable
enum class AgentCodexReasoningEffort {
    LOW,
    MEDIUM,
    HIGH,
}

@Serializable
data class AgentCodexConfig(
    val model: String = DEFAULT_CODEX_MODEL,
    val reasoningEffort: AgentCodexReasoningEffort = DEFAULT_CODEX_REASONING_EFFORT,
    val webSearch: Boolean = false,
)

@Serializable
data class AgentLlmConfig(
    val provider: LlmProvider,
    val model: String,
    val temperature: Double = 0.2,
)

@Serializable
enum class AgentRunStatus {
    SUCCESS,
    FAILED,
    SKIPPED,
}

@Serializable
enum class AgentRunTrigger {
    MANUAL,
    SCHEDULED,
}

@Serializable
data class AgentRunSummary(
    val runId: String,
    val agentId: String,
    val agentName: String,
    val trigger: AgentRunTrigger,
    val status: AgentRunStatus,
    val runtime: AgentRuntime,
    val prompt: String,
    val response: String? = null,
    val errorMessage: String? = null,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long,
)

@Serializable
enum class AgentRunDialogueRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL,
}

@Serializable
data class AgentRunDialogueEntry(
    val role: AgentRunDialogueRole,
    val content: String,
    val step: Int? = null,
    val serverId: String? = null,
    val toolName: String? = null,
    val timestampEpochMillis: Long,
)

@Serializable
enum class AgentRunActionType {
    PREPARING_RUN,
    LOADING_CAPABILITIES,
    LLM_REQUEST,
    LLM_THINKING,
    LLM_RESPONSE_GENERATION,
    TOOL_CALL,
    TOOL_RESULT,
    RUNTIME_EVENT,
}

@Serializable
data class AgentRunActionEntry(
    val type: AgentRunActionType,
    val step: Int? = null,
    val serverId: String? = null,
    val toolName: String? = null,
    val requestPayload: String? = null,
    val responsePayload: String? = null,
    val errorMessage: String? = null,
    val message: String? = null,
    val timestampEpochMillis: Long,
)

@Serializable
data class AgentRunDetails(
    val summary: AgentRunSummary,
    val systemPrompt: String,
    val llm: AgentLlmConfig,
    val codex: AgentCodexConfig? = null,
    val fileSystem: AgentFileSystemSettings,
    val dialogue: List<AgentRunDialogueEntry> = emptyList(),
    val actions: List<AgentRunActionEntry> = emptyList(),
)

@Serializable
enum class AgentFileSystemAccess {
    NONE,
    READ_ONLY,
    READ_WRITE,
}

@Serializable
data class AgentFileSystemSettings(
    val path: String = DEFAULT_AGENT_WORKSPACE_PATH,
    val access: AgentFileSystemAccess = AgentFileSystemAccess.NONE,
)

@Serializable
data class AgentSchedule(
    val cron: String,
    val prompt: String,
    val timezoneId: String,
    val runtime: AgentRuntime,
    val llm: AgentLlmConfig,
    val codex: AgentCodexConfig? = null,
    val fileSystem: AgentFileSystemSettings,
)

@Serializable
data class AgentManualLaunchDefaults(
    val prompt: String,
    val runtime: AgentRuntime,
    val llm: AgentLlmConfig,
    val codex: AgentCodexConfig? = null,
    val fileSystem: AgentFileSystemSettings,
)

@Serializable
data class AgentMcpServerReference(
    val id: String,
    val inlineConfig: McpServerConfig? = null,
)

@Serializable
data class AgentDefinition(
    val id: String,
    val name: String,
    val systemPrompt: String,
    val description: String? = null,
    val tools: List<ToolReference> = emptyList(),
    val agentTools: List<AgentToolReference> = emptyList(),
    val prompts: List<PromptReference>? = null,
    val resources: List<ResourceReference>? = null,
    val claudeTools: List<String>? = null,
    val claudeDisallowedTools: List<String>? = null,
    val claudePermissionMode: String? = null,
    val claudeMcpServers: List<AgentMcpServerReference>? = null,
    val orderIndex: Int = 0,
    val schedule: AgentSchedule? = null,
    val manualLaunchDefaults: AgentManualLaunchDefaults? = null,
)

@Serializable
data class AgentProviderConfig(
    val baseUrl: String? = null,
)

@Serializable
data class AgentProviderSettings(
    val enableCodexProvider: Boolean = false,
    val agentsDirectoryPath: String? = null,
    val openAi: AgentProviderConfig = AgentProviderConfig(),
    val anthropic: AgentProviderConfig = AgentProviderConfig(),
    val lmStudio: AgentProviderConfig = AgentProviderConfig(),
    val modelCache: AgentProviderModelCache = AgentProviderModelCache(),
    val codex: AgentCodexGlobalSettings = AgentCodexGlobalSettings(),
    val aiFeatures: AgentAiFeaturesSettings = AgentAiFeaturesSettings(),
)

@Serializable
data class AgentAiFeaturesSettings(
    val enabled: Boolean = false,
    val runtime: AgentRuntime = AgentRuntime.LANGCHAIN,
    val llm: AgentLlmConfig = defaultAgentLlmConfig(),
    val codex: AgentCodexConfig = AgentCodexConfig(),
)

@Serializable
data class AgentProviderModelCache(
    val openAi: List<String> = emptyList(),
    val anthropic: List<String> = emptyList(),
    val lmStudio: List<String> = emptyList(),
    val codex: List<String> = emptyList(),
    val codexFetchedAtEpochMillis: Long? = null,
)

@Serializable
data class AgentCodexGlobalSettings(
    val command: String = DEFAULT_CODEX_COMMAND,
    val portRangeStart: Int = DEFAULT_CODEX_PORT_RANGE_START,
    val portRangeEnd: Int = DEFAULT_CODEX_PORT_RANGE_END,
)
