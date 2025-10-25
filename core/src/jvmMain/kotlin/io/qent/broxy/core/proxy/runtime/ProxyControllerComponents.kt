package io.qent.broxy.core.proxy.runtime

import io.qent.broxy.core.mcp.DefaultMcpServerConnection
import io.qent.broxy.core.mcp.IsolatedMcpServerConnection
import io.qent.broxy.core.mcp.McpServerConnection
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.ServerStatus
import io.qent.broxy.core.mcp.auth.AuthorizationStatusListener
import io.qent.broxy.core.mcp.auth.OAuthState
import io.qent.broxy.core.mcp.auth.OAuthStateStore
import io.qent.broxy.core.mcp.auth.resolveOAuthResourceUrl
import io.qent.broxy.core.mcp.auth.restoreFromLocked
import io.qent.broxy.core.mcp.auth.toSnapshotLocked
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.proxy.ProxyMcpServer
import io.qent.broxy.core.proxy.inbound.InboundServer
import io.qent.broxy.core.proxy.inbound.InboundServerFactory
import io.qent.broxy.core.proxy.inbound.RequestTimeoutConfigurableInbound
import io.qent.broxy.core.utils.CollectingLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.min

private const val MILLIS_PER_SECOND = 1_000L
private const val MIN_REFRESH_INTERVAL_SECONDS = 30
private const val DEFAULT_REFRESH_INTERVAL_MILLIS = 300_000L
private const val DEFAULT_REFRESH_PARALLELISM = 1
private const val DEFAULT_MIN_REFRESH_PARALLELISM = 1
private const val DEFAULT_MAX_REFRESH_PARALLELISM = 4

internal data class TimeoutConfig(
    val callTimeoutMillis: Long,
    val capabilitiesTimeoutMillis: Long,
    val authorizationTimeoutMillis: Long,
)

internal data class ManagedDownstream(
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

    fun updateAuthorizationTimeout(millis: Long) {
        connection.updateAuthorizationTimeout(millis)
    }

    fun updateConnectionRetryCount(count: Int) {
        connection.updateConnectionRetryCount(count)
    }

    suspend fun shutdown() {
        runCatching { isolated.disconnect() }
        isolated.close()
    }
}

internal data class DownstreamSnapshot(
    val managed: Map<String, ManagedDownstream>,
    val downstreams: List<McpServerConnection>,
)

internal data class DownstreamUpdate(
    val snapshot: DownstreamSnapshot,
    val reusedIds: Set<String>,
    val changedIds: Set<String>,
    val toDisconnect: List<ManagedDownstream>,
)

