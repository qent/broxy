package io.qent.broxy.agents

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import io.qent.broxy.agents.runtime.langchain.LangChain4jAgentExecutor
import io.qent.broxy.agents.runtime.mcp.OAuthStatePersistence
import io.qent.broxy.core.mcp.McpServerConnection
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.ServerStatus
import io.qent.broxy.core.mcp.auth.OAuthState
import io.qent.broxy.core.mcp.auth.OAuthStateSnapshot
import io.qent.broxy.core.mcp.auth.OAuthToken
import io.qent.broxy.core.mcp.auth.peekAccessToken
import io.qent.broxy.core.models.AuthConfig
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.PromptReference
import io.qent.broxy.core.models.ResourceReference
import io.qent.broxy.core.models.ToolReference
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.proxy.RequestDispatcher
import io.qent.broxy.core.proxy.ToolCallRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private typealias JsonResult = Result<JsonElement>
private typealias ToolCallList = List<ToolCallRequest>
private typealias OperationCallback = (AgentExecutionOperation) -> Unit

private const val NOT_USED_IN_TEST = "Not used in test"

private fun <T> notUsedResult(): Result<T> = Result.failure(UnsupportedOperationException(NOT_USED_IN_TEST))

@Suppress("LargeClass")
class LangChain4jAgentExecutorTest {
    @Test
    fun execute_completesToolLoopAndReturnsFinalResponse() =
        runTest {
            val scriptedModel =
                ScriptedChatModel(
                    responses =
                        listOf(
                            AiMessage.from(
                                ToolExecutionRequest
                                    .builder()
                                    .id("call-1")
                                    .name("s1_lookup")
                                    .arguments("""{"q":"weather"}""")
                                    .build(),
                            ),
                            AiMessage.from("Final answer"),
                        ),
                )
            val dispatcher = RecordingDispatcher(Result.success(JsonPrimitive("tool-ok")))
            val executor =
                LangChain4jAgentExecutor(
                    logger = ExecutorTestLogger,
                    modelFactory = { scriptedModel },
                    dispatcherFactory = { _, _, _ -> dispatcher },
                )

            val result = executor.execute(baseRequest()).getOrThrow()

            assertEquals("Final answer", result.response)
            assertEquals(1, dispatcher.toolCalls.size)
            assertEquals("s1_lookup", dispatcher.toolCalls.first().name)
        }

    @Test
    fun execute_handlesToolFailureAndContinuesLoop() =
        runTest {
            val scriptedModel =
                ScriptedChatModel(
                    responses =
                        listOf(
                            AiMessage.from(
                                ToolExecutionRequest
                                    .builder()
                                    .id("call-2")
                                    .name("s1_search")
                                    .arguments("""{"q":"news"}""")
                                    .build(),
                            ),
                            AiMessage.from("Recovered answer"),
                        ),
                )
            val dispatcher = RecordingDispatcher(Result.failure(IllegalStateException("tool failed")))
            val executor =
                LangChain4jAgentExecutor(
                    logger = ExecutorTestLogger,
                    modelFactory = { scriptedModel },
                    dispatcherFactory = { _, _, _ -> dispatcher },
                )

            val result = executor.execute(baseRequest())

            assertTrue(result.isSuccess)
            assertEquals("Recovered answer", result.getOrThrow().response)
            assertEquals(1, dispatcher.toolCalls.size)
        }

    @Test
    fun execute_routesHallucinatedToolNameThroughDispatcher() =
        runTest {
            val scriptedModel =
                ScriptedChatModel(
                    responses =
                        listOf(
                            AiMessage.from(
                                ToolExecutionRequest
                                    .builder()
                                    .id("hallucinated-call")
                                    .name("unknown_tool")
                                    .arguments("""{"q":"weather"}""")
                                    .build(),
                            ),
                            AiMessage.from("Final answer"),
                        ),
                )
            val dispatcher = RecordingDispatcher(Result.success(JsonPrimitive("tool-ok")))
            val executor =
                LangChain4jAgentExecutor(
                    logger = ExecutorTestLogger,
                    modelFactory = { scriptedModel },
                    dispatcherFactory = { _, _, _ -> dispatcher },
                )

            val result = executor.execute(baseRequest())

            assertTrue(result.isSuccess)
            assertEquals("Final answer", result.getOrThrow().response)
            assertEquals(1, dispatcher.toolCalls.size)
            assertEquals("unknown_tool", dispatcher.toolCalls.first().name)
        }

