package io.qent.broxy.agents.runtime.langchain

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.agentic.Agent
import dev.langchain4j.agentic.AgenticServices
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.http.client.HttpClientBuilder
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder
import dev.langchain4j.model.anthropic.AnthropicChatModel
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.service.UserMessage
import dev.langchain4j.service.V
import dev.langchain4j.service.tool.ToolExecutor
import dev.langchain4j.service.tool.ToolProvider
import dev.langchain4j.service.tool.ToolProviderResult
import io.qent.broxy.agents.AgentExecutionOperation
import io.qent.broxy.agents.AgentExecutionRequest
import io.qent.broxy.agents.AgentExecutionResult
import io.qent.broxy.agents.AgentExecutor
import io.qent.broxy.agents.AgentFileSystemAccess
import io.qent.broxy.agents.AgentRunActionEntry
import io.qent.broxy.agents.AgentRunActionType
import io.qent.broxy.agents.AgentRunDialogueEntry
import io.qent.broxy.agents.AgentRunDialogueRole
import io.qent.broxy.agents.LlmProvider
import io.qent.broxy.agents.baseUrlFor
import io.qent.broxy.agents.resolveClaudeFileSystemAccess
import io.qent.broxy.agents.resolveClaudePermissionModeWarning
import io.qent.broxy.agents.runtime.filesystem.AgentFileSystemException
import io.qent.broxy.agents.runtime.filesystem.AgentFileSystemSandbox
import io.qent.broxy.agents.runtime.filesystem.AgentFileSystemTools
import io.qent.broxy.agents.runtime.filesystem.AgentFileSystemWorkspace
import io.qent.broxy.agents.runtime.mcp.AGENT_TOOLS_SERVER_ID
import io.qent.broxy.agents.runtime.mcp.OAuthStatePersistence
import io.qent.broxy.agents.runtime.mcp.OAuthStateStoreFactory
import io.qent.broxy.agents.runtime.mcp.ScopedMcpConnectionsFactory
import io.qent.broxy.core.mcp.DefaultMcpServerConnection
import io.qent.broxy.core.mcp.McpServerConnection
import io.qent.broxy.core.mcp.auth.OAuthState
import io.qent.broxy.core.mcp.auth.OAuthStateSnapshot
import io.qent.broxy.core.mcp.auth.OAuthStateStore
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.proxy.DefaultNamespaceManager
import io.qent.broxy.core.proxy.DefaultRequestDispatcher
import io.qent.broxy.core.proxy.DefaultToolFilter
import io.qent.broxy.core.proxy.FilterResult
import io.qent.broxy.core.proxy.RequestDispatcher
import io.qent.broxy.core.proxy.ToolCallRequest
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger
import io.qent.broxy.core.utils.errorJson
import io.qent.broxy.core.utils.infoJson
import io.qent.broxy.core.utils.warnJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import java.net.http.HttpClient
import java.nio.file.Path
import java.nio.file.Paths
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.X509TrustManager

private const val MAX_TOOL_STEPS = 24
private const val FILESYSTEM_SERVER_ID = "filesystem"

private typealias DispatcherFactory =
    (
        connections: List<McpServerConnection>,
        filterResult: FilterResult,
        logger: Logger,
    ) -> RequestDispatcher

private typealias LegacyConnectionFactory =
    (
        config: McpServerConfig,
        logger: Logger,
        maxRetries: Int,
        ignoreHttpsCertificateErrors: Boolean,
        callTimeoutMillis: Long,
        capabilitiesTimeoutMillis: Long,
        authorizationTimeoutMillis: Long,
        authState: OAuthState?,
        authStateObserver: ((OAuthState) -> Unit)?,
    ) -> McpServerConnection

internal interface RuntimeToolAgent {
    @Agent("Executes the prompt using available tools")
    @UserMessage("{{prompt}}")
    fun respond(
        @V("prompt") prompt: String,
    ): String
}