internal class DownstreamManager(
    private val logger: CollectingLogger,
    configDir: String?,
    private val serverStatusUpdates: MutableSharedFlow<ServerConnectionUpdate>,
    private val scope: CoroutineScope,
) {
    private val authStateStore = OAuthStateStore(baseDir = resolveConfigDir(configDir), logger = logger)

    suspend fun buildInitial(
        servers: List<McpServerConfig>,
        timeouts: TimeoutConfig,
        connectionRetryCount: Int,
    ): DownstreamSnapshot {
        val enabledConfigs = servers.filter { it.enabled }
        val managed = mutableMapOf<String, ManagedDownstream>()
        for (cfg in enabledConfigs) {
            managed[cfg.id] = createManagedDownstream(cfg, timeouts, connectionRetryCount)
        }
        val downstreams = enabledConfigs.mapNotNull { managed[it.id]?.isolated }
        return DownstreamSnapshot(managed = managed, downstreams = downstreams)
    }

    @Suppress("LongParameterList")
    suspend fun update(
        servers: List<McpServerConfig>,
        current: Map<String, ManagedDownstream>,
        previousTimeouts: TimeoutConfig,
        nextTimeouts: TimeoutConfig,
        previousRetryCount: Int,
        nextRetryCount: Int,
    ): DownstreamUpdate {
        val enabledConfigs = servers.filter { it.enabled }
        val updatePlan = buildUpdatePlan(enabledConfigs, current, nextTimeouts, nextRetryCount)
        applyTimeoutUpdates(updatePlan, previousTimeouts, nextTimeouts)
        applyRetryUpdates(updatePlan, previousRetryCount, nextRetryCount)
        val downstreams = enabledConfigs.mapNotNull { updatePlan.updated[it.id]?.isolated }
        return DownstreamUpdate(
            snapshot = DownstreamSnapshot(managed = updatePlan.updated, downstreams = downstreams),
            reusedIds = updatePlan.reusedIds,
            changedIds = updatePlan.changedIds,
            toDisconnect = updatePlan.toDisconnect,
        )
    }

    private data class UpdatePlan(
        val updated: MutableMap<String, ManagedDownstream>,
        val reusedIds: MutableSet<String>,
        val changedIds: MutableSet<String>,
        val toDisconnect: MutableList<ManagedDownstream>,
    )

    private suspend fun buildUpdatePlan(
        enabledConfigs: List<McpServerConfig>,
        current: Map<String, ManagedDownstream>,
        nextTimeouts: TimeoutConfig,
        nextRetryCount: Int,
    ): UpdatePlan {
        val nextById = enabledConfigs.associateBy { it.id }
        val updated = mutableMapOf<String, ManagedDownstream>()
        val reusedIds = mutableSetOf<String>()
        val changedIds = mutableSetOf<String>()
        val toDisconnect = mutableListOf<ManagedDownstream>()

        for (cfg in enabledConfigs) {
            val existing = current[cfg.id]
            if (existing != null && existing.config == cfg) {
                updated[cfg.id] = existing
                reusedIds += cfg.id
            } else {
                if (existing != null) {
                    toDisconnect += existing
                }
                val created = createManagedDownstream(cfg, nextTimeouts, nextRetryCount)
                updated[cfg.id] = created
                changedIds += cfg.id
            }
        }

        current.values
            .filterNot { it.serverId in nextById }
            .forEach { toDisconnect += it }

        return UpdatePlan(
            updated = updated,
            reusedIds = reusedIds,
            changedIds = changedIds,
            toDisconnect = toDisconnect,
        )
    }

    private fun applyTimeoutUpdates(
        plan: UpdatePlan,
        previousTimeouts: TimeoutConfig,
        nextTimeouts: TimeoutConfig,
    ) {
        if (previousTimeouts == nextTimeouts) return
        plan.reusedIds.forEach { id ->
            val managed = plan.updated[id] ?: return@forEach
            if (previousTimeouts.callTimeoutMillis != nextTimeouts.callTimeoutMillis) {
                managed.updateCallTimeout(nextTimeouts.callTimeoutMillis)
            }
            if (previousTimeouts.capabilitiesTimeoutMillis != nextTimeouts.capabilitiesTimeoutMillis) {
                managed.updateCapabilitiesTimeout(nextTimeouts.capabilitiesTimeoutMillis)
            }
            if (previousTimeouts.authorizationTimeoutMillis != nextTimeouts.authorizationTimeoutMillis) {
                managed.updateAuthorizationTimeout(nextTimeouts.authorizationTimeoutMillis)
            }
        }
    }

    private fun applyRetryUpdates(
        plan: UpdatePlan,
        previousRetryCount: Int,
        nextRetryCount: Int,
    ) {
        if (previousRetryCount == nextRetryCount) return
        plan.reusedIds.forEach { id ->
            val managed = plan.updated[id] ?: return@forEach
            managed.updateConnectionRetryCount(nextRetryCount)
        }
    }

    private suspend fun createManagedDownstream(
        config: McpServerConfig,
        timeouts: TimeoutConfig,
        connectionRetryCount: Int,
    ): ManagedDownstream {
        val authState = loadAuthState(config)
        val authorizationListener =
            object : AuthorizationStatusListener {
                override fun onAuthorizationStart() {
                    serverStatusUpdates.tryEmit(
                        ServerConnectionUpdate(
                            serverId = config.id,
                            status = ServerConnectionStatus.Authorization,
                        ),
                    )
                }

                override fun onAuthorizationComplete() {
                    serverStatusUpdates.tryEmit(
                        ServerConnectionUpdate(
                            serverId = config.id,
                            status = ServerConnectionStatus.Connecting,
                        ),
                    )
                }
            }
        val authStateObserver: (OAuthState) -> Unit = { state ->
            scope.launch {
                runCatching { persistAuthState(config, state) }
                    .onFailure { logger.warn("Failed to persist OAuth state for '${config.id}'", it) }
            }
        }
        val connection =
            DefaultMcpServerConnection(
                config = config,
                logger = logger,
                authState = authState,
                authorizationStatusListener = authorizationListener,
                authStateObserver = authStateObserver,
                maxRetries = connectionRetryCount,
                initialCallTimeoutMillis = timeouts.callTimeoutMillis,
                initialCapabilitiesTimeoutMillis = timeouts.capabilitiesTimeoutMillis,
                initialAuthorizationTimeoutMillis = timeouts.authorizationTimeoutMillis,
            )
        return ManagedDownstream(connection, IsolatedMcpServerConnection(connection))
    }

    private suspend fun loadAuthState(config: McpServerConfig): OAuthState? {
        val resourceUrl = resolveAuthResourceUrl(config) ?: return null
        val state = OAuthState()
        authStateStore.load(config.id, resourceUrl)?.let { snapshot ->
            state.restoreFromLocked(snapshot)
        }
        return state
    }

    private suspend fun persistAuthState(
        config: McpServerConfig,
        state: OAuthState,
    ) {
        val resourceUrl = resolveAuthResourceUrl(config) ?: return
        val snapshot = state.toSnapshotLocked(resourceUrl)
        authStateStore.save(config.id, snapshot)
    }

    private fun resolveAuthResourceUrl(config: McpServerConfig): String? =
        when (val transport = config.transport) {
            is TransportConfig.HttpTransport -> resolveOAuthResourceUrl(transport.url)
            is TransportConfig.StreamableHttpTransport -> resolveOAuthResourceUrl(transport.url)
            is TransportConfig.WebSocketTransport -> resolveOAuthResourceUrl(transport.url)
            else -> null
        }
}