    @Test
    fun execute_returnsFallbackWhenMaxSequentialToolStepsExceeded() =
        runTest {
            val logger = ExecutorRecordingLogger()
            val scriptedModel =
                ScriptedChatModel(
                    responses =
                        listOf(
                            AiMessage.from(
                                ToolExecutionRequest
                                    .builder()
                                    .id("loop-call")
                                    .name("s1_loop")
                                    .arguments("""{"q":"loop"}""")
                                    .build(),
                            ),
                        ),
                )
            val dispatcher = RecordingDispatcher(Result.success(JsonPrimitive("tool-ok")))
            val executor =
                LangChain4jAgentExecutor(
                    logger = logger,
                    modelFactory = { scriptedModel },
                    dispatcherFactory = { _, _, _ -> dispatcher },
                )

            val result = executor.execute(baseRequest())

            assertTrue(result.isSuccess)
            assertEquals("Agent stopped after too many tool steps.", result.getOrThrow().response)
            assertEquals(24, dispatcher.toolCalls.size)
            assertTrue(logger.hasEvent("agent.execution.max_tool_steps"))
        }

    @Test
    fun execute_reportsOperationSequence_withParsedToolServerAndName() =
        runTest {
            val scriptedModel =
                ScriptedChatModel(
                    responses =
                        listOf(
                            AiMessage.from(
                                ToolExecutionRequest
                                    .builder()
                                    .id("call-ops")
                                    .name("server42_lookup")
                                    .arguments("""{"q":"status"}""")
                                    .build(),
                            ),
                            AiMessage.from("Done"),
                        ),
                )
            val dispatcher = RecordingDispatcher(Result.success(JsonPrimitive("tool-ok")))
            val operations = mutableListOf<AgentExecutionOperation>()
            val executor =
                LangChain4jAgentExecutor(
                    logger = ExecutorTestLogger,
                    modelFactory = { scriptedModel },
                    dispatcherFactory = { _, _, _ -> dispatcher },
                )

            val result = executor.execute(baseRequest(onOperation = operations::add))

            assertTrue(result.isSuccess)
            assertIs<AgentExecutionOperation.LoadingCapabilities>(operations.first())
            val requestIndex = operations.indexOfFirst { it is AgentExecutionOperation.LlmRequest && it.step == 1 }
            val thinkingIndex = operations.indexOfFirst { it is AgentExecutionOperation.LlmThinking && it.step == 1 }
            val responseIndex =
                operations.indexOfFirst {
                    it is AgentExecutionOperation.LlmResponseGeneration && it.step == 1
                }
            assertTrue(requestIndex > -1)
            assertTrue(thinkingIndex > requestIndex)
            assertTrue(responseIndex > thinkingIndex)
            val toolExecutionUpdate = operations.filterIsInstance<AgentExecutionOperation.ToolExecution>().firstOrNull()
            val toolExecution = assertNotNull(toolExecutionUpdate)
            assertEquals("server42", toolExecution.serverId)
            assertEquals("lookup", toolExecution.toolName)
            assertEquals(1, toolExecution.step)
        }

    @Test
    fun execute_failsWhenModelFactoryReportsMissingApiKey() =
        runTest {
            val executor =
                LangChain4jAgentExecutor(
                    logger = ExecutorTestLogger,
                    modelFactory = {
                        throw IllegalStateException("Missing API key for provider OPENAI")
                    },
                )

            val result = executor.execute(baseRequest())

            assertTrue(result.isFailure)
            val errorMessage = result.exceptionOrNull()?.message.orEmpty()
            assertTrue(errorMessage.contains("Missing API key"))
        }

