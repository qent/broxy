package io.qent.broxy.ui.adapter.agents

import io.qent.broxy.ui.adapter.models.UiAgent
import io.qent.broxy.ui.adapter.models.UiAgentAiFeaturesSettings
import io.qent.broxy.ui.adapter.models.UiAgentCodexConfig
import io.qent.broxy.ui.adapter.models.UiAgentDraft
import io.qent.broxy.ui.adapter.models.UiAgentFileSystemSettings
import io.qent.broxy.ui.adapter.models.UiAgentGenerationStage
import io.qent.broxy.ui.adapter.models.UiAgentLlmConfig
import io.qent.broxy.ui.adapter.models.UiAgentOperation
import io.qent.broxy.ui.adapter.models.UiAgentProviderSettings
import io.qent.broxy.ui.adapter.models.UiAgentRuntime
import io.qent.broxy.ui.adapter.models.UiGeneratedAgentDraft
import io.qent.broxy.ui.adapter.models.UiLlmProvider
import io.qent.broxy.ui.adapter.models.UiRunDetails
import io.qent.broxy.ui.adapter.models.UiRunSummary
import io.qent.broxy.ui.adapter.models.UiSchedulePreview
import io.qent.broxy.ui.adapter.models.UiServerCapsSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

sealed interface UiAgentExecutionUpdate {
    data class Running(
        val agentId: String,
        val startedAtEpochMillis: Long,
    ) : UiAgentExecutionUpdate

    data class Operation(
        val agentId: String,
        val operation: UiAgentOperation,
    ) : UiAgentExecutionUpdate

    data class Finished(
        val agentId: String,
        val run: UiRunSummary,
    ) : UiAgentExecutionUpdate
}

interface AgentGateway {
    val updates: Flow<UiAgentExecutionUpdate>

    fun start()

    fun stop()

    suspend fun listAgents(): List<UiAgent>

    suspend fun getAgentDraft(id: String): UiAgentDraft?

    suspend fun upsertAgent(draft: UiAgentDraft): Result<UiAgent>

    suspend fun deleteAgent(id: String): Result<Unit>

    suspend fun reorderAgents(agentIds: List<String>): Result<List<UiAgent>>

    suspend fun runAgentNow(
        id: String,
        prompt: String,
        llm: UiAgentLlmConfig,
        fileSystem: UiAgentFileSystemSettings,
        runtime: UiAgentRuntime = UiAgentRuntime.LANGCHAIN,
        codex: UiAgentCodexConfig? = null,
    ): Result<Unit>

    suspend fun stopAgent(id: String): Result<Unit>

    suspend fun saveSchedule(
        id: String,
        cron: String,
        prompt: String,
        timezoneId: String,
        llm: UiAgentLlmConfig,
        fileSystem: UiAgentFileSystemSettings,
        runtime: UiAgentRuntime = UiAgentRuntime.LANGCHAIN,
        codex: UiAgentCodexConfig? = null,
    ): Result<UiAgent>

    suspend fun clearSchedule(id: String): Result<UiAgent>

    suspend fun listRuns(): List<UiRunSummary>

    suspend fun loadRun(runId: String): UiRunDetails?

    suspend fun runningAgentIds(): Set<String>

    suspend fun runningAgents(): Map<String, Long>

    suspend fun loadProviderSettings(): UiAgentProviderSettings

    suspend fun saveProviderSettings(settings: UiAgentProviderSettings): Result<UiAgentProviderSettings>

    suspend fun saveProviderApiKey(
        provider: UiLlmProvider,
        apiKey: String,
    ): Result<Unit>

    suspend fun clearProviderApiKey(provider: UiLlmProvider): Result<Unit>

    suspend fun listProviderModels(
        provider: UiLlmProvider,
        forceRefresh: Boolean = false,
    ): Result<List<String>>

    suspend fun listCodexModels(forceRefresh: Boolean = false): Result<List<String>>

    suspend fun generateAgentDescription(
        draft: UiAgentDraft,
        capabilitySnapshots: List<UiServerCapsSnapshot>,
    ): Result<String>

    suspend fun generateAgentFromRequest(
        request: String,
        capabilitySnapshots: List<UiServerCapsSnapshot>,
        aiFeaturesOverride: UiAgentAiFeaturesSettings? = null,
        onProgress: (UiAgentGenerationStage) -> Unit = {},
    ): Result<UiGeneratedAgentDraft>