internal class ProxyRuntimeLifecycle(
    private val logger: CollectingLogger,
    private val emitCapabilities: (Map<String, ServerCapabilities>) -> Unit,
) {
    private val stateLock = Mutex()

    @Volatile
    private var state: RuntimeState = RuntimeState.Stopped

    private sealed interface RuntimeState {
        data object Stopped : RuntimeState

        data object Starting : RuntimeState

        data class Running(
            val proxy: ProxyMcpServer,
            val inbound: InboundServer,
        ) : RuntimeState

        data object Stopping : RuntimeState
    }

    @Suppress("LongParameterList", "TooGenericExceptionCaught")
    suspend fun start(
        downstreams: List<McpServerConnection>,
        preset: Preset,
        inbound: TransportConfig,
        requestTimeoutMillis: Long,
        awaitInitialCapabilities: Boolean,
        fallbackPromptsAndResourcesToTools: Boolean,
    ): ProxyMcpServer {
        stateLock.withLock {
            check(state is RuntimeState.Stopped) { "Proxy is already running" }
            state = RuntimeState.Starting
        }
        val proxy =
            ProxyMcpServer(
                downstreams,
                logger = logger,
                onCapabilitiesUpdated = { capabilities ->
                    (state as? RuntimeState.Running)?.inbound?.refreshCapabilities()
                    emitCapabilities(capabilities)
                },
                fallbackPromptsAndResourcesToToolsEnabled = fallbackPromptsAndResourcesToTools,
            )
        var inboundServer: InboundServer? = null
        try {
            proxy.start(preset, inbound)
            if (awaitInitialCapabilities) {
                proxy.refreshFilteredCapabilities()
            }
            inboundServer =
                InboundServerFactory.create(
                    inbound,
                    proxy,
                    logger,
                    requestTimeoutMillis = requestTimeoutMillis,
                )
            val status = inboundServer.start()
            if (status is ServerStatus.Error) {
                throw IllegalStateException(status.message ?: "Failed to start inbound server")
            }
            stateLock.withLock {
                if (state !is RuntimeState.Starting) {
                    inboundServer.stop()
                    state = RuntimeState.Stopped
                    error("Proxy startup interrupted")
                }
                state = RuntimeState.Running(proxy, inboundServer)
            }
        } catch (error: Exception) {
            inboundServer?.stop()
            stateLock.withLock {
                state = RuntimeState.Stopped
            }
            throw error
        }
        return proxy
    }

    suspend fun stop() {
        val running =
            stateLock.withLock {
                when (val current = state) {
                    is RuntimeState.Running -> {
                        state = RuntimeState.Stopping
                        current
                    }
                    is RuntimeState.Starting -> {
                        state = RuntimeState.Stopping
                        null
                    }
                    is RuntimeState.Stopping -> null
                    is RuntimeState.Stopped -> null
                }
            }
        running?.inbound?.stop()
        stateLock.withLock { state = RuntimeState.Stopped }
    }

    fun applyPreset(preset: Preset) {
        val proxy = requireProxy()
        proxy.applyPreset(preset)
        refreshInboundCapabilities()?.getOrThrow()
    }

    fun updateFallbackPromptsAndResourcesToTools(enabled: Boolean) {
        (state as? RuntimeState.Running)?.proxy?.fallbackPromptsAndResourcesToTools = enabled
        refreshInboundCapabilities()
    }

    fun updateInboundTimeout(requestTimeoutMillis: Long) {
        val inbound = (state as? RuntimeState.Running)?.inbound
        (inbound as? RequestTimeoutConfigurableInbound)?.updateRequestTimeoutMillis(requestTimeoutMillis)
    }

    fun updateDownstreams(downstreams: List<McpServerConnection>) {
        requireProxy().updateDownstreams(downstreams)
    }

    fun refreshInboundCapabilities(): Result<Unit>? {
        val result = (state as? RuntimeState.Running)?.inbound?.refreshCapabilities()
        result?.exceptionOrNull()?.let { logger.warn("Failed to refresh inbound capabilities", it) }
        return result
    }

    fun requireProxy(): ProxyMcpServer = (state as? RuntimeState.Running)?.proxy ?: error("Proxy is not running")

    fun currentProxy(): ProxyMcpServer? = (state as? RuntimeState.Running)?.proxy
}