    @Test
    fun execute_logsModelSelectionConnectionAndToolCalls() =
        runTest {
            val logger = ExecutorRecordingLogger()
            val scriptedModel =
                ScriptedChatModel(
                    responses =
                        listOf(
                            AiMessage.from(
                                ToolExecutionRequest
                                    .builder()
                                    .id("call-3")
                                    .name("s1_search")
                                    .arguments("""{"q":"logs","apiKey":"secret"}""")
                                    .build(),
                            ),
                            AiMessage.from("Done"),
                        ),
                )
            val dispatcher = RecordingDispatcher(Result.success(JsonPrimitive("ok")))
            val executor =
                LangChain4jAgentExecutor(
                    logger = logger,
                    modelFactory = { scriptedModel },
                    dispatcherFactory = { _, _, _ -> dispatcher },
                )

            val result = executor.execute(baseRequest())

            assertTrue(result.isSuccess)
            assertTrue(logger.hasEvent("agent.llm.model.selected"))
            assertTrue(logger.hasEvent("agent.llm.connection.succeeded"))
            assertTrue(logger.hasEvent("agent.tool.call.request"))
            assertTrue(logger.hasEvent("agent.tool.call.succeeded"))
            assertTrue(logger.hasEvent("agent.execution.finished"))
        }

    @Test
    fun execute_logsConnectionFailureOnFirstLlmRequest() =
        runTest {
            val logger = ExecutorRecordingLogger()
            val executor =
                LangChain4jAgentExecutor(
                    logger = logger,
                    modelFactory = { ThrowingChatModel("provider unavailable") },
                )

            val result = executor.execute(baseRequest())

            assertTrue(result.isFailure)
            assertTrue(logger.hasEvent("agent.llm.connection.failed"))
            assertTrue(logger.hasEvent("agent.execution.failed"))
        }

    @Test
    fun execute_withNoFsAccess_routesFsToolToDispatcher() =
        runTest {
            val scriptedModel =
                ScriptedChatModel(
                    responses =
                        listOf(
                            AiMessage.from(
                                ToolExecutionRequest
                                    .builder()
                                    .id("fs-none")
                                    .name("fsInspect")
                                    .arguments("""{"operation":"info","path":"."}""")
                                    .build(),
                            ),
                            AiMessage.from("Done"),
                        ),
                )
            val dispatcher = RecordingDispatcher(Result.success(JsonPrimitive("tool-ok")))
            val executor =
                LangChain4jAgentExecutor(
                    logger = ExecutorTestLogger,
                    modelFactory = { scriptedModel },
                    dispatcherFactory = { _, _, _ -> dispatcher },
                )

            val result =
                executor.execute(
                    baseRequest(
                        fileSystem =
                            AgentFileSystemSettings(
                                path = DEFAULT_AGENT_WORKSPACE_PATH,
                                access = AgentFileSystemAccess.NONE,
                            ),
                    ),
                )

            assertTrue(result.isSuccess)
            assertEquals(1, dispatcher.toolCalls.size)
            assertEquals("fsInspect", dispatcher.toolCalls.first().name)
        }

    @Test
    fun execute_withReadOnlyFsAccess_routesFsEditToDispatcher() =
        runTest {
            val workspace = Files.createTempDirectory("broxy-agent-executor-ro")
            try {
                val scriptedModel =
                    ScriptedChatModel(
                        responses =
                            listOf(
                                AiMessage.from(
                                    ToolExecutionRequest
                                        .builder()
                                        .id("fs-ro")
                                        .name("fsEdit")
                                        .arguments("""{"operation":"overwrite","path":"file.txt","text":"x"}""")
                                        .build(),
                                ),
                                AiMessage.from("Done"),
                            ),
                    )
                val dispatcher = RecordingDispatcher(Result.success(JsonPrimitive("tool-ok")))
                val executor =
                    LangChain4jAgentExecutor(
                        logger = ExecutorTestLogger,
                        modelFactory = { scriptedModel },
                        dispatcherFactory = { _, _, _ -> dispatcher },
                    )

                val result =
                    executor.execute(
                        baseRequest(
                            fileSystem =
                                AgentFileSystemSettings(
                                    path = workspace.toString(),
                                    access = AgentFileSystemAccess.READ_ONLY,
                                ),
                        ),
                    )

                assertTrue(result.isSuccess)
                assertEquals(1, dispatcher.toolCalls.size)
                assertEquals("fsEdit", dispatcher.toolCalls.first().name)
            } finally {
                workspace.toFile().walkBottomUp().forEach { file ->
                    if (file.exists()) {
                        file.delete()
                    }
                }
            }
        }

