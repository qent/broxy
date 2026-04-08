package io.qent.broxy.agents.runtime.mcp

import io.qent.broxy.agents.AgentDefinition
import io.qent.broxy.agents.AgentExecutionRequest
import io.qent.broxy.agents.mergeAgentMcpServers
import io.qent.broxy.core.mcp.DefaultMcpServerConnection
import io.qent.broxy.core.mcp.McpServerConnection
import io.qent.broxy.core.mcp.auth.OAuthState
import io.qent.broxy.core.mcp.auth.OAuthStateSnapshot
import io.qent.broxy.core.mcp.auth.OAuthStateStore
import io.qent.broxy.core.mcp.auth.resolveOAuthResourceUrl
import io.qent.broxy.core.mcp.auth.restoreFromLocked
import io.qent.broxy.core.mcp.auth.toSnapshotLocked
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger
import io.qent.broxy.core.utils.warnJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import java.nio.file.Paths

private const val MIN_TIMEOUT_SECONDS = 1
private const val MILLIS_PER_SECOND = 1_000L

interface OAuthStatePersistence {
    fun load(
        serverId: String,
        resourceUrl: String?,
    ): OAuthStateSnapshot?

    fun save(
        serverId: String,
        snapshot: OAuthStateSnapshot,
    )
}

data class AgentConnectionOptions(
    val maxRetries: Int,
    val ignoreHttpsCertificateErrors: Boolean,
    val callTimeoutMillis: Long,
    val capabilitiesTimeoutMillis: Long,
    val authorizationTimeoutMillis: Long,
    val authState: OAuthState?,
    val authStateObserver: ((OAuthState) -> Unit)?,
)

data class ScopedMcpConnections(
    val usedServerIds: Set<String>,
    val connections: List<McpServerConnection>,
    val preset: Preset,
)

typealias OAuthStateStoreFactory = (baseDir: Path, logger: Logger) -> OAuthStatePersistence

typealias ConnectionFactory =
    (
        config: McpServerConfig,
        logger: Logger,
        options: AgentConnectionOptions,
    ) -> McpServerConnection

