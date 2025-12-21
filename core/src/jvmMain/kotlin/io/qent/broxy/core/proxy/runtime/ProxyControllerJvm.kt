package io.qent.broxy.core.proxy.runtime

import io.qent.broxy.core.mcp.DefaultMcpServerConnection
import io.qent.broxy.core.mcp.IsolatedMcpServerConnection
import io.qent.broxy.core.mcp.McpServerConnection
import io.qent.broxy.core.mcp.ServerStatus
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.proxy.ProxyMcpServer
import io.qent.broxy.core.proxy.inbound.InboundServer
import io.qent.broxy.core.proxy.inbound.InboundServerFactory
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.core.utils.LogEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private data class ManagedDownstream(
    val connection: DefaultMcpServerConnection,
    val isolated: IsolatedMcpServerConnection,
) {
    val serverId: String
        get() = connection.serverId

    val config: McpServerConfig
        get() = connection.config

    fun updateCallTimeout(millis: Long) {
        connection.updateCallTimeout(millis)
    }

    fun updateCapabilitiesTimeout(millis: Long) {
        connection.updateCapabilitiesTimeout(millis)
    }

    suspend fun shutdown() {
        runCatching { isolated.disconnect() }
        isolated.close()
    }
}

private class JvmProxyController(
    private val logger: CollectingLogger,
) : ProxyController {
    private var downstreams: List<McpServerConnection> = emptyList()
    private var managedDownstreams: Map<String, ManagedDownstream> = emptyMap()
    private var proxy: ProxyMcpServer? = null
    private var inboundServer: InboundServer? = null
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var refreshJob: Job? = null

    @Volatile
    private var callTimeoutMillis: Long = 60_000

    @Volatile
    private var capabilitiesTimeoutMillis: Long = 30_000

    override val logs: Flow<LogEvent> get() = logger.events

    override fun start(
        servers: List<McpServerConfig>,
        preset: Preset,
        inbound: TransportConfig,
        callTimeoutSeconds: Int,
        capabilitiesTimeoutSeconds: Int,
    ): Result<Unit> =
        runCatching {
            runCatching { stop() }
            callTimeoutMillis = callTimeoutSeconds.toLong() * 1_000L
            capabilitiesTimeoutMillis = capabilitiesTimeoutSeconds.toLong() * 1_000L

            val enabledConfigs = servers.filter { it.enabled }
            val managed =
                enabledConfigs.associate { cfg ->
                    cfg.id to createManagedDownstream(cfg)
                }
            val downstreams = enabledConfigs.mapNotNull { managed[it.id]?.isolated }
            try {
                val proxy =
                    ProxyMcpServer(
                        downstreams,
                        logger = logger,
                        onCapabilitiesUpdated = {
                            inboundServer?.refreshCapabilities()
                        },
                    )
                proxy.start(preset, inbound)
                val inboundServer = InboundServerFactory.create(inbound, proxy, logger)
                val status = inboundServer.start()
                if (status is ServerStatus.Error) {
                    throw IllegalStateException(status.message ?: "Failed to start inbound server")
                }
                this.proxy = proxy
                this.inboundServer = inboundServer
                managedDownstreams = managed
                this.downstreams = downstreams
                startInitialRefresh(proxy, downstreams)
            } catch (t: Throwable) {
                runBlocking { managed.values.map { async { it.shutdown() } }.awaitAll() }
                throw t
            }
        }

    override fun stop(): Result<Unit> =
        runCatching {
            refreshJob?.cancel()
            refreshJob = null
            inboundServer?.stop()
            inboundServer = null
            val managed = managedDownstreams.values
            downstreams = emptyList()
            managedDownstreams = emptyMap()
            proxy = null
            runBlocking { managed.map { async { it.shutdown() } }.awaitAll() }
        }

    override fun applyPreset(preset: Preset): Result<Unit> =
        runCatching {
            val proxy = this.proxy ?: error("Proxy is not running")
            proxy.applyPreset(preset)
            inboundServer?.refreshCapabilities()?.getOrThrow()
        }

    override fun updateServers(
        servers: List<McpServerConfig>,
        callTimeoutSeconds: Int,
        capabilitiesTimeoutSeconds: Int,
    ): Result<Unit> =
        runCatching {
            val proxy = this.proxy ?: error("Proxy is not running")
            refreshJob?.cancel()
            refreshJob = null

            val previousCallTimeoutMillis = callTimeoutMillis
            val previousCapabilitiesTimeoutMillis = capabilitiesTimeoutMillis
            callTimeoutMillis = callTimeoutSeconds.toLong() * 1_000L
            capabilitiesTimeoutMillis = capabilitiesTimeoutSeconds.toLong() * 1_000L

            val enabledConfigs = servers.filter { it.enabled }
            val nextById = enabledConfigs.associateBy { it.id }
            val current = managedDownstreams
            val updated = mutableMapOf<String, ManagedDownstream>()
            val reusedIds = mutableSetOf<String>()
            val changedIds = mutableSetOf<String>()
            val toDisconnect = mutableListOf<ManagedDownstream>()

            enabledConfigs.forEach { cfg ->
                val existing = current[cfg.id]
                if (existing != null && existing.config == cfg) {
                    updated[cfg.id] = existing
                    reusedIds += cfg.id
                } else {
                    if (existing != null) {
                        toDisconnect += existing
                    }
                    val created = createManagedDownstream(cfg)
                    updated[cfg.id] = created
                    changedIds += cfg.id
                }
            }

            current.values
                .filterNot { it.serverId in nextById }
                .forEach { toDisconnect += it }

            managedDownstreams = updated
            downstreams = enabledConfigs.mapNotNull { updated[it.id]?.isolated }

            val callTimeoutChanged = previousCallTimeoutMillis != callTimeoutMillis
            val capabilitiesTimeoutChanged = previousCapabilitiesTimeoutMillis != capabilitiesTimeoutMillis
            if (callTimeoutChanged || capabilitiesTimeoutChanged) {
                reusedIds.forEach { id ->
                    val managed = updated[id] ?: return@forEach
                    if (callTimeoutChanged) managed.updateCallTimeout(callTimeoutMillis)
                    if (capabilitiesTimeoutChanged) managed.updateCapabilitiesTimeout(capabilitiesTimeoutMillis)
                }
            }

            proxy.updateDownstreams(downstreams)
            runBlocking {
                val removedIds = toDisconnect.map { it.serverId }.toSet()
                removedIds.forEach { proxy.removeServerCapabilities(it) }
                if (changedIds.isNotEmpty()) {
                    changedIds.map { id -> async { proxy.refreshServerCapabilities(id) } }.awaitAll()
                }
            }
            inboundServer?.refreshCapabilities()?.getOrThrow()

            runBlocking {
                toDisconnect.distinctBy { it.serverId }.map { async { it.shutdown() } }.awaitAll()
            }
        }

    override fun updateCallTimeout(seconds: Int) {
        callTimeoutMillis = seconds.toLong() * 1_000L
        managedDownstreams.values.forEach { it.updateCallTimeout(callTimeoutMillis) }
    }

    override fun updateCapabilitiesTimeout(seconds: Int) {
        capabilitiesTimeoutMillis = seconds.toLong() * 1_000L
        managedDownstreams.values.forEach { it.updateCapabilitiesTimeout(capabilitiesTimeoutMillis) }
    }

    override fun currentProxy(): ProxyMcpServer? = proxy

    private fun createManagedDownstream(config: McpServerConfig): ManagedDownstream {
        val connection =
            DefaultMcpServerConnection(
                config = config,
                logger = logger,
                initialCallTimeoutMillis = callTimeoutMillis,
                initialCapabilitiesTimeoutMillis = capabilitiesTimeoutMillis,
            )
        return ManagedDownstream(connection, IsolatedMcpServerConnection(connection))
    }

    private fun startInitialRefresh(
        proxy: ProxyMcpServer,
        downstreams: List<McpServerConnection>,
    ) {
        refreshJob?.cancel()
        refreshJob =
            refreshScope.launch {
                downstreams.map { server ->
                    launch { proxy.refreshServerCapabilities(server.serverId) }
                }.joinAll()
            }
    }
}

actual fun createProxyController(logger: CollectingLogger): ProxyController = JvmProxyController(logger)

actual fun createStdioProxyController(logger: CollectingLogger): ProxyController = JvmProxyController(logger = logger)