    @Test
    fun execute_withReadWriteFsAccess_handlesFsInspectLocally() =
        runTest {
            val workspace = Files.createTempDirectory("broxy-agent-executor-rw")
            try {
                Files.writeString(workspace.resolve("hello.txt"), "hello")
                val scriptedModel =
                    ScriptedChatModel(
                        responses =
                            listOf(
                                AiMessage.from(
                                    ToolExecutionRequest
                                        .builder()
                                        .id("fs-rw")
                                        .name("fsInspect")
                                        .arguments("""{"operation":"list","path":"."}""")
                                        .build(),
                                ),
                                AiMessage.from("Done"),
                            ),
                    )
                val dispatcher = RecordingDispatcher(Result.success(JsonPrimitive("tool-ok")))
                val executor =
                    LangChain4jAgentExecutor(
                        logger = ExecutorTestLogger,
                        modelFactory = { scriptedModel },
                        dispatcherFactory = { _, _, _ -> dispatcher },
                    )

                val result =
                    executor.execute(
                        baseRequest(
                            fileSystem =
                                AgentFileSystemSettings(
                                    path = workspace.toString(),
                                    access = AgentFileSystemAccess.READ_WRITE,
                                ),
                        ),
                    )

                assertTrue(result.isSuccess)
                assertTrue(dispatcher.toolCalls.isEmpty())
            } finally {
                workspace.toFile().walkBottomUp().forEach { file ->
                    if (file.exists()) {
                        file.delete()
                    }
                }
            }
        }

    @Test
    fun execute_failsForMissingCustomWorkspacePath() =
        runTest {
            val missingWorkspace = Files.createTempDirectory("broxy-agent-executor-missing").resolve("missing")
            val scriptedModel = ScriptedChatModel(responses = listOf(AiMessage.from("Done")))
            val executor =
                LangChain4jAgentExecutor(
                    logger = ExecutorTestLogger,
                    modelFactory = { scriptedModel },
                    dispatcherFactory = { _, _, _ -> RecordingDispatcher(Result.success(JsonPrimitive("ok"))) },
                )

            val result =
                executor.execute(
                    baseRequest(
                        fileSystem =
                            AgentFileSystemSettings(
                                path = missingWorkspace.toString(),
                                access = AgentFileSystemAccess.READ_ONLY,
                            ),
                    ),
                )

            assertTrue(result.isFailure)
            assertTrue(
                result
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .contains("Workspace directory does not exist"),
            )
            missingWorkspace.parent.toFile().walkBottomUp().forEach { file ->
                if (file.exists()) {
                    file.delete()
                }
            }
        }

    @Test
    fun execute_refreshesCapabilitiesOnlyForScopedServers() =
        runTest {
            val refreshedServers = mutableListOf<String>()
            val scriptedModel = ScriptedChatModel(responses = listOf(AiMessage.from("Done")))
            val executor =
                LangChain4jAgentExecutor(
                    logger = ExecutorTestLogger,
                    modelFactory = { scriptedModel },
                    oauthStateStoreFactory = { _, _ -> RecordingOAuthStatePersistence() },
                    connectionFactory = { config, _, _, _, _, _, _, _, _ ->
                        FakeMcpServerConnection(
                            config = config,
                            onGetCapabilities = { forceRefresh ->
                                assertTrue(forceRefresh)
                                refreshedServers += config.id
                            },
                        )
                    },
                )
            val request =
                baseRequest(
                    agent =
                        AgentDefinition(
                            id = "agent-test",
                            name = "Test agent",
                            systemPrompt = "You are concise",
                            tools = listOf(ToolReference(serverId = "s-used", toolName = "lookup", enabled = true)),
                        ),
                    mcpConfig =
                        McpServersConfig(
                            servers =
                                listOf(
                                    stdioServer("s-used", enabled = true),
                                    stdioServer("s-unused", enabled = true),
                                    stdioServer("s-disabled", enabled = false),
                                ),
                        ),
                )

            val result = executor.execute(request)

            assertTrue(result.isSuccess)
            assertEquals(listOf("s-used"), refreshedServers)
        }

