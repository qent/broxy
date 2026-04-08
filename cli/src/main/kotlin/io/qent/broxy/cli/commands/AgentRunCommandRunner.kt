@file:Suppress("LongMethod", "TooManyFunctions")

package io.qent.broxy.cli.commands

import io.qent.broxy.agents.AgentCodexConfig
import io.qent.broxy.agents.AgentCodexReasoningEffort
import io.qent.broxy.agents.AgentDefinition
import io.qent.broxy.agents.AgentExecutionOperation
import io.qent.broxy.agents.AgentExecutionRequest
import io.qent.broxy.agents.AgentExecutor
import io.qent.broxy.agents.AgentFileSystemAccess
import io.qent.broxy.agents.AgentFileSystemSettings
import io.qent.broxy.agents.AgentLlmConfig
import io.qent.broxy.agents.AgentProviderSettings
import io.qent.broxy.agents.AgentRunActionType
import io.qent.broxy.agents.AgentRuntime
import io.qent.broxy.agents.DEFAULT_AGENT_WORKSPACE_PATH
import io.qent.broxy.agents.DEFAULT_CODEX_MODEL
import io.qent.broxy.agents.LlmProvider
import io.qent.broxy.agents.application.HybridAgentExecutor
import io.qent.broxy.agents.codex.CodexCliExecutor
import io.qent.broxy.agents.defaultAgentLlmConfig
import io.qent.broxy.agents.infrastructure.persistence.AgentSidecarMetadata
import io.qent.broxy.agents.infrastructure.persistence.ClaudeSubagentMarkdownCodec
import io.qent.broxy.agents.runtime.langchain.LangChain4jAgentExecutor
import io.qent.broxy.cli.support.CliLoggerFactory
import io.qent.broxy.core.config.JsonConfigurationRepository
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.utils.ConfigurationException
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.io.path.absolutePathString

internal const val DEFAULT_AGENT_RUN_TIMEOUT_SECONDS = 300L
internal const val ENV_AGENT_OPENAI_API_KEY = "BROXY_AGENT_OPENAI_API_KEY"
internal const val ENV_AGENT_ANTHROPIC_API_KEY = "BROXY_AGENT_ANTHROPIC_API_KEY"

private const val MCP_FILE_NAME = "mcp.json"
private const val OPENAI_SECRET_KEY = "openai_api_key"
private const val ANTHROPIC_SECRET_KEY = "anthropic_api_key"
private const val MILLIS_PER_SECOND = 1_000L
private const val AGENT_MARKDOWN_FILE_SUFFIX = ".md"
private const val AGENT_SIDECAR_DIR = "metadata"

internal enum class AgentOutputFormat {
    TEXT,
    JSON,
}

internal data class AgentRunCliOptions(
    val mcpConfigFile: File,
    val agentConfigFile: File,
    val agentSettingsFile: File?,
    val agentsSecretsFile: File?,
    val stateDir: File,
    val prompt: String?,
    val runtime: AgentRuntime?,
    val provider: LlmProvider?,
    val model: String?,
    val temperature: Double?,
    val workspace: String?,
    val fileSystemAccess: AgentFileSystemAccess?,
    val codexModel: String?,
    val codexReasoningEffort: AgentCodexReasoningEffort?,
    val codexWebSearch: Boolean?,
    val output: AgentOutputFormat,
    val timeoutSeconds: Long,
    val logLevel: LogLevelOption,
)

internal data class AgentRunCommandResult(
    val exitCode: Int,
    val standardOutput: String? = null,
    val errorOutput: String? = null,
)

internal data class AgentRunCommandRunnerDependencies(
    val loggerFactory: (LogLevelOption, Path) -> Logger = CliLoggerFactory::create,
    val now: () -> Long = { System.currentTimeMillis() },
    val env: (String) -> String? = { key -> System.getenv(key) },
    val executorFactory: (Logger, Path) -> AgentExecutor = { logger, stateDir ->
        HybridAgentExecutor(
            langChainExecutor = LangChain4jAgentExecutor(logger = logger, oauthStateStoreBaseDir = stateDir),
            codexExecutor = CodexCliExecutor(logger = logger, oauthStateStoreBaseDir = stateDir),
        )
    },
)

