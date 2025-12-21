package io.qent.broxy.core.proxy.runtime

import io.qent.broxy.core.capabilities.ServerCapsSnapshot
import io.qent.broxy.core.capabilities.toSnapshot
import io.qent.broxy.core.mcp.DefaultMcpServerConnection
import io.qent.broxy.core.mcp.IsolatedMcpServerConnection
import io.qent.broxy.core.mcp.McpServerConnection
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.ServerStatus
import io.qent.broxy.core.mcp.auth.OAuthState
import io.qent.broxy.core.mcp.auth.OAuthStateStore
import io.qent.broxy.core.mcp.auth.resolveOAuthResourceUrl
import io.qent.broxy.core.mcp.auth.restoreFrom
import io.qent.broxy.core.mcp.auth.toSnapshot
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.proxy.ProxyMcpServer
import io.qent.broxy.core.proxy.inbound.InboundServer
import io.qent.broxy.core.proxy.inbound.InboundServerFactory
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.core.utils.LogEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.min

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
    configDir: String?,
) : ProxyController {
    private val authStateStore = OAuthStateStore(baseDir = resolveConfigDir(configDir), logger = logger)
    private var downstreams: List<McpServerConnection> = emptyList()
    private var managedDownstreams: Map<String, ManagedDownstream> = emptyMap()
    private var proxy: ProxyMcpServer? = null
    private var inboundServer: InboundServer? = null
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var refreshJob: Job? = null
    private var periodicRefreshJob: Job? = null
    private var refreshLimiter = Semaphore(DEFAULT_REFRESH_PARALLELISM)
    private var capabilitiesRefreshIntervalMillis: Long = DEFAULT_REFRESH_INTERVAL_MILLIS
    private val _capabilityUpdates = MutableSharedFlow<List<ServerCapsSnapshot>>(replay = 1)

    @Volatile
    private var callTimeoutMillis: Long = 60_000

    @Volatile
    private var capabilitiesTimeoutMillis: Long = 30_000

    override val logs: Flow<LogEvent> get() = logger.events
    override val capabilityUpdates: Flow<List<ServerCapsSnapshot>> get() = _capabilityUpdates

    override fun start(
        servers: List<McpServerConfig>,
        preset: Preset,
        inbound: TransportConfig,
        callTimeoutSeconds: Int,
        capabilitiesTimeoutSeconds: Int,
        capabilitiesRefreshIntervalSeconds: Int,
    ): Result<Unit> =
        runCatching {
            runCatching { stop() }
            callTimeoutMillis = callTimeoutSeconds.toLong() * 1_000L
            capabilitiesTimeoutMillis = capabilitiesTimeoutSeconds.toLong() * 1_000L
            capabilitiesRefreshIntervalMillis = refreshIntervalMillis(capabilitiesRefreshIntervalSeconds)

            val enabledConfigs = servers.filter { it.enabled }
            val managed =
                enabledConfigs.associate { cfg ->
                    cfg.id to createManagedDownstream(cfg)
                }
            val downstreams = enabledConfigs.mapNotNull { managed[it.id]?.isolated }
            resetRefreshLimiter(downstreams.size)
            try {
                val proxy =
                    ProxyMcpServer(
                        downstreams,
                        logger = logger,
                        onCapabilitiesUpdated = { capabilities ->
                            inboundServer?.refreshCapabilities()
                            emitCapabilitySnapshots(capabilities)
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
                startPeriodicRefresh(proxy, downstreams)
            } catch (t: Throwable) {
                runBlocking { managed.values.map { async { it.shutdown() } }.awaitAll() }
                throw t
            }
        }

    override fun stop(): Result<Unit> =
        runCatching {
            refreshJob?.cancel()
            refreshJob = null
            periodicRefreshJob?.cancel()
            periodicRefreshJob = null
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
        capabilitiesRefreshIntervalSeconds: Int,
    ): Result<Unit> =
        runCatching {
            val proxy = this.proxy ?: error("Proxy is not running")
            refreshJob?.cancel()
            refreshJob = null
            periodicRefreshJob?.cancel()
            periodicRefreshJob = null

            val previousCallTimeoutMillis = callTimeoutMillis
            val previousCapabilitiesTimeoutMillis = capabilitiesTimeoutMillis
            callTimeoutMillis = callTimeoutSeconds.toLong() * 1_000L
            capabilitiesTimeoutMillis = capabilitiesTimeoutSeconds.toLong() * 1_000L
            capabilitiesRefreshIntervalMillis = refreshIntervalMillis(capabilitiesRefreshIntervalSeconds)

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
            resetRefreshLimiter(downstreams.size)

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
                    refreshServers(proxy, changedIds, "Updated")
                }
            }
            inboundServer?.refreshCapabilities()?.getOrThrow()

            runBlocking {
                toDisconnect.distinctBy { it.serverId }.map { async { it.shutdown() } }.awaitAll()
            }
            startPeriodicRefresh(proxy, downstreams)
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

    private fun emitCapabilitySnapshots(capabilitiesById: Map<String, ServerCapabilities>) {
        if (capabilitiesById.isEmpty()) return
        val configs = managedDownstreams.values.map { it.config }
        val snapshots =
            configs.mapNotNull { cfg ->
                capabilitiesById[cfg.id]?.toSnapshot(cfg)
            }
        if (snapshots.isNotEmpty()) {
            _capabilityUpdates.tryEmit(snapshots)
        }
    }

    private fun createManagedDownstream(config: McpServerConfig): ManagedDownstream {
        val authState = loadAuthState(config)
        val connection =
            DefaultMcpServerConnection(
                config = config,
                logger = logger,
                authState = authState,
                authStateObserver = { state -> persistAuthState(config, state) },
                initialCallTimeoutMillis = callTimeoutMillis,
                initialCapabilitiesTimeoutMillis = capabilitiesTimeoutMillis,
            )
        return ManagedDownstream(connection, IsolatedMcpServerConnection(connection))
    }

    private fun loadAuthState(config: McpServerConfig): OAuthState? {
        val resourceUrl = resolveAuthResourceUrl(config) ?: return null
        val state = OAuthState()
        authStateStore.load(config.id, resourceUrl)?.let { snapshot ->
            state.restoreFrom(snapshot)
        }
        return state
    }

    private fun persistAuthState(
        config: McpServerConfig,
        state: OAuthState,
    ) {
        val resourceUrl = resolveAuthResourceUrl(config) ?: return
        authStateStore.save(config.id, state.toSnapshot(resourceUrl))
    }

    private fun resolveAuthResourceUrl(config: McpServerConfig): String? =
        when (val transport = config.transport) {
            is TransportConfig.HttpTransport -> resolveOAuthResourceUrl(transport.url)
            is TransportConfig.StreamableHttpTransport -> resolveOAuthResourceUrl(transport.url)
            is TransportConfig.WebSocketTransport -> resolveOAuthResourceUrl(transport.url)
            else -> null
        }

    private fun resetRefreshLimiter(serverCount: Int) {
        refreshLimiter = Semaphore(computeRefreshParallelism(serverCount))
    }

    private fun startPeriodicRefresh(
        proxy: ProxyMcpServer,
        downstreams: List<McpServerConnection>,
    ) {
        periodicRefreshJob?.cancel()
        periodicRefreshJob = null
        if (capabilitiesRefreshIntervalMillis <= 0L || downstreams.isEmpty()) return
        val serverIds = downstreams.map { it.serverId }
        periodicRefreshJob =
            refreshScope.launch {
                refreshJob?.join()
                while (isActive) {
                    delay(capabilitiesRefreshIntervalMillis)
                    refreshServers(proxy, serverIds, "Background")
                }
            }
    }

    private suspend fun refreshServers(
        proxy: ProxyMcpServer,
        serverIds: Collection<String>,
        label: String,
    ) {
        if (serverIds.isEmpty()) return
        supervisorScope {
            serverIds.map { serverId ->
                launch {
                    try {
                        refreshLimiter.withPermit {
                            proxy.refreshServerCapabilities(serverId)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        logger.warn("$label capabilities refresh failed for '$serverId'", t)
                    }
                }
            }.joinAll()
        }
    }

    private fun refreshIntervalMillis(seconds: Int): Long =
        when {
            seconds <= 0 -> 0L
            else -> seconds.coerceAtLeast(MIN_REFRESH_INTERVAL_SECONDS).toLong() * 1_000L
        }

    private fun computeRefreshParallelism(serverCount: Int): Int {
        val cpu = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val maxParallel = min(MAX_REFRESH_PARALLELISM, cpu)
        return serverCount.coerceAtLeast(1).coerceAtMost(maxParallel)
    }

    private fun startInitialRefresh(
        proxy: ProxyMcpServer,
        downstreams: List<McpServerConnection>,
    ) {
        refreshJob?.cancel()
        val serverIds = downstreams.map { it.serverId }
        refreshJob =
            refreshScope.launch {
                refreshServers(proxy, serverIds, "Initial")
            }
    }

    private companion object {
        private const val MIN_REFRESH_INTERVAL_SECONDS = 30
        private const val MAX_REFRESH_PARALLELISM = 4
        private const val DEFAULT_REFRESH_INTERVAL_MILLIS = 300_000L
        private const val DEFAULT_REFRESH_PARALLELISM = 1
    }
}

private fun resolveConfigDir(configDir: String?): Path =
    if (configDir.isNullOrBlank()) {
        Paths.get(System.getProperty("user.home"), ".config", "broxy")
    } else {
        Paths.get(configDir)
    }

actual fun createProxyController(
    logger: CollectingLogger,
    configDir: String?,
): ProxyController = JvmProxyController(logger, configDir)

actual fun createStdioProxyController(
    logger: CollectingLogger,
    configDir: String?,
): ProxyController = JvmProxyController(logger = logger, configDir = configDir)