    @Test
    @Suppress("LongMethod")
    fun execute_scopesServersFromEnabledToolPromptAndResourceRefs() =
        runTest {
            val refreshedServers = mutableListOf<String>()
            val scriptedModel = ScriptedChatModel(responses = listOf(AiMessage.from("Done")))
            val executor =
                LangChain4jAgentExecutor(
                    logger = ExecutorTestLogger,
                    modelFactory = { scriptedModel },
                    oauthStateStoreFactory = { _, _ -> RecordingOAuthStatePersistence() },
                    connectionFactory = { config, _, _, _, _, _, _, _, _ ->
                        FakeMcpServerConnection(
                            config = config,
                            onGetCapabilities = { refreshedServers += config.id },
                        )
                    },
                )
            val request =
                baseRequest(
                    agent =
                        AgentDefinition(
                            id = "agent-test",
                            name = "Test agent",
                            systemPrompt = "You are concise",
                            tools =
                                listOf(
                                    ToolReference(serverId = "s-tools", toolName = "lookup", enabled = true),
                                    ToolReference(serverId = "s-disabled-ref", toolName = "ignore", enabled = false),
                                ),
                            prompts =
                                listOf(
                                    PromptReference(serverId = "s-prompts", promptName = "p1", enabled = true),
                                    PromptReference(serverId = "s-disabled-ref", promptName = "p2", enabled = false),
                                ),
                            resources =
                                listOf(
                                    ResourceReference(serverId = "s-resources", resourceKey = "r1", enabled = true),
                                    ResourceReference(
                                        serverId = "s-disabled-server",
                                        resourceKey = "r2",
                                        enabled = true,
                                    ),
                                ),
                        ),
                    mcpConfig =
                        McpServersConfig(
                            servers =
                                listOf(
                                    stdioServer("s-tools", enabled = true),
                                    stdioServer("s-prompts", enabled = true),
                                    stdioServer("s-resources", enabled = true),
                                    stdioServer("s-unused", enabled = true),
                                    stdioServer("s-disabled-server", enabled = false),
                                ),
                        ),
                )

            val result = executor.execute(request)

            assertTrue(result.isSuccess)
            assertEquals(listOf("s-tools", "s-prompts", "s-resources"), refreshedServers)
            assertFalse(refreshedServers.contains("s-unused"))
            assertFalse(refreshedServers.contains("s-disabled-server"))
            assertFalse(refreshedServers.contains("s-disabled-ref"))
        }

    @Test
    fun execute_restoresOAuthSnapshotIntoConnectionAuthState() =
        runTest {
            val persistence = RecordingOAuthStatePersistence()
            persistence.seed(
                serverId = "s-http",
                resourceUrl = "https://mcp.example.com/mcp",
                snapshot =
                    OAuthStateSnapshot(
                        resourceUrl = "https://mcp.example.com/mcp",
                        token = OAuthToken(accessToken = "cached-token", expiresAtEpochMillis = 99_999L),
                    ),
            )
            var capturedAuthState: OAuthState? = null
            val scriptedModel = ScriptedChatModel(responses = listOf(AiMessage.from("Done")))
            val executor =
                LangChain4jAgentExecutor(
                    logger = ExecutorTestLogger,
                    modelFactory = { scriptedModel },
                    oauthStateStoreFactory = { _, _ -> persistence },
                    connectionFactory = { config, _, _, _, _, _, _, authState, _ ->
                        capturedAuthState = authState
                        FakeMcpServerConnection(config = config)
                    },
                )
            val request =
                baseRequest(
                    agent =
                        AgentDefinition(
                            id = "agent-test",
                            name = "Test agent",
                            systemPrompt = "You are concise",
                            tools = listOf(ToolReference(serverId = "s-http", toolName = "lookup", enabled = true)),
                        ),
                    mcpConfig =
                        McpServersConfig(
                            servers =
                                listOf(
                                    httpServer(
                                        id = "s-http",
                                        url = "https://mcp.example.com/mcp",
                                        enabled = true,
                                        auth = AuthConfig.OAuth(),
                                    ),
                                ),
                        ),
                )

            val result = executor.execute(request)

            assertTrue(result.isSuccess)
            assertEquals("cached-token", capturedAuthState?.peekAccessToken())
            assertEquals(1, persistence.loadRequests.size)
            assertEquals("s-http" to "https://mcp.example.com/mcp", persistence.loadRequests.first())
        }

