package io.qent.broxy.agents.codex.mcp

import io.qent.broxy.agents.AgentExecutionRequest
import io.qent.broxy.agents.runtime.mcp.OAuthStatePersistence
import io.qent.broxy.agents.runtime.mcp.OAuthStateStoreFactory
import io.qent.broxy.agents.runtime.mcp.ScopedMcpConnectionsFactory
import io.qent.broxy.core.mcp.DefaultMcpServerConnection
import io.qent.broxy.core.mcp.McpServerConnection
import io.qent.broxy.core.mcp.ServerStatus
import io.qent.broxy.core.mcp.auth.OAuthState
import io.qent.broxy.core.mcp.auth.OAuthStateSnapshot
import io.qent.broxy.core.mcp.auth.OAuthStateStore
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.TransportConfig.StreamableHttpTransport
import io.qent.broxy.core.proxy.ProxyMcpServer
import io.qent.broxy.core.proxy.inbound.InboundServer
import io.qent.broxy.core.proxy.inbound.InboundServerFactory
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.nio.file.Paths

private const val MIN_TIMEOUT_SECONDS = 1
private const val MILLIS_PER_SECOND = 1_000L

data class IsolatedMcpSession(
    val endpointUrl: String,
    val close: () -> Unit,
)

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

/**
 * Creates a short-lived inbound MCP server that only exposes capabilities selected by the agent.
 */
class AgentRunMcpIsolator(
    private val logger: Logger = ConsoleLogger,
    private val oauthStateStoreBaseDir: Path =
        Paths.get(System.getProperty("user.home"), ".config", "broxy"),
    private val portAllocator: AgentPortRangeAllocator = AgentPortRangeAllocator(),
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
    private val scopedConnectionsFactory: ScopedMcpConnectionsFactory =
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
        ),
) {
    fun start(
        request: AgentExecutionRequest,
        portRangeStart: Int,
        portRangeEnd: Int,
    ): IsolatedMcpSession {
        val scopedConnections = scopedConnectionsFactory.create(request)
        val connections = scopedConnections.connections
        val callTimeoutMillis =
            request.mcpConfig.requestTimeoutSeconds.coerceAtLeast(MIN_TIMEOUT_SECONDS) * MILLIS_PER_SECOND
        val port = portAllocator.acquire(portRangeStart, portRangeEnd)
        val endpointUrl = "http://127.0.0.1:$port/mcp"
        val inboundTransport =
            StreamableHttpTransport(
                url = endpointUrl,
            )
        val proxy =
            ProxyMcpServer(
                downstreams = connections,
                logger = logger,
                fallbackPromptsAndResourcesToToolsEnabled = request.mcpConfig.fallbackPromptsAndResourcesToTools,
                adapterModeEnabled = request.mcpConfig.adapterMode,
            )
        val inbound =
            runCatching {
                proxy.start(scopedConnections.preset, inboundTransport)
                runBlocking { proxy.refreshFilteredCapabilities() }
                startInbound(
                    transport = inboundTransport,
                    proxy = proxy,
                    requestTimeoutMillis = callTimeoutMillis,
                )
            }.getOrElse { failure ->
                runCatching { proxy.stop() }
                scopedConnectionsFactory.closeConnections(request.agent.id, connections)
                portAllocator.release(port)
                throw failure
            }

        return IsolatedMcpSession(
            endpointUrl = endpointUrl,
            close = {
                runCatching { inbound.stop() }
                runCatching { proxy.stop() }
                scopedConnectionsFactory.closeConnections(request.agent.id, connections)
                portAllocator.release(port)
            },
        )
    }

    private fun startInbound(
        transport: StreamableHttpTransport,
        proxy: ProxyMcpServer,
        requestTimeoutMillis: Long,
    ): InboundServer {
        val inbound =
            InboundServerFactory.create(
                transport = transport,
                proxy = proxy,
                logger = logger,
                requestTimeoutMillis = requestTimeoutMillis,
            )
        return runCatching {
            val status = inbound.start()
            if (status !is ServerStatus.Running) {
                val message =
                    when (status) {
                        is ServerStatus.Error -> status.message ?: "Failed to start isolated MCP server"
                        else -> "Failed to start isolated MCP server"
                    }
                error(message)
            }
            inbound.refreshCapabilities().getOrElse { throw it }
            inbound
        }.getOrElse { failure ->
            runCatching { inbound.stop() }
            throw failure
        }
    }
}