internal data class RefreshConcurrencyConfig(
    val minParallelism: Int = DEFAULT_MIN_REFRESH_PARALLELISM,
    val maxParallelism: Int = DEFAULT_MAX_REFRESH_PARALLELISM,
    val defaultParallelism: Int = DEFAULT_REFRESH_PARALLELISM,
    val limitByCpu: Boolean = true,
) {
    init {
        require(minParallelism >= 1) { "minParallelism must be >= 1" }
        require(maxParallelism >= minParallelism) { "maxParallelism must be >= minParallelism" }
        require(defaultParallelism in minParallelism..maxParallelism) {
            "defaultParallelism must be within [$minParallelism, $maxParallelism]"
        }
    }
}

internal class RefreshScheduler(
    private val scope: CoroutineScope,
    private val logger: CollectingLogger,
    private val serverStatusUpdates: MutableSharedFlow<ServerConnectionUpdate>,
    private var concurrencyConfig: RefreshConcurrencyConfig = RefreshConcurrencyConfig(),
    private val managedProvider: () -> Map<String, ManagedDownstream>,
) {
    private var refreshJob: Job? = null
    private var periodicRefreshJob: Job? = null
    private var refreshLimiter = Semaphore(concurrencyConfig.defaultParallelism)
    private var refreshIntervalMillis: Long = DEFAULT_REFRESH_INTERVAL_MILLIS

    fun updateIntervalSeconds(seconds: Int) {
        refreshIntervalMillis =
            when {
                seconds <= 0 -> 0L
                else -> seconds.coerceAtLeast(MIN_REFRESH_INTERVAL_SECONDS).toLong() * MILLIS_PER_SECOND
            }
    }

    fun resetLimiter(serverCount: Int) {
        refreshLimiter = Semaphore(computeRefreshParallelism(serverCount))
    }

    fun stop() {
        refreshJob?.cancel()
        refreshJob = null
        periodicRefreshJob?.cancel()
        periodicRefreshJob = null
    }

    fun startInitial(
        proxy: ProxyMcpServer,
        downstreams: List<McpServerConnection>,
    ) {
        refreshJob?.cancel()
        val serverIds = downstreams.map { it.serverId }
        refreshJob =
            scope.launch {
                refreshServers(proxy, serverIds, "Initial")
            }
    }

    fun startPeriodic(
        proxy: ProxyMcpServer,
        downstreams: List<McpServerConnection>,
    ) {
        periodicRefreshJob?.cancel()
        periodicRefreshJob = null
        if (refreshIntervalMillis <= 0L || downstreams.isEmpty()) return
        val serverIds = downstreams.map { it.serverId }
        periodicRefreshJob =
            scope.launch {
                refreshJob?.join()
                while (isActive) {
                    delay(refreshIntervalMillis)
                    refreshServers(proxy, serverIds, "Background")
                }
            }
    }

    suspend fun refreshServers(
        proxy: ProxyMcpServer,
        serverIds: Collection<String>,
        label: String,
    ) {
        if (serverIds.isEmpty()) return
        supervisorScope {
            serverIds
                .map { serverId ->
                    launch {
                        val refreshResult =
                            runCatching {
                                refreshLimiter.withPermit {
                                    proxy.refreshServerCapabilities(serverId)
                                }
                            }
                        refreshResult.exceptionOrNull()?.let { error ->
                            if (error is CancellationException) throw error
                            logger.warn("$label capabilities refresh failed for '$serverId'", error)
                        }
                        val connection = managedProvider()[serverId]?.connection ?: return@launch
                        val status = connection.status
                        if (status is ServerStatus.Error) {
                            serverStatusUpdates.tryEmit(
                                ServerConnectionUpdate(
                                    serverId = serverId,
                                    status = ServerConnectionStatus.Error,
                                    errorMessage = status.message,
                                ),
                            )
                        }
                    }
                }.joinAll()
        }
    }

    private fun computeRefreshParallelism(serverCount: Int): Int {
        val cpuLimit = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val configuredMax =
            if (concurrencyConfig.limitByCpu) {
                min(concurrencyConfig.maxParallelism, cpuLimit)
            } else {
                concurrencyConfig.maxParallelism
            }
        val minParallel = concurrencyConfig.minParallelism
        return serverCount.coerceAtLeast(minParallel).coerceAtMost(configuredMax)
    }
}

private fun resolveConfigDir(configDir: String?): Path =
    if (configDir.isNullOrBlank()) {
        Paths.get(System.getProperty("user.home"), ".config", "broxy")
    } else {
        Paths.get(configDir)
    }