class ScopedMcpConnectionsFactory(
    private val logger: Logger = ConsoleLogger,
    private val oauthStateStoreBaseDir: Path =
        Paths.get(System.getProperty("user.home"), ".config", "broxy"),
    private val oauthStateStoreFactory: OAuthStateStoreFactory = { baseDir, stateLogger ->
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
    private val connectionFactory: ConnectionFactory = ::buildConnection,
) {
    fun create(request: AgentExecutionRequest): ScopedMcpConnections {
        val mergedMcp = mergeAgentMcpServers(request.mcpConfig, request.agent)
        mergedMcp.warnings.forEach { warning ->
            logger.warnJson("agent.mcp.compat.warning") {
                put("agentId", JsonPrimitive(request.agent.id))
                put("message", JsonPrimitive(warning))
            }
        }
        val effectiveMcpConfig = mergedMcp.config
        val usedServerIds = resolveUsedServerIds(request.agent)
        val callTimeoutMillis =
            effectiveMcpConfig.requestTimeoutSeconds.coerceAtLeast(MIN_TIMEOUT_SECONDS) * MILLIS_PER_SECOND
        val capabilitiesTimeoutMillis =
            effectiveMcpConfig.capabilitiesTimeoutSeconds.coerceAtLeast(MIN_TIMEOUT_SECONDS) * MILLIS_PER_SECOND
        val authorizationTimeoutMillis =
            effectiveMcpConfig.authorizationTimeoutSeconds.coerceAtLeast(MIN_TIMEOUT_SECONDS) * MILLIS_PER_SECOND
        val scopedServers =
            effectiveMcpConfig.servers
                .asSequence()
                .filter { server -> server.enabled && server.id in usedServerIds }
                .toList()
        val scopedServerIds = scopedServers.mapTo(linkedSetOf()) { it.id }
        val missingScopedServerIds = usedServerIds - scopedServerIds
        if (missingScopedServerIds.isNotEmpty()) {
            logger.warnJson("agent.mcp.scope.missing") {
                put("agentId", JsonPrimitive(request.agent.id))
                put("missingServerIds", JsonPrimitive(missingScopedServerIds.sorted().joinToString(",")))
            }
        }
        val authStateStore = oauthStateStoreFactory(oauthStateStoreBaseDir, logger)
        val mcpConnections =
            scopedServers.map { server ->
                val (authState, authStateObserver) = loadAuthStateBinding(server, authStateStore)
                val options =
                    AgentConnectionOptions(
                        maxRetries = request.mcpConfig.connectionRetryCount,
                        ignoreHttpsCertificateErrors = request.mcpConfig.ignoreHttpsCertificateErrors,
                        callTimeoutMillis = callTimeoutMillis,
                        capabilitiesTimeoutMillis = capabilitiesTimeoutMillis,
                        authorizationTimeoutMillis = authorizationTimeoutMillis,
                        authState = authState,
                        authStateObserver = authStateObserver,
                    )
                connectionFactory(server, logger, options)
            }
        val resolvedAgentTools = buildAgentToolsConnection(request)
        val connections =
            if (resolvedAgentTools != null) {
                mcpConnections + resolvedAgentTools.connection
            } else {
                mcpConnections
            }
        return ScopedMcpConnections(
            usedServerIds = usedServerIds,
            connections = connections,
            preset = buildScopedPreset(request, resolvedAgentTools),
        )
    }

    fun closeConnections(
        agentId: String,
        connections: List<McpServerConnection>,
    ) {
        connections.forEach { connection ->
            runCatching {
                runBlocking { connection.disconnect() }
            }.onFailure { failure ->
                logger.warnJson("agent.downstream.disconnect.failed", failure) {
                    put("agentId", JsonPrimitive(agentId))
                    put("serverId", JsonPrimitive(connection.serverId))
                    put(
                        "errorMessage",
                        JsonPrimitive(failure.message ?: "Failed to disconnect downstream server"),
                    )
                }
            }
        }
    }

    private fun loadAuthStateBinding(
        config: McpServerConfig,
        authStateStore: OAuthStatePersistence,
    ): Pair<OAuthState?, ((OAuthState) -> Unit)?> {
        val resourceUrl = resolveAuthResourceUrl(config) ?: return null to null
        val authState = OAuthState()
        authStateStore.load(config.id, resourceUrl)?.let { snapshot ->
            runBlocking { authState.restoreFromLocked(snapshot) }
        }
        val authStateObserver: (OAuthState) -> Unit = { state ->
            runCatching {
                val snapshot = runBlocking { state.toSnapshotLocked(resourceUrl) }
                authStateStore.save(config.id, snapshot)
            }.onFailure { failure ->
                logger.warn("Failed to persist OAuth state for '${config.id}'", failure)
            }
        }
        return authState to authStateObserver
    }

    private companion object {
        private fun buildConnection(
            config: McpServerConfig,
            logger: Logger,
            options: AgentConnectionOptions,
        ): McpServerConnection =
            DefaultMcpServerConnection(
                config = config,
                logger = logger,
                maxRetries = options.maxRetries,
                ignoreHttpsCertificateErrors = options.ignoreHttpsCertificateErrors,
                authState = options.authState,
                authStateObserver = options.authStateObserver,
                initialCallTimeoutMillis = options.callTimeoutMillis,
                initialCapabilitiesTimeoutMillis = options.capabilitiesTimeoutMillis,
                initialConnectTimeoutMillis = options.capabilitiesTimeoutMillis,
                initialAuthorizationTimeoutMillis = options.authorizationTimeoutMillis,
            )

        private fun resolveAuthResourceUrl(config: McpServerConfig): String? =
            when (val transport = config.transport) {
                is TransportConfig.HttpTransport -> resolveOAuthResourceUrl(transport.url)
                is TransportConfig.StreamableHttpTransport -> resolveOAuthResourceUrl(transport.url)
                is TransportConfig.WebSocketTransport -> resolveOAuthResourceUrl(transport.url)
                else -> null
            }
    }
}

fun resolveUsedServerIds(agent: AgentDefinition): Set<String> =
    buildSet {
        agent.tools
            .asSequence()
            .filter { it.enabled }
            .mapTo(this) { it.serverId }
        agent.prompts
            .orEmpty()
            .asSequence()
            .filter { it.enabled }
            .mapTo(this) { it.serverId }
        agent.resources
            .orEmpty()
            .asSequence()
            .filter { it.enabled }
            .mapTo(this) { it.serverId }
        agent.claudeMcpServers
            .orEmpty()
            .asSequence()
            .map { it.id.trim() }
            .filter { it.isNotBlank() }
            .mapTo(this) { it }
    }

private fun buildScopedPreset(
    request: AgentExecutionRequest,
    resolvedAgentTools: ResolvedAgentTools?,
): Preset {
    val agent = request.agent
    if (shouldUseAllEnabledPreset(agent)) {
        return Preset.allEnabled()
    }
    return Preset(
        id = agent.id,
        name = agent.name,
        tools = agent.tools + (resolvedAgentTools?.toolRefs ?: emptyList()),
        prompts = agent.prompts,
        resources = agent.resources,
    )
}

private fun shouldUseAllEnabledPreset(agent: AgentDefinition): Boolean {
    val hasClaudeScopedServers = !agent.claudeMcpServers.isNullOrEmpty()
    val hasTools = agent.tools.any { it.enabled }
    return hasClaudeScopedServers && !hasTools && agent.prompts == null && agent.resources == null
}