    suspend fun previewSchedule(
        cron: String,
        timezoneId: String,
        limit: Int = 3,
    ): Result<UiSchedulePreview>
}

object NoopAgentGateway : AgentGateway {
    override val updates: Flow<UiAgentExecutionUpdate> = emptyFlow()

    override fun start() {
    }

    override fun stop() {
    }

    override suspend fun listAgents(): List<UiAgent> = emptyList()

    override suspend fun getAgentDraft(id: String): UiAgentDraft? = null

    override suspend fun upsertAgent(draft: UiAgentDraft): Result<UiAgent> = Result.failure(IllegalStateException("Agents are unavailable"))

    override suspend fun deleteAgent(id: String): Result<Unit> = Result.failure(IllegalStateException("Agents are unavailable"))

    override suspend fun reorderAgents(agentIds: List<String>): Result<List<UiAgent>> =
        Result.failure(IllegalStateException("Agents are unavailable"))

    override suspend fun runAgentNow(
        id: String,
        prompt: String,
        llm: UiAgentLlmConfig,
        fileSystem: UiAgentFileSystemSettings,
        runtime: UiAgentRuntime,
        codex: UiAgentCodexConfig?,
    ): Result<Unit> = Result.failure(IllegalStateException("Agents are unavailable"))

    override suspend fun stopAgent(id: String): Result<Unit> = Result.failure(IllegalStateException("Agents are unavailable"))

    override suspend fun saveSchedule(
        id: String,
        cron: String,
        prompt: String,
        timezoneId: String,
        llm: UiAgentLlmConfig,
        fileSystem: UiAgentFileSystemSettings,
        runtime: UiAgentRuntime,
        codex: UiAgentCodexConfig?,
    ): Result<UiAgent> = Result.failure(IllegalStateException("Agents are unavailable"))

    override suspend fun clearSchedule(id: String): Result<UiAgent> = Result.failure(IllegalStateException("Agents are unavailable"))

    override suspend fun listRuns(): List<UiRunSummary> = emptyList()

    override suspend fun loadRun(runId: String): UiRunDetails? = null

    override suspend fun runningAgentIds(): Set<String> = emptySet()

    override suspend fun runningAgents(): Map<String, Long> = emptyMap()

    override suspend fun loadProviderSettings(): UiAgentProviderSettings = UiAgentProviderSettings()

    override suspend fun saveProviderSettings(settings: UiAgentProviderSettings): Result<UiAgentProviderSettings> =
        Result.failure(IllegalStateException("Agents are unavailable"))

    override suspend fun saveProviderApiKey(
        provider: UiLlmProvider,
        apiKey: String,
    ): Result<Unit> = Result.failure(IllegalStateException("Agents are unavailable"))

    override suspend fun clearProviderApiKey(provider: UiLlmProvider): Result<Unit> =
        Result.failure(IllegalStateException("Agents are unavailable"))

    override suspend fun listProviderModels(
        provider: UiLlmProvider,
        forceRefresh: Boolean,
    ): Result<List<String>> = Result.failure(IllegalStateException("Agents are unavailable"))

    override suspend fun listCodexModels(forceRefresh: Boolean): Result<List<String>> =
        Result.failure(IllegalStateException("Agents are unavailable"))

    override suspend fun generateAgentDescription(
        draft: UiAgentDraft,
        capabilitySnapshots: List<UiServerCapsSnapshot>,
    ): Result<String> = Result.failure(IllegalStateException("Agents are unavailable"))

    override suspend fun generateAgentFromRequest(
        request: String,
        capabilitySnapshots: List<UiServerCapsSnapshot>,
        aiFeaturesOverride: UiAgentAiFeaturesSettings?,
        onProgress: (UiAgentGenerationStage) -> Unit,
    ): Result<UiGeneratedAgentDraft> = Result.failure(IllegalStateException("Agents are unavailable"))

    override suspend fun previewSchedule(
        cron: String,
        timezoneId: String,
        limit: Int,
    ): Result<UiSchedulePreview> = Result.failure(IllegalStateException("Agents are unavailable"))
}
