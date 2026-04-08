package io.qent.broxy.agents.codex

import io.qent.broxy.agents.AgentCodexConfig
import io.qent.broxy.agents.AgentDefinition
import io.qent.broxy.agents.AgentExecutionRequest
import io.qent.broxy.agents.AgentFileSystemAccess
import io.qent.broxy.agents.AgentFileSystemSettings
import io.qent.broxy.agents.AgentLlmConfig
import io.qent.broxy.agents.AgentProviderSettings
import io.qent.broxy.agents.AgentRuntime
import io.qent.broxy.agents.LlmProvider
import io.qent.broxy.agents.codex.mcp.AgentRunMcpIsolator
import io.qent.broxy.agents.runtime.mcp.OAuthStatePersistence
import io.qent.broxy.core.mcp.McpServerConnection
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.ServerStatus
import io.qent.broxy.core.mcp.auth.OAuthState
import io.qent.broxy.core.mcp.auth.OAuthStateSnapshot
import io.qent.broxy.core.mcp.auth.OAuthToken
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.ToolReference
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.utils.Logger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.ServerSocket
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentRunMcpIsolatorTest {
    @Test
    fun start_restoresOauthSnapshotForHttpDownstream() {
        val persistence = RecordingOAuthStatePersistence()
        val resourceUrl = "https://mcp.example.com/mcp"
        persistence.seed(
            serverId = "s-http",
            resourceUrl = resourceUrl,
            snapshot = OAuthStateSnapshot(resourceUrl = resourceUrl, token = OAuthToken(accessToken = "cached-token")),
        )
        var capturedAccessToken: String? = null
        val isolator =
            AgentRunMcpIsolator(
                logger = IsolatorTestLogger,
                oauthStateStoreBaseDir = Paths.get("/tmp"),
                oauthStateStoreFactory = { _, _ -> persistence },
                connectionFactory = { config, _, _, _, _, _, _, authState, _ ->
                    capturedAccessToken = authState?.token?.accessToken
                    FakeMcpServerConnection(config = config)
                },
            )
        val portRange = findFreePortRange()
        val request = requestWith(server = httpServer("s-http"))

        val session = isolator.start(request, portRange.first, portRange.last)
        session.close()

        assertEquals("cached-token", capturedAccessToken)
        assertTrue(persistence.loadRequests.contains("s-http" to resourceUrl))
    }

    @Test
    fun start_persistsOauthSnapshotFromObserverForHttpDownstream() {
        val persistence = RecordingOAuthStatePersistence()
        val resourceUrl = "https://mcp.example.com/mcp"
        val isolator =
            AgentRunMcpIsolator(
                logger = IsolatorTestLogger,
                oauthStateStoreBaseDir = Paths.get("/tmp"),
                oauthStateStoreFactory = { _, _ -> persistence },
                connectionFactory = { config, _, _, _, _, _, _, authState, authStateObserver ->
                    FakeMcpServerConnection(
                        config = config,
                        onGetCapabilities = {
                            if (authState != null && authStateObserver != null) {
                                authState.token =
                                    OAuthToken(
                                        accessToken = "updated-token",
                                        expiresAtEpochMillis = 99_000L,
                                    )
                                authStateObserver.invoke(authState)
                            }
                        },
                    )
                },
            )
        val portRange = findFreePortRange()
        val request = requestWith(server = httpServer("s-http"))

        val session = isolator.start(request, portRange.first, portRange.last)
        session.close()

        val saved = persistence.savedSnapshots["s-http"]
        assertNotNull(saved)
        assertEquals(resourceUrl, saved.resourceUrl)
        assertEquals("updated-token", saved.token?.accessToken)
    }

    @Test
    fun start_doesNotBindOauthStateForStdioDownstream() {
        val persistence = RecordingOAuthStatePersistence()
        var capturedAuthState: OAuthState? = null
        var capturedObserver: ((OAuthState) -> Unit)? = null
        val isolator =
            AgentRunMcpIsolator(
                logger = IsolatorTestLogger,
                oauthStateStoreBaseDir = Paths.get("/tmp"),
                oauthStateStoreFactory = { _, _ -> persistence },
                connectionFactory = { config, _, _, _, _, _, _, authState, authStateObserver ->
                    capturedAuthState = authState
                    capturedObserver = authStateObserver
                    FakeMcpServerConnection(config = config)
                },
            )
        val portRange = findFreePortRange()
        val request = requestWith(server = stdioServer("s-stdio"))

        val session = isolator.start(request, portRange.first, portRange.last)
        session.close()

        assertNull(capturedAuthState)
        assertNull(capturedObserver)
        assertTrue(persistence.loadRequests.isEmpty())
        assertTrue(persistence.savedSnapshots.isEmpty())
    }

    private fun requestWith(server: McpServerConfig): AgentExecutionRequest =
        AgentExecutionRequest(
            agent =
                AgentDefinition(
                    id = "agent-1",
                    name = "Agent 1",
                    systemPrompt = "You are helpful",
                    tools = listOf(ToolReference(serverId = server.id, toolName = "lookup", enabled = true)),
                ),
            runtime = AgentRuntime.CODEX_CLI,
            llm = AgentLlmConfig(provider = LlmProvider.OPENAI, model = "gpt-4o-mini", temperature = 0.2),
            codex = AgentCodexConfig(),
            prompt = "run prompt",
            fileSystem =
                AgentFileSystemSettings(
                    path = "/tmp",
                    access = AgentFileSystemAccess.NONE,
                ),
            providerSettings = AgentProviderSettings(enableCodexProvider = true),
            mcpConfig = McpServersConfig(servers = listOf(server)),
            apiKey = null,
        )

    private fun stdioServer(id: String): McpServerConfig =
        McpServerConfig(
            id = id,
            name = id,
            transport = TransportConfig.StdioTransport(command = "test-mcp-server"),
            enabled = true,
        )

    private fun httpServer(id: String): McpServerConfig =
        McpServerConfig(
            id = id,
            name = id,
            transport = TransportConfig.StreamableHttpTransport(url = "https://mcp.example.com/mcp"),
            enabled = true,
        )

    private fun findFreePortRange(): IntRange {
        for (start in 46_000..64_999) {
            if (isPortFree(start)) {
                return start..start
            }
        }
        error("Failed to locate free port")
    }

    private fun isPortFree(port: Int): Boolean =
        runCatching {
            ServerSocket(port).use { socket ->
                socket.reuseAddress = true
            }
        }.isSuccess
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

private class FakeMcpServerConnection(
    override val config: McpServerConfig,
    private val onGetCapabilities: () -> Unit = {},
) : McpServerConnection {
    override val serverId: String = config.id
    override val status: ServerStatus = ServerStatus.Stopped

    override suspend fun connect(): Result<Unit> = Result.success(Unit)

    override suspend fun disconnect() = Unit

    override suspend fun getCapabilities(forceRefresh: Boolean): Result<ServerCapabilities> {
        onGetCapabilities()
        return Result.success(ServerCapabilities())
    }

    override suspend fun callTool(
        toolName: String,
        arguments: JsonObject,
    ): Result<JsonElement> = Result.success(JsonPrimitive("ok"))

    override suspend fun getPrompt(
        name: String,
        arguments: Map<String, String>?,
    ): Result<JsonObject> = Result.success(JsonObject(emptyMap()))

    override suspend fun readResource(uri: String): Result<JsonObject> = Result.success(JsonObject(emptyMap()))
}

private object IsolatorTestLogger : Logger {
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