    @Test
    fun execute_persistsOAuthSnapshotFromAuthStateObserver() =
        runTest {
            val persistence = RecordingOAuthStatePersistence()
            val scriptedModel = ScriptedChatModel(responses = listOf(AiMessage.from("Done")))
            val executor =
                LangChain4jAgentExecutor(
                    logger = ExecutorTestLogger,
                    modelFactory = { scriptedModel },
                    oauthStateStoreFactory = { _, _ -> persistence },
                    connectionFactory = { config, _, _, _, _, _, _, authState, authStateObserver ->
                        FakeMcpServerConnection(
                            config = config,
                            onGetCapabilities = {
                                if (authState != null && authStateObserver != null) {
                                    authState.token =
                                        OAuthToken(
                                            accessToken = "fresh-token",
                                            expiresAtEpochMillis = 321_000L,
                                        )
                                    authStateObserver.invoke(authState)
                                }
                            },
                        )
                    },
                )
            val request =
                baseRequest(
                    agent =
                        AgentDefinition(
                            id = "agent-test",
                            name = "Test agent",
                            systemPrompt = "You are concise",
                            tools = listOf(ToolReference(serverId = "s-http", toolName = "lookup", enabled = true)),
                        ),
                    mcpConfig =
                        McpServersConfig(
                            servers =
                                listOf(
                                    httpServer(
                                        id = "s-http",
                                        url = "https://mcp.example.com/mcp",
                                        enabled = true,
                                        auth = AuthConfig.OAuth(),
                                    ),
                                ),
                        ),
                )

            val result = executor.execute(request)

            assertTrue(result.isSuccess)
            val saved = persistence.savedSnapshots["s-http"]
            assertNotNull(saved)
            assertEquals("https://mcp.example.com/mcp", saved.resourceUrl)
            assertEquals("fresh-token", saved.token?.accessToken)
        }

    @Test
    fun buildLangChainHttpClientBuilder_returnsJdkBuilderWhenNoOverridesAreRequested() {
        val executor = LangChain4jAgentExecutor(logger = ExecutorTestLogger)

        val method =
            executor.javaClass.getDeclaredMethod(
                "buildLangChainHttpClientBuilder",
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            )
        method.isAccessible = true

        val result = method.invoke(executor, false, false)

        assertNotNull(result)
        assertEquals("JdkHttpClientBuilder", result.javaClass.simpleName)
    }

    @Test
    fun buildLangChainHttpClientBuilder_returnsJdkBuilderWhenCertificateValidationIsIgnored() {
        val executor = LangChain4jAgentExecutor(logger = ExecutorTestLogger)

        val method =
            executor.javaClass.getDeclaredMethod(
                "buildLangChainHttpClientBuilder",
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            )
        method.isAccessible = true

        val result = method.invoke(executor, true, false)

        assertNotNull(result)
        assertEquals("JdkHttpClientBuilder", result.javaClass.simpleName)
    }

    @Test
    fun buildLangChainHttpClientBuilder_returnsJdkBuilderWhenHttp11IsForced() {
        val executor = LangChain4jAgentExecutor(logger = ExecutorTestLogger)

        val method =
            executor.javaClass.getDeclaredMethod(
                "buildLangChainHttpClientBuilder",
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            )
        method.isAccessible = true

        val result = method.invoke(executor, false, true)

        assertNotNull(result)
        assertEquals("JdkHttpClientBuilder", result.javaClass.simpleName)
    }
}

private fun baseRequest(
    agent: AgentDefinition =
        AgentDefinition(
            id = "agent-test",
            name = "Test agent",
            systemPrompt = "You are concise",
        ),
    mcpConfig: McpServersConfig = McpServersConfig(),
    fileSystem: AgentFileSystemSettings = AgentFileSystemSettings(path = DEFAULT_AGENT_WORKSPACE_PATH),
    onOperation: OperationCallback = {},
): AgentExecutionRequest =
    AgentExecutionRequest(
        agent = agent,
        llm =
            AgentLlmConfig(
                provider = LlmProvider.OPENAI,
                model = "gpt-4o-mini",
                temperature = 0.2,
            ),
        prompt = "Run",
        fileSystem = fileSystem,
        providerSettings = AgentProviderSettings(),
        mcpConfig = mcpConfig,
        apiKey = "test-key",
        onOperation = onOperation,
    )