internal open class AgentRunCommandRunner(
    private val dependencies: AgentRunCommandRunnerDependencies = AgentRunCommandRunnerDependencies(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val outputJson = Json { prettyPrint = false }
    private val markdownCodec = ClaudeSubagentMarkdownCodec()

    open fun run(options: AgentRunCliOptions): AgentRunCommandResult {
        val stateDir =
            options.stateDir
                .toPath()
                .toAbsolutePath()
                .normalize()
        val logger = dependencies.loggerFactory(options.logLevel, stateDir)
        return runBlocking { execute(options, logger, stateDir) }
    }

    private suspend fun execute(
        options: AgentRunCliOptions,
        logger: Logger,
        stateDir: Path,
    ): AgentRunCommandResult {
        val startedAt = dependencies.now()
        val toolCalls = mutableListOf<ToolCallEntry>()
        var cycleErrorHint: String? = null

        val preparedResult =
            runCatching {
                val mcpConfig = loadMcpConfig(options.mcpConfigFile, logger)
                val agent = loadAgent(options.agentConfigFile, options.agentSettingsFile, stateDir)
                val providerSettings = loadProviderSettings(options.agentSettingsFile)
                val launch = resolveLaunch(agent, options)
                if (launch.runtime == AgentRuntime.CODEX_CLI && !providerSettings.enableCodexProvider) {
                    error("Codex provider is disabled in agent settings")
                }
                val apiKeys = loadApiKeys(options.agentsSecretsFile)
                val apiKey = resolveApiKey(launch.llm.provider, apiKeys)
                val executor = dependencies.executorFactory(logger, stateDir)
                val request =
                    AgentExecutionRequest(
                        agent = agent,
                        runtime = launch.runtime,
                        llm = launch.llm,
                        codex = launch.codex,
                        prompt = launch.prompt,
                        fileSystem = launch.fileSystem,
                        providerSettings = providerSettings,
                        mcpConfig = mcpConfig,
                        apiKey = apiKey,
                        apiKeys = apiKeys.toProviderMap(),
                        resolveAgentById = { id ->
                            loadSiblingAgent(
                                rootAgentFile = options.agentConfigFile,
                                agentId = id,
                                settingsFile = options.agentSettingsFile,
                                stateDir = stateDir,
                            )
                        },
                        executeNestedAgent = { nestedRequest -> executor.execute(nestedRequest) },
                        onOperation = { operation ->
                            if (operation is AgentExecutionOperation.ToolExecution) {
                                toolCalls +=
                                    ToolCallEntry(
                                        serverId = operation.serverId,
                                        toolName = operation.toolName,
                                        step = operation.step,
                                    )
                            }
                        },
                        onTraceAction = { action ->
                            if (action.type == AgentRunActionType.TOOL_RESULT) {
                                val message = action.errorMessage?.trim().orEmpty()
                                if (message.contains("agent tool cycle detected", ignoreCase = true)) {
                                    cycleErrorHint = message
                                }
                            }
                        },
                    )
                PreparedExecution(
                    request = request,
                    runtime = launch.runtime,
                    executor = executor,
                )
            }
        val prepared = preparedResult.getOrNull()
        if (prepared == null) {
            return buildFailure(
                format = options.output,
                runtime = null,
                message = preparedResult.exceptionOrNull()?.message ?: "Failed to prepare agent execution",
                toolCalls = emptyList(),
                durationMillis = dependencies.now() - startedAt,
            )
        }

        val executionResult =
            runCatching {
                withTimeout(options.timeoutSeconds * MILLIS_PER_SECOND) {
                    prepared.executor.execute(prepared.request).getOrThrow()
                }
            }
        val execution = executionResult.getOrNull()
        return if (execution != null) {
            buildSuccess(
                format = options.output,
                runtime = prepared.runtime,
                response = execution.response,
                toolCalls = toolCalls,
                durationMillis = dependencies.now() - startedAt,
            )
        } else {
            val failure = executionResult.exceptionOrNull()
            val message =
                if (failure is TimeoutCancellationException) {
                    "Agent run timed out after ${options.timeoutSeconds} seconds"
                } else {
                    cycleErrorHint
                        ?: failure.findAgentToolCycleMessage()
                        ?: failure?.message
                        ?: "Agent execution failed"
                }
            buildFailure(
                format = options.output,
                runtime = prepared.runtime,
                message = message,
                toolCalls = toolCalls,
                durationMillis = dependencies.now() - startedAt,
            )
        }
    }

    private fun loadMcpConfig(
        file: File,
        logger: Logger,
    ): McpServersConfig {
        val source = file.toPath().toAbsolutePath().normalize()
        require(Files.isRegularFile(source)) { "MCP config file not found: ${source.absolutePathString()}" }
        if (source.fileName.toString() == MCP_FILE_NAME) {
            val baseDir = source.parent ?: Paths.get(".").toAbsolutePath().normalize()
            return JsonConfigurationRepository(baseDir = baseDir, logger = logger).loadMcpConfig()
        }

        val tempDir = Files.createTempDirectory("broxy-agent-cli-")
        return try {
            Files.copy(source, tempDir.resolve(MCP_FILE_NAME), StandardCopyOption.REPLACE_EXISTING)
            JsonConfigurationRepository(baseDir = tempDir, logger = logger).loadMcpConfig()
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    private fun loadAgent(
        file: File,
        settingsFile: File?,
        stateDir: Path,
    ): AgentDefinition {
        val path = file.toPath().toAbsolutePath().normalize()
        require(Files.isRegularFile(path)) { "Agent config file not found: ${path.absolutePathString()}" }
        val id = resolveAgentId(path)
        val sidecar = loadAgentSidecar(id, settingsFile, stateDir)
        val text = Files.readString(path)
        return runCatching {
            markdownCodec.decodeAgentDefinition(
                text = text,
                fileName = path.fileName.toString(),
                agentId = id,
                sidecar = sidecar,
            )
        }.getOrElse { error ->
            val message =
                when (error) {
                    is ConfigurationException -> "Invalid agent markdown config: ${error.message}"
                    else -> "Invalid agent markdown config: ${error.message}"
                }
            throw IllegalArgumentException(message, error)
        }
    }

    @Suppress("ReturnCount")
    private fun loadSiblingAgent(
        rootAgentFile: File,
        agentId: String,
        settingsFile: File?,
        stateDir: Path,
    ): AgentDefinition? {
        val normalizedId = agentId.trim()
        if (normalizedId.isBlank()) {
            return null
        }
        val rootPath = rootAgentFile.toPath().toAbsolutePath().normalize()
        val siblingPath = rootPath.parent?.resolve("$normalizedId$AGENT_MARKDOWN_FILE_SUFFIX") ?: return null
        if (!Files.isRegularFile(siblingPath)) {
            return null
        }
        val sidecar = loadAgentSidecar(normalizedId, settingsFile, stateDir)
        val text = Files.readString(siblingPath)
        return runCatching {
            markdownCodec.decodeAgentDefinition(
                text = text,
                fileName = siblingPath.fileName.toString(),
                agentId = normalizedId,
                sidecar = sidecar,
            )
        }.getOrNull()
    }

    private fun resolveAgentId(path: Path): String {
        val fileName = path.fileName.toString()
        require(fileName.endsWith(AGENT_MARKDOWN_FILE_SUFFIX)) {
            "Agent config must be a Claude markdown file (*.md): ${path.absolutePathString()}"
        }
        val id = fileName.removeSuffix(AGENT_MARKDOWN_FILE_SUFFIX).trim()
        require(id.isNotBlank()) { "Agent file name must include non-blank id: ${path.absolutePathString()}" }
        return id
    }

    private fun loadAgentSidecar(
        agentId: String,
        settingsFile: File?,
        stateDir: Path,
    ): AgentSidecarMetadata {
        val storageRoot = resolveAgentStorageRoot(settingsFile, stateDir)
        val sidecarFile = storageRoot.resolve(AGENT_SIDECAR_DIR).resolve("agent_$agentId.json")
        if (!Files.isRegularFile(sidecarFile)) {
            return AgentSidecarMetadata()
        }
        val text = Files.readString(sidecarFile)
        return runCatching {
            json.decodeFromString(AgentSidecarMetadata.serializer(), text)
        }.getOrElse {
            AgentSidecarMetadata()
        }
    }

    private fun resolveAgentStorageRoot(
        settingsFile: File?,
        stateDir: Path,
    ): Path {
        val explicitSettingsRoot =
            settingsFile
                ?.toPath()
                ?.toAbsolutePath()
                ?.normalize()
                ?.parent
        return explicitSettingsRoot ?: stateDir.resolve("agents")
    }

    private fun loadProviderSettings(file: File?): AgentProviderSettings {
        if (file == null) {
            return AgentProviderSettings()
        }
        val path = file.toPath().toAbsolutePath().normalize()
        require(Files.isRegularFile(path)) { "Agent settings file not found: ${path.absolutePathString()}" }
        val text = Files.readString(path)
        return try {
            json.decodeFromString(AgentProviderSettings.serializer(), text)
        } catch (error: SerializationException) {
            throw IllegalArgumentException("Invalid agent settings JSON: ${error.message}", error)
        }
    }

    private fun loadApiKeys(file: File?): ApiKeys {
        val fileKeys =
            if (file == null) {
                ApiKeys()
            } else {
                val path = file.toPath().toAbsolutePath().normalize()
                require(Files.isRegularFile(path)) { "Agent secrets file not found: ${path.absolutePathString()}" }
                val text = Files.readString(path)
                val payload =
                    try {
                        json.decodeFromString(AgentSecretsPayload.serializer(), text)
                    } catch (error: SerializationException) {
                        throw IllegalArgumentException("Invalid agent secrets JSON: ${error.message}", error)
                    }
                ApiKeys(
                    openAi =
                        payload.values[OPENAI_SECRET_KEY]
                            ?.trim()
                            .orEmpty()
                            .ifBlank { null },
                    anthropic =
                        payload.values[ANTHROPIC_SECRET_KEY]
                            ?.trim()
                            .orEmpty()
                            .ifBlank { null },
                )
            }

        return ApiKeys(
            openAi =
                dependencies
                    .env(ENV_AGENT_OPENAI_API_KEY)
                    ?.trim()
                    .orEmpty()
                    .ifBlank { fileKeys.openAi },
            anthropic =
                dependencies
                    .env(ENV_AGENT_ANTHROPIC_API_KEY)
                    ?.trim()
                    .orEmpty()
                    .ifBlank { fileKeys.anthropic },
        )
    }

    private fun resolveLaunch(
        agent: AgentDefinition,
        options: AgentRunCliOptions,
    ): LaunchParameters {
        val base = resolveBaseLaunch(agent)
        val prompt = resolvePrompt(options.prompt, base.prompt)
        val model = resolveRequiredModel(options.model, base.llm.model)
        val codexModel = resolveCodexModel(options.codexModel, base.codex.model)
        val workspace = resolveWorkspace(options.workspace, base.fileSystem.path)

        return LaunchParameters(
            prompt = prompt,
            runtime = options.runtime ?: base.runtime,
            llm =
                AgentLlmConfig(
                    provider = options.provider ?: base.llm.provider,
                    model = model,
                    temperature = options.temperature ?: base.llm.temperature,
                ),
            codex =
                AgentCodexConfig(
                    model = codexModel,
                    reasoningEffort = options.codexReasoningEffort ?: base.codex.reasoningEffort,
                    webSearch = options.codexWebSearch ?: base.codex.webSearch,
                ),
            fileSystem =
                AgentFileSystemSettings(
                    path = workspace,
                    access = options.fileSystemAccess ?: base.fileSystem.access,
                ),
        )
    }

    private fun resolveApiKey(
        provider: LlmProvider,
        keys: ApiKeys,
    ): String? =
        when (provider) {
            LlmProvider.OPENAI ->
                checkNotNull(keys.openAi) {
                    "Missing OpenAI API key. " +
                        "Set $ENV_AGENT_OPENAI_API_KEY or provide $OPENAI_SECRET_KEY in --agents-secrets"
                }
            LlmProvider.ANTHROPIC ->
                checkNotNull(keys.anthropic) {
                    "Missing Anthropic API key. " +
                        "Set $ENV_AGENT_ANTHROPIC_API_KEY or provide $ANTHROPIC_SECRET_KEY in --agents-secrets"
                }
            LlmProvider.LM_STUDIO -> null
        }

    private fun buildSuccess(
        format: AgentOutputFormat,
        runtime: AgentRuntime,
        response: String,
        toolCalls: List<ToolCallEntry>,
        durationMillis: Long,
    ): AgentRunCommandResult =
        when (format) {
            AgentOutputFormat.TEXT ->
                AgentRunCommandResult(
                    exitCode = 0,
                    standardOutput = response,
                )
            AgentOutputFormat.JSON ->
                AgentRunCommandResult(
                    exitCode = 0,
                    standardOutput =
                        outputJson.encodeToString(
                            AgentRunOutput.serializer(),
                            AgentRunOutput(
                                status = "SUCCESS",
                                runtime = runtime.name,
                                response = response,
                                errorMessage = null,
                                toolCalls = toolCalls,
                                durationMillis = durationMillis.coerceAtLeast(0L),
                            ),
                        ),
                )
        }

    private fun buildFailure(
        format: AgentOutputFormat,
        runtime: AgentRuntime?,
        message: String,
        toolCalls: List<ToolCallEntry>,
        durationMillis: Long,
    ): AgentRunCommandResult =
        when (format) {
            AgentOutputFormat.TEXT ->
                AgentRunCommandResult(
                    exitCode = 1,
                    errorOutput = message,
                )
            AgentOutputFormat.JSON ->
                AgentRunCommandResult(
                    exitCode = 1,
                    standardOutput =
                        outputJson.encodeToString(
                            AgentRunOutput.serializer(),
                            AgentRunOutput(
                                status = "FAILED",
                                runtime = runtime?.name,
                                response = null,
                                errorMessage = message,
                                toolCalls = toolCalls,
                                durationMillis = durationMillis.coerceAtLeast(0L),
                            ),
                        ),
                )
        }
}

private data class LaunchParameters(
    val prompt: String,
    val runtime: AgentRuntime,
    val llm: AgentLlmConfig,
    val codex: AgentCodexConfig,
    val fileSystem: AgentFileSystemSettings,
)

private data class BaseLaunch(
    val prompt: String,
    val runtime: AgentRuntime,
    val llm: AgentLlmConfig,
    val codex: AgentCodexConfig,
    val fileSystem: AgentFileSystemSettings,
)

private data class PreparedExecution(
    val request: AgentExecutionRequest,
    val runtime: AgentRuntime,
    val executor: AgentExecutor,
)

private data class ApiKeys(
    val openAi: String? = null,
    val anthropic: String? = null,
)

private fun ApiKeys.toProviderMap(): Map<LlmProvider, String> =
    buildMap {
        openAi?.trim()?.takeIf { it.isNotBlank() }?.let { put(LlmProvider.OPENAI, it) }
        anthropic?.trim()?.takeIf { it.isNotBlank() }?.let { put(LlmProvider.ANTHROPIC, it) }
    }

private fun resolveBaseLaunch(agent: AgentDefinition): BaseLaunch {
    val manual = agent.manualLaunchDefaults
    val schedule = agent.schedule
    return BaseLaunch(
        prompt = manual?.prompt ?: schedule?.prompt.orEmpty(),
        runtime = manual?.runtime ?: schedule?.runtime ?: AgentRuntime.LANGCHAIN,
        llm = manual?.llm ?: schedule?.llm ?: defaultAgentLlmConfig(),
        codex = manual?.codex ?: schedule?.codex ?: AgentCodexConfig(),
        fileSystem = manual?.fileSystem ?: schedule?.fileSystem ?: AgentFileSystemSettings(),
    )
}

private fun resolvePrompt(
    overridePrompt: String?,
    basePrompt: String,
): String {
    val prompt = (overridePrompt ?: basePrompt).trim()
    require(prompt.isNotBlank()) { "Prompt cannot be blank (use --prompt or set defaults in agent config)" }
    return prompt
}

private fun resolveRequiredModel(
    overrideModel: String?,
    baseModel: String,
): String {
    val model =
        overrideModel
            ?.trim()
            .orEmpty()
            .ifBlank { baseModel.trim() }
    require(model.isNotBlank()) { "Model cannot be blank" }
    return model
}

private fun resolveCodexModel(
    overrideModel: String?,
    baseModel: String,
): String =
    overrideModel
        ?.trim()
        .orEmpty()
        .ifBlank { baseModel.trim().ifBlank { DEFAULT_CODEX_MODEL } }

private fun resolveWorkspace(
    overrideWorkspace: String?,
    baseWorkspace: String,
): String =
    overrideWorkspace
        ?.trim()
        .orEmpty()
        .ifBlank { baseWorkspace.trim() }
        .ifBlank { DEFAULT_AGENT_WORKSPACE_PATH }

private fun Throwable?.findAgentToolCycleMessage(): String? {
    var current = this
    while (current != null) {
        val message = current.message?.trim().orEmpty()
        if (message.contains("agent tool cycle detected", ignoreCase = true)) {
            return message
        }
        current = current.cause
    }
    return null
}

@Serializable
private data class AgentSecretsPayload(
    val values: Map<String, String> = emptyMap(),
)

@Serializable
internal data class ToolCallEntry(
    val serverId: String,
    val toolName: String,
    val step: Int,
)

@Serializable
private data class AgentRunOutput(
    val status: String,
    val runtime: String?,
    val response: String?,
    val errorMessage: String?,
    val toolCalls: List<ToolCallEntry>,
    val durationMillis: Long,
)