@Suppress("LargeClass", "TooManyFunctions")
class LangChain4jAgentExecutor(
    private val logger: Logger = ConsoleLogger,
    private val modelFactory: ((AgentExecutionRequest) -> ChatModel)? = null,
    private val dispatcherFactory: DispatcherFactory = ::buildDispatcher,
    private val oauthStateStoreBaseDir: Path =
        Paths.get(System.getProperty("user.home"), ".config", "broxy"),
    private val oauthStateStoreFactory: OAuthStateStoreFactory =
        { baseDir, stateLogger ->
            val store = OAuthStateStore(baseDir = baseDir, logger = stateLogger)
            object : OAuthStatePersistence {
                override fun load(
                    serverId: String,
                    resourceUrl: String?,
                ): OAuthStateSnapshot? = store.load(serverId, resourceUrl)

                override fun save(
                    serverId: String,
                    snapshot: OAuthStateSnapshot,
                ) {
                    store.save(serverId, snapshot)
                }
            }
        },
    private val connectionFactory: LegacyConnectionFactory =
        {
            config,
            connectionLogger,
            maxRetries,
            ignoreHttpsCertificateErrors,
            callTimeoutMillis,
            capabilitiesTimeoutMillis,
            authorizationTimeoutMillis,
            authState,
            authStateObserver,
            ->
            DefaultMcpServerConnection(
                config = config,
                logger = connectionLogger,
                maxRetries = maxRetries,
                ignoreHttpsCertificateErrors = ignoreHttpsCertificateErrors,
                authState = authState,
                authStateObserver = authStateObserver,
                initialCallTimeoutMillis = callTimeoutMillis,
                initialCapabilitiesTimeoutMillis = capabilitiesTimeoutMillis,
                initialConnectTimeoutMillis = capabilitiesTimeoutMillis,
                initialAuthorizationTimeoutMillis = authorizationTimeoutMillis,
            )
        },
) : AgentExecutor {
    private val namespaceManager = DefaultNamespaceManager()
    private val scopedConnectionsFactory =
        ScopedMcpConnectionsFactory(
            logger = logger,
            oauthStateStoreBaseDir = oauthStateStoreBaseDir,
            oauthStateStoreFactory = oauthStateStoreFactory,
            connectionFactory = { config, connectionLogger, options ->
                connectionFactory(
                    config,
                    connectionLogger,
                    options.maxRetries,
                    options.ignoreHttpsCertificateErrors,
                    options.callTimeoutMillis,
                    options.capabilitiesTimeoutMillis,
                    options.authorizationTimeoutMillis,
                    options.authState,
                    options.authStateObserver,
                )
            },
        )

    override suspend fun execute(request: AgentExecutionRequest): Result<AgentExecutionResult> =
        runCatching {
            executeInternal(request)
        }.onFailure { failure ->
            logger.errorJson("agent.execution.failed", failure) {
                put("agentId", JsonPrimitive(request.agent.id))
                put("provider", JsonPrimitive(request.llm.provider.name))
                put("model", JsonPrimitive(request.llm.model))
                put("errorMessage", JsonPrimitive(failure.message ?: "Agent execution failed"))
            }
        }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private suspend fun executeInternal(request: AgentExecutionRequest): AgentExecutionResult =
        coroutineScope {
            logger.infoJson("agent.execution.started") {
                put("agentId", JsonPrimitive(request.agent.id))
                put("provider", JsonPrimitive(request.llm.provider.name))
                put("model", JsonPrimitive(request.llm.model))
                put("promptLength", JsonPrimitive(request.prompt.length))
            }
            request.onTraceDialogue(
                AgentRunDialogueEntry(
                    role = AgentRunDialogueRole.SYSTEM,
                    content = request.agent.systemPrompt,
                    timestampEpochMillis = System.currentTimeMillis(),
                ),
            )
            request.onTraceDialogue(
                AgentRunDialogueEntry(
                    role = AgentRunDialogueRole.USER,
                    content = request.prompt,
                    timestampEpochMillis = System.currentTimeMillis(),
                ),
            )

            val permissionModeWarning = resolveClaudePermissionModeWarning(request.agent)
            if (permissionModeWarning != null) {
                emitClaudeCompatibilityWarning(request, permissionModeWarning)
            }
            val fsAccessResolution = resolveClaudeFileSystemAccess(request.agent, request.fileSystem.access)
            fsAccessResolution.warnings.forEach { warning ->
                emitClaudeCompatibilityWarning(request, warning)
            }
            val effectiveRequest =
                if (fsAccessResolution.access == request.fileSystem.access) {
                    request
                } else {
                    request.copy(
                        fileSystem =
                            request.fileSystem.copy(
                                access = fsAccessResolution.access,
                            ),
                    )
                }

            val localWorkspace = prepareWorkspace(effectiveRequest)
            val localFsTools = localWorkspace?.let { AgentFileSystemTools(it) }
            val scopedConnections = scopedConnectionsFactory.create(effectiveRequest)
            val connections = scopedConnections.connections

            try {
                request.onOperation(AgentExecutionOperation.LoadingCapabilities)
                val allCapabilities =
                    connections
                        .map { connection ->
                            async {
                                val capabilities = connection.getCapabilities(forceRefresh = true)
                                if (capabilities.isSuccess) {
                                    capabilities.getOrNull()?.let { resolved ->
                                        logger.infoJson("agent.downstream.capabilities.succeeded") {
                                            put("agentId", JsonPrimitive(request.agent.id))
                                            put("serverId", JsonPrimitive(connection.serverId))
                                            put("toolsCount", JsonPrimitive(resolved.tools.size))
                                            put("promptsCount", JsonPrimitive(resolved.prompts.size))
                                            put("resourcesCount", JsonPrimitive(resolved.resources.size))
                                        }
                                    }
                                } else {
                                    val failure = capabilities.exceptionOrNull()
                                    logger.warnJson("agent.downstream.capabilities.failed", failure) {
                                        put("agentId", JsonPrimitive(request.agent.id))
                                        put("serverId", JsonPrimitive(connection.serverId))
                                        put(
                                            "errorMessage",
                                            JsonPrimitive(failure?.message ?: "Failed to fetch capabilities"),
                                        )
                                    }
                                }
                                connection.serverId to capabilities.getOrNull()
                            }
                        }.awaitAll()
                        .mapNotNull { (serverId, capabilities) ->
                            capabilities?.let { serverId to it }
                        }.toMap()

                val filter = DefaultToolFilter(logger)
                val filtered = filter.filter(allCapabilities, scopedConnections.preset)
                val dispatcher = dispatcherFactory(connections, filtered, logger)
                val model = (modelFactory ?: this@LangChain4jAgentExecutor::buildModel)(effectiveRequest)
                var stepCounter = 0
                var providerConnected = false
                val toolExecutors =
                    linkedMapOf<ToolSpecification, ToolExecutor>().apply {
                        filtered.capabilities.tools.forEach { tool ->
                            val specification =
                                ToolSpecification
                                    .builder()
                                    .name(tool.name)
                                    .description(tool.description ?: tool.title ?: tool.name)
                                    .build()
                            val executor =
                                ToolExecutor { toolRequest, _ ->
                                    executeToolBridge(
                                        executionRequest = effectiveRequest,
                                        dispatcher = dispatcher,
                                        toolRequest = toolRequest,
                                        step = stepCounter.coerceAtLeast(1),
                                    )
                                }
                            this[specification] = executor
                        }
                    }
                val localToolHandlers = linkedMapOf<String, (ToolExecutionRequest) -> String>()
                if (localFsTools != null) {
                    localFsTools
                        .specifications(effectiveRequest.fileSystem.access)
                        .forEach { (toolName, specification) ->
                            val handler: (ToolExecutionRequest) -> String = { toolRequest ->
                                executeLocalFsToolBridge(
                                    executionRequest = effectiveRequest,
                                    toolExecutor = localFsTools,
                                    workspace = checkNotNull(localWorkspace),
                                    toolRequest = toolRequest,
                                    step = stepCounter.coerceAtLeast(1),
                                )
                            }
                            localToolHandlers[toolName] = handler
                            toolExecutors[specification] = ToolExecutor { toolRequest, _ -> handler(toolRequest) }
                        }
                }
                val toolSpecifications = toolExecutors.keys.toList()
                val toolProvider =
                    ToolProvider {
                        ToolProviderResult
                            .builder()
                            .addAll(toolExecutors)
                            .build()
                    }

                val configuredBaseUrl = providerBaseUrlOverride(effectiveRequest)
                val selectedBaseUrl = selectedProviderBaseUrl(effectiveRequest)
                logger.infoJson("agent.llm.model.selected") {
                    put("agentId", JsonPrimitive(request.agent.id))
                    put("provider", JsonPrimitive(request.llm.provider.name))
                    put("model", JsonPrimitive(request.llm.model))
                    put("temperature", JsonPrimitive(request.llm.temperature))
                    put("toolsAvailable", JsonPrimitive(toolSpecifications.size))
                    put("customModelFactory", JsonPrimitive(modelFactory != null))
                    put("baseUrlOverride", JsonPrimitive(configuredBaseUrl != null))
                    put("baseUrl", JsonPrimitive(selectedBaseUrl))
                }
                logger.infoJson("agent.execution.filtered_capabilities") {
                    put("agentId", JsonPrimitive(request.agent.id))
                    put("allowedPrefixedTools", JsonPrimitive(filtered.allowedPrefixedTools.size))
                    put("toolsCount", JsonPrimitive(filtered.capabilities.tools.size))
                    put("promptsCount", JsonPrimitive(filtered.capabilities.prompts.size))
                    put("resourcesCount", JsonPrimitive(filtered.capabilities.resources.size))
                }

                val instrumentedModel =
                    object : ChatModel {
                        override fun chat(chatRequest: ChatRequest): ChatResponse {
                            val step = ++stepCounter
                            request.onOperation(AgentExecutionOperation.LlmRequest(step = step))
                            logger.infoJson("agent.llm.request") {
                                put("agentId", JsonPrimitive(request.agent.id))
                                put("provider", JsonPrimitive(request.llm.provider.name))
                                put("model", JsonPrimitive(request.llm.model))
                                put("step", JsonPrimitive(step))
                                put("conversationSize", JsonPrimitive(chatRequest.messages().size))
                                put(
                                    "toolSpecificationsCount",
                                    JsonPrimitive(chatRequest.toolSpecifications()?.size ?: 0),
                                )
                            }
                            request.onOperation(AgentExecutionOperation.LlmThinking(step = step))
                            val response =
                                runCatching {
                                    model.chat(chatRequest)
                                }.onSuccess {
                                    if (!providerConnected) {
                                        providerConnected = true
                                        logger.infoJson("agent.llm.connection.succeeded") {
                                            put("agentId", JsonPrimitive(request.agent.id))
                                            put("provider", JsonPrimitive(request.llm.provider.name))
                                            put("model", JsonPrimitive(request.llm.model))
                                            put("step", JsonPrimitive(step))
                                        }
                                    }
                                }.onFailure { failure ->
                                    val eventName =
                                        if (!providerConnected) {
                                            "agent.llm.connection.failed"
                                        } else {
                                            "agent.llm.request.failed"
                                        }
                                    logger.errorJson(eventName, failure) {
                                        put("agentId", JsonPrimitive(request.agent.id))
                                        put("provider", JsonPrimitive(request.llm.provider.name))
                                        put("model", JsonPrimitive(request.llm.model))
                                        put("step", JsonPrimitive(step))
                                        put("errorMessage", JsonPrimitive(failure.message ?: "LLM request failed"))
                                    }
                                }.getOrThrow()
                            request.onOperation(AgentExecutionOperation.LlmResponseGeneration(step = step))
                            val aiMessage = response.aiMessage()
                            val toolRequests = aiMessage.toolExecutionRequests().orEmpty()
                            aiMessage.text()?.takeIf { it.isNotBlank() }?.let { assistantText ->
                                request.onTraceDialogue(
                                    AgentRunDialogueEntry(
                                        role = AgentRunDialogueRole.ASSISTANT,
                                        content = assistantText,
                                        step = step,
                                        timestampEpochMillis = System.currentTimeMillis(),
                                    ),
                                )
                            }
                            logger.infoJson("agent.llm.response") {
                                put("agentId", JsonPrimitive(request.agent.id))
                                put("provider", JsonPrimitive(request.llm.provider.name))
                                put("model", JsonPrimitive(request.llm.model))
                                put("step", JsonPrimitive(step))
                                put("toolRequestsCount", JsonPrimitive(toolRequests.size))
                                put("textLength", JsonPrimitive(aiMessage.text().orEmpty().length))
                            }
                            return response
                        }
                    }

                val runtimeAgent =
                    AgenticServices
                        .agentBuilder(RuntimeToolAgent::class.java)
                        .chatModel(instrumentedModel)
                        .systemMessageProvider { _: Any? -> effectiveRequest.agent.systemPrompt }
                        .toolProvider(toolProvider)
                        .hallucinatedToolNameStrategy { toolRequest ->
                            val localHandler = localToolHandlers[toolRequest.name()]
                            val payload =
                                if (localHandler != null) {
                                    localHandler(toolRequest)
                                } else {
                                    executeToolBridge(
                                        executionRequest = effectiveRequest,
                                        dispatcher = dispatcher,
                                        toolRequest = toolRequest,
                                        step = stepCounter.coerceAtLeast(1),
                                    )
                                }
                            ToolExecutionResultMessage.from(
                                toolRequest,
                                payload,
                            )
                        }.maxSequentialToolsInvocations(MAX_TOOL_STEPS)
                        .build()

                val runResult = runCatching { runtimeAgent.respond(effectiveRequest.prompt) }
                if (runResult.exceptionOrNull()?.let(::isMaxToolStepsFailure) == true) {
                    logger.warnJson("agent.execution.max_tool_steps") {
                        put("agentId", JsonPrimitive(request.agent.id))
                        put("maxToolSteps", JsonPrimitive(MAX_TOOL_STEPS))
                    }
                    return@coroutineScope AgentExecutionResult(
                        response = "Agent stopped after too many tool steps.",
                    )
                }
                val finalResponse = runResult.getOrThrow()

                logger.infoJson("agent.execution.finished") {
                    put("agentId", JsonPrimitive(request.agent.id))
                    put("status", JsonPrimitive("SUCCESS"))
                    put("steps", JsonPrimitive(stepCounter))
                    put("responseLength", JsonPrimitive(finalResponse.length))
                }
                AgentExecutionResult(response = finalResponse)
            } finally {
                scopedConnectionsFactory.closeConnections(request.agent.id, connections)
            }
        }

    private fun emitClaudeCompatibilityWarning(
        request: AgentExecutionRequest,
        message: String,
    ) {
        logger.warnJson("agent.claude.compat.warning") {
            put("agentId", JsonPrimitive(request.agent.id))
            put("message", JsonPrimitive(message))
        }
        request.onTraceAction(
            AgentRunActionEntry(
                type = AgentRunActionType.RUNTIME_EVENT,
                message = message,
                timestampEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    private fun prepareWorkspace(request: AgentExecutionRequest): AgentFileSystemWorkspace? {
        if (request.fileSystem.access == AgentFileSystemAccess.NONE) {
            return null
        }
        return runCatching { AgentFileSystemSandbox.prepare(request.fileSystem) }
            .onSuccess { workspace ->
                logger.infoJson("agent.fs.workspace.ready") {
                    put("agentId", JsonPrimitive(request.agent.id))
                    put("workspacePath", JsonPrimitive(workspace.rootPath.toString()))
                    put("fsAccess", JsonPrimitive(request.fileSystem.access.name))
                }
            }.onFailure { failure ->
                logger.errorJson("agent.fs.workspace.failed", failure) {
                    put("agentId", JsonPrimitive(request.agent.id))
                    put("workspacePath", JsonPrimitive(request.fileSystem.path))
                    put("fsAccess", JsonPrimitive(request.fileSystem.access.name))
                    val code = (failure as? AgentFileSystemException)?.code
                    if (code != null) {
                        put("code", JsonPrimitive(code))
                    }
                    put("errorMessage", JsonPrimitive(failure.message ?: "Workspace preparation failed"))
                }
            }.getOrThrow()
    }

    @Suppress("LongMethod")
    private fun executeToolBridge(
        executionRequest: AgentExecutionRequest,
        dispatcher: RequestDispatcher,
        toolRequest: ToolExecutionRequest,
        step: Int,
    ): String {
        val (serverId, toolName) = resolveToolOperationInfo(toolRequest.name())
        val isAgentToolInvocation = isAgentToolInvocation(serverId, toolRequest.name())
        val arguments = parseArguments(toolRequest.arguments())
        executionRequest.onOperation(
            AgentExecutionOperation.ToolExecution(
                serverId = serverId,
                toolName = toolName,
                step = step,
            ),
        )
        executionRequest.onTraceAction(
            AgentRunActionEntry(
                type = AgentRunActionType.TOOL_CALL,
                step = step,
                serverId = serverId,
                toolName = toolName,
                requestPayload = arguments.toString(),
                timestampEpochMillis = System.currentTimeMillis(),
            ),
        )
        logger.infoJson("agent.tool.call.request") {
            put("agentId", JsonPrimitive(executionRequest.agent.id))
            put("toolName", JsonPrimitive(toolRequest.name()))
            put("step", JsonPrimitive(step))
            put("argumentCount", JsonPrimitive(arguments.size))
            put(
                "argumentKeys",
                buildJsonArray {
                    arguments.keys.sorted().forEach { key ->
                        add(JsonPrimitive(key))
                    }
                },
            )
        }
        val toolResult =
            runBlocking {
                dispatcher.dispatchToolCall(ToolCallRequest(toolRequest.name(), arguments))
            }
        val resultPayload =
            toolResult
                .onSuccess { responsePayload ->
                    executionRequest.onTraceAction(
                        AgentRunActionEntry(
                            type = AgentRunActionType.TOOL_RESULT,
                            step = step,
                            serverId = serverId,
                            toolName = toolName,
                            responsePayload = responsePayload.toString(),
                            timestampEpochMillis = System.currentTimeMillis(),
                        ),
                    )
                    logger.infoJson("agent.tool.call.succeeded") {
                        put("agentId", JsonPrimitive(executionRequest.agent.id))
                        put("toolName", JsonPrimitive(toolRequest.name()))
                        put("step", JsonPrimitive(step))
                        put("responseLength", JsonPrimitive(responsePayload.toString().length))
                    }
                }.onFailure { error ->
                    executionRequest.onTraceAction(
                        AgentRunActionEntry(
                            type = AgentRunActionType.TOOL_RESULT,
                            step = step,
                            serverId = serverId,
                            toolName = toolName,
                            errorMessage = error.message,
                            timestampEpochMillis = System.currentTimeMillis(),
                        ),
                    )
                    logger.errorJson("agent.tool.call.failed", error) {
                        put("agentId", JsonPrimitive(executionRequest.agent.id))
                        put("toolName", JsonPrimitive(toolRequest.name()))
                        put("step", JsonPrimitive(step))
                        put("errorMessage", JsonPrimitive(error.message ?: "Tool execution failed"))
                    }
                }.fold(
                    onSuccess = { result -> result.toString() },
                    onFailure = { error ->
                        val cycleMessage = error.findAgentToolCycleMessage()
                        if (cycleMessage != null) {
                            throw IllegalStateException(cycleMessage, error)
                        }
                        if (isAgentToolInvocation) {
                            val reason = error.message ?: "Unknown error"
                            throw IllegalStateException("Agent tool invocation failed: $reason", error)
                        }
                        "Tool execution failed: ${error.message}"
                    },
                )
        resultPayload.takeIf { it.isNotBlank() }?.let { payload ->
            executionRequest.onTraceDialogue(
                AgentRunDialogueEntry(
                    role = AgentRunDialogueRole.TOOL,
                    content = payload,
                    step = step,
                    serverId = serverId,
                    toolName = toolName,
                    timestampEpochMillis = System.currentTimeMillis(),
                ),
            )
        }
        return resultPayload
    }

    @Suppress("LongMethod")
    private fun executeLocalFsToolBridge(
        executionRequest: AgentExecutionRequest,
        toolExecutor: AgentFileSystemTools,
        workspace: AgentFileSystemWorkspace,
        toolRequest: ToolExecutionRequest,
        step: Int,
    ): String {
        executionRequest.onOperation(
            AgentExecutionOperation.ToolExecution(
                serverId = FILESYSTEM_SERVER_ID,
                toolName = toolRequest.name(),
                step = step,
            ),
        )
        val arguments = parseArguments(toolRequest.arguments())
        executionRequest.onTraceAction(
            AgentRunActionEntry(
                type = AgentRunActionType.TOOL_CALL,
                step = step,
                serverId = FILESYSTEM_SERVER_ID,
                toolName = toolRequest.name(),
                requestPayload = arguments.toString(),
                timestampEpochMillis = System.currentTimeMillis(),
            ),
        )
        logger.infoJson("agent.tool.call.request") {
            put("agentId", JsonPrimitive(executionRequest.agent.id))
            put("toolName", JsonPrimitive(toolRequest.name()))
            put("toolOrigin", JsonPrimitive(FILESYSTEM_SERVER_ID))
            put("workspacePath", JsonPrimitive(workspace.rootPath.toString()))
            put("fsAccess", JsonPrimitive(executionRequest.fileSystem.access.name))
            put("step", JsonPrimitive(step))
            put("argumentCount", JsonPrimitive(arguments.size))
            put(
                "argumentKeys",
                buildJsonArray {
                    arguments.keys.sorted().forEach { key ->
                        add(JsonPrimitive(key))
                    }
                },
            )
        }
        val execution = toolExecutor.execute(toolRequest.name(), arguments)
        if (execution.ok) {
            executionRequest.onTraceAction(
                AgentRunActionEntry(
                    type = AgentRunActionType.TOOL_RESULT,
                    step = step,
                    serverId = FILESYSTEM_SERVER_ID,
                    toolName = toolRequest.name(),
                    responsePayload = execution.payload,
                    timestampEpochMillis = System.currentTimeMillis(),
                ),
            )
            logger.infoJson("agent.tool.call.succeeded") {
                put("agentId", JsonPrimitive(executionRequest.agent.id))
                put("toolName", JsonPrimitive(toolRequest.name()))
                put("toolOrigin", JsonPrimitive(FILESYSTEM_SERVER_ID))
                put("workspacePath", JsonPrimitive(workspace.rootPath.toString()))
                put("fsAccess", JsonPrimitive(executionRequest.fileSystem.access.name))
                put("step", JsonPrimitive(step))
                put("responseLength", JsonPrimitive(execution.payload.length))
            }
        } else {
            executionRequest.onTraceAction(
                AgentRunActionEntry(
                    type = AgentRunActionType.TOOL_RESULT,
                    step = step,
                    serverId = FILESYSTEM_SERVER_ID,
                    toolName = toolRequest.name(),
                    responsePayload = execution.payload,
                    errorMessage = "Filesystem tool execution failed",
                    timestampEpochMillis = System.currentTimeMillis(),
                ),
            )
            logger.warnJson("agent.tool.call.failed") {
                put("agentId", JsonPrimitive(executionRequest.agent.id))
                put("toolName", JsonPrimitive(toolRequest.name()))
                put("toolOrigin", JsonPrimitive(FILESYSTEM_SERVER_ID))
                put("workspacePath", JsonPrimitive(workspace.rootPath.toString()))
                put("fsAccess", JsonPrimitive(executionRequest.fileSystem.access.name))
                put("step", JsonPrimitive(step))
                execution.code?.let { code ->
                    put("code", JsonPrimitive(code))
                }
                put("errorMessage", JsonPrimitive("Filesystem tool execution failed"))
            }
        }
        executionRequest.onTraceDialogue(
            AgentRunDialogueEntry(
                role = AgentRunDialogueRole.TOOL,
                content = execution.payload,
                step = step,
                serverId = FILESYSTEM_SERVER_ID,
                toolName = toolRequest.name(),
                timestampEpochMillis = System.currentTimeMillis(),
            ),
        )
        return execution.payload
    }

    private fun isMaxToolStepsFailure(failure: Throwable): Boolean {
        var current: Throwable? = failure
        while (current != null) {
            val message = current.message.orEmpty().lowercase()
            if (message.contains("sequential tool executions") && message.contains("exceeded")) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun Throwable.findAgentToolCycleMessage(): String? {
        var current: Throwable? = this
        while (current != null) {
            val message = current.message?.trim().orEmpty()
            if (message.contains("agent tool cycle detected", ignoreCase = true)) {
                return message
            }
            current = current.cause
        }
        return null
    }

    private fun buildModel(request: AgentExecutionRequest): ChatModel {
        val timeout =
            Duration.ofSeconds(
                request.mcpConfig.requestTimeoutSeconds
                    .coerceAtLeast(1)
                    .toLong(),
            )
        val llm = request.llm
        val forceHttp11 = llm.provider == LlmProvider.LM_STUDIO
        val httpClientBuilder =
            buildLangChainHttpClientBuilder(
                ignoreHttpsCertificateErrors = request.mcpConfig.ignoreHttpsCertificateErrors,
                forceHttp11 = forceHttp11,
            )
        val baseUrlOverride = providerBaseUrlOverride(request)
        return when (llm.provider) {
            LlmProvider.OPENAI -> {
                val builder =
                    OpenAiChatModel
                        .builder()
                        .apiKey(requireNotNull(resolveApiKey(request, LlmProvider.OPENAI)))
                        .modelName(llm.model)
                        .temperature(llm.temperature)
                        .timeout(timeout)
                builder.httpClientBuilder(httpClientBuilder)
                baseUrlOverride?.let(builder::baseUrl)
                builder.build()
            }

            LlmProvider.ANTHROPIC -> {
                val builder =
                    AnthropicChatModel
                        .builder()
                        .apiKey(requireNotNull(resolveApiKey(request, LlmProvider.ANTHROPIC)))
                        .modelName(llm.model)
                        .temperature(llm.temperature)
                        .timeout(timeout)
                builder.httpClientBuilder(httpClientBuilder)
                baseUrlOverride?.let(builder::baseUrl)
                builder.build()
            }

            LlmProvider.LM_STUDIO -> {
                val builder =
                    OpenAiChatModel
                        .builder()
                        .apiKey(request.apiKey?.trim().takeIf { !it.isNullOrBlank() } ?: "lm-studio")
                        .modelName(llm.model)
                        .temperature(llm.temperature)
                        .timeout(timeout)
                        .baseUrl(request.providerSettings.baseUrlFor(LlmProvider.LM_STUDIO))
                builder.httpClientBuilder(httpClientBuilder)
                builder.build()
            }
        }
    }

    private fun providerBaseUrlOverride(request: AgentExecutionRequest): String? =
        when (request.llm.provider) {
            LlmProvider.OPENAI -> request.providerSettings.openAi.baseUrl
            LlmProvider.ANTHROPIC -> request.providerSettings.anthropic.baseUrl
            LlmProvider.LM_STUDIO -> request.providerSettings.lmStudio.baseUrl
        }?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun selectedProviderBaseUrl(request: AgentExecutionRequest): String =
        request.providerSettings
            .baseUrlFor(request.llm.provider)

    private fun resolveApiKey(
        request: AgentExecutionRequest,
        provider: LlmProvider,
    ): String? {
        val fromMap = request.apiKeys[provider]?.trim()?.takeIf { it.isNotBlank() }
        if (fromMap != null) {
            return fromMap
        }
        return if (request.llm.provider == provider) {
            request.apiKey?.trim()?.takeIf { it.isNotBlank() }
        } else {
            null
        }
    }

    private fun buildLangChainHttpClientBuilder(
        ignoreHttpsCertificateErrors: Boolean,
        forceHttp11: Boolean = false,
    ): HttpClientBuilder {
        val jdkHttpClientBuilder = HttpClient.newBuilder()
        if (forceHttp11) {
            jdkHttpClientBuilder.version(HttpClient.Version.HTTP_1_1)
        }
        if (ignoreHttpsCertificateErrors) {
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf(TrustAllX509TrustManager), SecureRandom())
            val sslParameters = SSLParameters().apply { endpointIdentificationAlgorithm = "" }
            jdkHttpClientBuilder.sslContext(sslContext).sslParameters(sslParameters)
        }
        return JdkHttpClientBuilder().httpClientBuilder(jdkHttpClientBuilder)
    }

    private fun parseArguments(arguments: String?): JsonObject {
        if (arguments.isNullOrBlank()) {
            return JsonObject(emptyMap())
        }
        return runCatching {
            Json.parseToJsonElement(arguments).jsonObject
        }.getOrDefault(JsonObject(emptyMap()))
    }

    private fun resolveToolOperationInfo(prefixedToolName: String): Pair<String, String> =
        runCatching { namespaceManager.parsePrefixedToolName(prefixedToolName) }
            .getOrElse { "unknown" to prefixedToolName }

    private fun isAgentToolInvocation(
        resolvedServerId: String,
        prefixedToolName: String,
    ): Boolean = resolvedServerId == AGENT_TOOLS_SERVER_ID || prefixedToolName.startsWith("${AGENT_TOOLS_SERVER_ID}_")

    private companion object {
        private fun buildDispatcher(
            connections: List<McpServerConnection>,
            filterResult: FilterResult,
            logger: Logger,
        ): RequestDispatcher =
            DefaultRequestDispatcher(
                servers = connections,
                allowedPrefixedTools = { filterResult.allowedPrefixedTools },
                allowAllWhenNoAllowedTools = false,
                promptServerResolver = { name -> filterResult.promptServerByName[name] },
                resourceServerResolver = { uri -> filterResult.resourceServerByUri[uri] },
                logger = logger,
            )
    }
}

private object TrustAllX509TrustManager : X509TrustManager {
    override fun checkClientTrusted(
        chain: Array<X509Certificate>,
        authType: String,
    ) = Unit

    override fun checkServerTrusted(
        chain: Array<X509Certificate>,
        authType: String,
    ) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