private fun stdioServer(
    id: String,
    enabled: Boolean,
): McpServerConfig =
    McpServerConfig(
        id = id,
        name = id,
        enabled = enabled,
        transport = TransportConfig.StdioTransport(command = "unused"),
    )

private fun httpServer(
    id: String,
    url: String,
    enabled: Boolean,
    auth: AuthConfig?,
): McpServerConfig =
    McpServerConfig(
        id = id,
        name = id,
        enabled = enabled,
        auth = auth,
        transport = TransportConfig.StreamableHttpTransport(url = url),
    )

private class ScriptedChatModel(
    private val responses: List<AiMessage>,
) : ChatModel {
    private var index: Int = 0

    override fun chat(request: ChatRequest): ChatResponse {
        val current = responses.getOrElse(index) { responses.last() }
        index += 1
        return ChatResponse.builder().aiMessage(current).build()
    }
}

private class RecordingDispatcher(
    private val response: JsonResult,
) : RequestDispatcher {
    val toolCalls = mutableListOf<ToolCallRequest>()

    override suspend fun dispatchToolCall(request: ToolCallRequest): JsonResult {
        toolCalls += request
        return response
    }

    override suspend fun dispatchBatch(requests: ToolCallList): List<JsonResult> = requests.map { dispatchToolCall(it) }

    override suspend fun dispatchPrompt(
        name: String,
        arguments: Map<String, String>?,
    ): Result<JsonObject> = notUsedResult()

    override suspend fun dispatchResource(uri: String): Result<JsonObject> = notUsedResult()
}

private class FakeMcpServerConnection(
    override val config: McpServerConfig,
    private val capabilitiesResult: Result<ServerCapabilities> = Result.success(ServerCapabilities()),
    private val onGetCapabilities: (forceRefresh: Boolean) -> Unit = {},
) : McpServerConnection {
    override val serverId: String = config.id
    override val status: ServerStatus = ServerStatus.Stopped

    override suspend fun connect(): Result<Unit> = Result.success(Unit)

    override suspend fun disconnect() = Unit

    override suspend fun getCapabilities(forceRefresh: Boolean): Result<ServerCapabilities> {
        onGetCapabilities(forceRefresh)
        return capabilitiesResult
    }

    override suspend fun callTool(
        toolName: String,
        arguments: JsonObject,
    ): Result<JsonElement> = notUsedResult()

    override suspend fun getPrompt(
        name: String,
        arguments: Map<String, String>?,
    ): Result<JsonObject> = notUsedResult()

    override suspend fun readResource(uri: String): Result<JsonObject> = notUsedResult()
}

private class RecordingOAuthStatePersistence : OAuthStatePersistence {
    private val snapshots = mutableMapOf<Pair<String, String?>, OAuthStateSnapshot>()
    val loadRequests = mutableListOf<Pair<String, String?>>()
    val savedSnapshots = mutableMapOf<String, OAuthStateSnapshot>()

    fun seed(
        serverId: String,
        resourceUrl: String?,
        snapshot: OAuthStateSnapshot,
    ) {
        snapshots[serverId to resourceUrl] = snapshot
    }

    override fun load(
        serverId: String,
        resourceUrl: String?,
    ): OAuthStateSnapshot? {
        loadRequests += serverId to resourceUrl
        return snapshots[serverId to resourceUrl]
    }

    override fun save(
        serverId: String,
        snapshot: OAuthStateSnapshot,
    ) {
        savedSnapshots[serverId] = snapshot
        snapshots[serverId to snapshot.resourceUrl] = snapshot
    }
}

private class ThrowingChatModel(
    private val message: String,
) : ChatModel {
    override fun chat(request: ChatRequest): ChatResponse = throw IllegalStateException(message)
}

private class ExecutorRecordingLogger : io.qent.broxy.core.utils.Logger {
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
}

private object ExecutorTestLogger : io.qent.broxy.core.utils.Logger {
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
