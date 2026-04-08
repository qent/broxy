package io.qent.broxy.core.proxy.runtime

import io.qent.broxy.core.config.JsonConfigurationRepository
import io.qent.broxy.core.mcp.McpServerConnection
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.proxy.ProxyMcpServer
import io.qent.broxy.core.proxy.inbound.InboundPresetResolver
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.core.utils.LogEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking

@Suppress("TooManyFunctions")
private class JvmProxyController(
    private val logger: CollectingLogger,
    configDir: String?,
) : ProxyController,
    ProxyControllerInternal {
    private companion object {
        private const val MILLIS_PER_SECOND = 1_000L
        private const val MIN_RETRY_COUNT = 1
        private const val DEFAULT_CALL_TIMEOUT_MILLIS = 60_000L
        private const val DEFAULT_CAPABILITIES_TIMEOUT_MILLIS = 30_000L
        private const val DEFAULT_CONNECTION_RETRY_COUNT = 3
        private const val DEFAULT_AUTHORIZATION_TIMEOUT_MILLIS = 120_000L
    }

    private var downstreams: List<McpServerConnection> = emptyList()
    private var managedDownstreams: Map<String, ManagedDownstream> = emptyMap()
    private val presetResolver: InboundPresetResolver =
        { presetId ->
            runCatching {
                JsonConfigurationRepository(
                    baseDir = resolveConfigDir(configDir),
                    logger = logger,
                ).loadPreset(presetId)
            }
        }
    private val _capabilityUpdates = MutableSharedFlow<Map<String, ServerCapabilities>>(replay = 1)
    private val _serverStatusUpdates = MutableSharedFlow<ServerConnectionUpdate>(extraBufferCapacity = 32)
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val downstreamManager = DownstreamManager(logger, configDir, _serverStatusUpdates, runtimeScope)
    private val refreshScheduler = RefreshScheduler(runtimeScope, logger, _serverStatusUpdates) { managedDownstreams }
    private val runtimeLifecycle = ProxyRuntimeLifecycle(logger, ::emitCapabilities)

    @Volatile
    private var callTimeoutMillis: Long = DEFAULT_CALL_TIMEOUT_MILLIS

    @Volatile
    private var capabilitiesTimeoutMillis: Long = DEFAULT_CAPABILITIES_TIMEOUT_MILLIS

    @Volatile
    private var authorizationTimeoutMillis: Long = DEFAULT_AUTHORIZATION_TIMEOUT_MILLIS

    @Volatile
    private var connectionRetryCount: Int = DEFAULT_CONNECTION_RETRY_COUNT

    @Volatile
    private var ignoreHttpsCertificateErrors: Boolean = false

    @Volatile
    private var fallbackPromptsAndResourcesToTools: Boolean = false

    @Volatile
    private var adapterMode: Boolean = false

    override val logs: Flow<LogEvent> get() = logger.events
    override val capabilityUpdates: Flow<Map<String, ServerCapabilities>> get() = _capabilityUpdates
    override val serverStatusUpdates: Flow<ServerConnectionUpdate> get() = _serverStatusUpdates

    override fun start(
        servers: List<McpServerConfig>,
        preset: Preset,
        inbound: TransportConfig,
        callTimeoutSeconds: Int,
        capabilitiesTimeoutSeconds: Int,
        authorizationTimeoutSeconds: Int,
        connectionRetryCount: Int,
        ignoreHttpsCertificateErrors: Boolean,
        capabilitiesRefreshIntervalSeconds: Int,
        fallbackPromptsAndResourcesToTools: Boolean,
        adapterMode: Boolean,
    ): Result<Unit> =
        runCatching {
            runBlocking {
                startInternal(
                    servers = servers,
                    preset = preset,
                    inbound = inbound,
                    callTimeoutSeconds = callTimeoutSeconds,
                    capabilitiesTimeoutSeconds = capabilitiesTimeoutSeconds,
                    authorizationTimeoutSeconds = authorizationTimeoutSeconds,
                    connectionRetryCount = connectionRetryCount,
                    ignoreHttpsCertificateErrors = ignoreHttpsCertificateErrors,
                    capabilitiesRefreshIntervalSeconds = capabilitiesRefreshIntervalSeconds,
                    fallbackPromptsAndResourcesToTools = fallbackPromptsAndResourcesToTools,
                    adapterMode = adapterMode,
                )
            }
        }

    override fun stop(): Result<Unit> =
        runCatching {
            runBlocking { stopInternal() }
        }

    override fun applyPreset(preset: Preset): Result<Unit> =
        runCatching {
            runtimeLifecycle.applyPreset(preset)
        }

    override fun updateServers(
        servers: List<McpServerConfig>,
        callTimeoutSeconds: Int,
        capabilitiesTimeoutSeconds: Int,
        authorizationTimeoutSeconds: Int,
        connectionRetryCount: Int,
        ignoreHttpsCertificateErrors: Boolean,
        capabilitiesRefreshIntervalSeconds: Int,
        fallbackPromptsAndResourcesToTools: Boolean,
        adapterMode: Boolean,
    ): Result<Unit> =
        runCatching {
            runBlocking {
                updateServersInternal(
                    servers = servers,
                    callTimeoutSeconds = callTimeoutSeconds,
                    capabilitiesTimeoutSeconds = capabilitiesTimeoutSeconds,
                    authorizationTimeoutSeconds = authorizationTimeoutSeconds,
                    connectionRetryCount = connectionRetryCount,
                    ignoreHttpsCertificateErrors = ignoreHttpsCertificateErrors,
                    capabilitiesRefreshIntervalSeconds = capabilitiesRefreshIntervalSeconds,
                    fallbackPromptsAndResourcesToTools = fallbackPromptsAndResourcesToTools,
                    adapterMode = adapterMode,
                )
            }
        }

    override fun updateCallTimeout(seconds: Int) {
        callTimeoutMillis = seconds.toLong() * MILLIS_PER_SECOND
        managedDownstreams.values.forEach { it.updateCallTimeout(callTimeoutMillis) }
        runtimeLifecycle.updateInboundTimeout(callTimeoutMillis)
    }

    override fun updateCapabilitiesTimeout(seconds: Int) {
        capabilitiesTimeoutMillis = seconds.toLong() * MILLIS_PER_SECOND
        managedDownstreams.values.forEach { it.updateCapabilitiesTimeout(capabilitiesTimeoutMillis) }
    }

    override fun updateConnectionRetryCount(count: Int) {
        connectionRetryCount = count.coerceAtLeast(MIN_RETRY_COUNT)
        managedDownstreams.values.forEach { it.updateConnectionRetryCount(connectionRetryCount) }
    }

    override fun updateIgnoreHttpsCertificateErrors(enabled: Boolean) {
        ignoreHttpsCertificateErrors = enabled
        managedDownstreams.values.forEach { it.updateIgnoreHttpsCertificateErrors(enabled) }
    }

    override fun updateFallbackPromptsAndResourcesToTools(enabled: Boolean) {
        fallbackPromptsAndResourcesToTools = enabled
        runtimeLifecycle.updateFallbackPromptsAndResourcesToTools(enabled)
    }

    override fun updateAdapterMode(enabled: Boolean) {
        adapterMode = enabled
        runtimeLifecycle.updateAdapterMode(enabled)
    }

    override fun refreshServerCapabilities(serverId: String): Result<Unit> =
        runCatching {
            val proxy = runtimeLifecycle.requireProxy()
            runBlocking {
                proxy.refreshServerCapabilities(serverId)
            }
        }

    override fun refreshFilteredCapabilities(): Result<Unit> =
        runCatching {
            val proxy = runtimeLifecycle.requireProxy()
            runBlocking {
                proxy.refreshFilteredCapabilities()
            }
        }

    override fun currentProxy(): ProxyMcpServer? = runtimeLifecycle.currentProxy()

    private fun emitCapabilities(capabilitiesById: Map<String, ServerCapabilities>) {
        if (capabilitiesById.isEmpty()) return
        _capabilityUpdates.tryEmit(capabilitiesById)
    }

    @Suppress("LongParameterList", "TooGenericExceptionCaught")
    private suspend fun startInternal(
        servers: List<McpServerConfig>,
        preset: Preset,
        inbound: TransportConfig,
        callTimeoutSeconds: Int,
        capabilitiesTimeoutSeconds: Int,
        authorizationTimeoutSeconds: Int,
        connectionRetryCount: Int,
        ignoreHttpsCertificateErrors: Boolean,
        capabilitiesRefreshIntervalSeconds: Int,
        fallbackPromptsAndResourcesToTools: Boolean,
        adapterMode: Boolean,
    ) {
        runCatching { stopInternal() }
        callTimeoutMillis = callTimeoutSeconds.toLong() * MILLIS_PER_SECOND
        capabilitiesTimeoutMillis = capabilitiesTimeoutSeconds.toLong() * MILLIS_PER_SECOND
        authorizationTimeoutMillis = authorizationTimeoutSeconds.toLong() * MILLIS_PER_SECOND
        this.connectionRetryCount = connectionRetryCount.coerceAtLeast(MIN_RETRY_COUNT)
        this.ignoreHttpsCertificateErrors = ignoreHttpsCertificateErrors
        refreshScheduler.updateIntervalSeconds(capabilitiesRefreshIntervalSeconds)
        this.fallbackPromptsAndResourcesToTools = fallbackPromptsAndResourcesToTools
        this.adapterMode = adapterMode

        val timeouts =
            TimeoutConfig(
                callTimeoutMillis = callTimeoutMillis,
                capabilitiesTimeoutMillis = capabilitiesTimeoutMillis,
                authorizationTimeoutMillis = authorizationTimeoutMillis,
            )
        val snapshot =
            downstreamManager.buildInitial(
                servers = servers,
                timeouts = timeouts,
                connectionRetryCount = this.connectionRetryCount,
                ignoreHttpsCertificateErrors = this.ignoreHttpsCertificateErrors,
            )
        val downstreams = snapshot.downstreams
        val awaitInitialCapabilities = inbound is TransportConfig.StdioTransport
        refreshScheduler.resetLimiter(downstreams.size)

        managedDownstreams = snapshot.managed
        this.downstreams = downstreams
        try {
            val proxy =
                runtimeLifecycle.start(
                    downstreams = downstreams,
                    preset = preset,
                    inbound = inbound,
                    presetResolver = presetResolver,
                    requestTimeoutMillis = callTimeoutMillis,
                    awaitInitialCapabilities = awaitInitialCapabilities,
                    fallbackPromptsAndResourcesToTools = fallbackPromptsAndResourcesToTools,
                    adapterMode = adapterMode,
                )
            if (!awaitInitialCapabilities) {
                refreshScheduler.startInitial(proxy, downstreams)
            }
            refreshScheduler.startPeriodic(proxy, downstreams)
        } catch (error: Exception) {
            runtimeLifecycle.stop()
            managedDownstreams = emptyMap()
            this.downstreams = emptyList()
            shutdownDownstreams(snapshot.managed.values)
            throw error
        }
    }

    private suspend fun stopInternal() {
        refreshScheduler.stop()
        runtimeLifecycle.stop()
        val managed = managedDownstreams.values
        downstreams = emptyList()
        managedDownstreams = emptyMap()
        shutdownDownstreams(managed)
    }

    @Suppress("LongParameterList")
    private suspend fun updateServersInternal(
        servers: List<McpServerConfig>,
        callTimeoutSeconds: Int,
        capabilitiesTimeoutSeconds: Int,
        authorizationTimeoutSeconds: Int,
        connectionRetryCount: Int,
        ignoreHttpsCertificateErrors: Boolean,
        capabilitiesRefreshIntervalSeconds: Int,
        fallbackPromptsAndResourcesToTools: Boolean,
        adapterMode: Boolean,
    ) {
        val proxy = runtimeLifecycle.requireProxy()
        refreshScheduler.stop()

        val previousTimeouts =
            TimeoutConfig(
                callTimeoutMillis = callTimeoutMillis,
                capabilitiesTimeoutMillis = capabilitiesTimeoutMillis,
                authorizationTimeoutMillis = authorizationTimeoutMillis,
            )
        val previousConnectionRetryCount = this.connectionRetryCount
        val previousIgnoreHttpsCertificateErrors = this.ignoreHttpsCertificateErrors
        val previousFallbackPromptsAndResourcesToTools = this.fallbackPromptsAndResourcesToTools
        val previousAdapterMode = this.adapterMode
        callTimeoutMillis = callTimeoutSeconds.toLong() * MILLIS_PER_SECOND
        capabilitiesTimeoutMillis = capabilitiesTimeoutSeconds.toLong() * MILLIS_PER_SECOND
        authorizationTimeoutMillis = authorizationTimeoutSeconds.toLong() * MILLIS_PER_SECOND
        this.connectionRetryCount = connectionRetryCount.coerceAtLeast(MIN_RETRY_COUNT)
        this.ignoreHttpsCertificateErrors = ignoreHttpsCertificateErrors
        refreshScheduler.updateIntervalSeconds(capabilitiesRefreshIntervalSeconds)
        this.fallbackPromptsAndResourcesToTools = fallbackPromptsAndResourcesToTools
        this.adapterMode = adapterMode
        runtimeLifecycle.updateInboundTimeout(callTimeoutMillis)

        val nextTimeouts =
            TimeoutConfig(
                callTimeoutMillis = callTimeoutMillis,
                capabilitiesTimeoutMillis = capabilitiesTimeoutMillis,
                authorizationTimeoutMillis = authorizationTimeoutMillis,
            )
        val update =
            downstreamManager.update(
                servers = servers,
                current = managedDownstreams,
                previousTimeouts = previousTimeouts,
                nextTimeouts = nextTimeouts,
                previousRetryCount = previousConnectionRetryCount,
                nextRetryCount = this.connectionRetryCount,
                previousIgnoreHttpsCertificateErrors = previousIgnoreHttpsCertificateErrors,
                nextIgnoreHttpsCertificateErrors = this.ignoreHttpsCertificateErrors,
            )

        managedDownstreams = update.snapshot.managed
        downstreams = update.snapshot.downstreams
        refreshScheduler.resetLimiter(downstreams.size)

        if (previousFallbackPromptsAndResourcesToTools != fallbackPromptsAndResourcesToTools) {
            runtimeLifecycle.updateFallbackPromptsAndResourcesToTools(fallbackPromptsAndResourcesToTools)
        }
        if (previousAdapterMode != adapterMode) {
            runtimeLifecycle.updateAdapterMode(adapterMode)
        }

        runtimeLifecycle.updateDownstreams(downstreams)
        val removedIds = update.toDisconnect.map { it.serverId }.toSet()
        removedIds.forEach { proxy.removeServerCapabilities(it) }
        if (update.changedIds.isNotEmpty()) {
            refreshScheduler.refreshServers(proxy, update.changedIds, "Updated")
        }
        runtimeLifecycle.refreshInboundCapabilities()?.getOrThrow()
        shutdownDownstreams(update.toDisconnect.distinctBy { it.serverId })
        refreshScheduler.startPeriodic(proxy, downstreams)
    }

    private suspend fun shutdownDownstreams(managed: Collection<ManagedDownstream>) {
        if (managed.isEmpty()) return
        coroutineScope { managed.map { async { it.shutdown() } }.awaitAll() }
    }
}

actual fun createProxyController(
    logger: CollectingLogger,
    configDir: String?,
): ProxyController = JvmProxyController(logger, configDir)

actual fun createStdioProxyController(
    logger: CollectingLogger,
    configDir: String?,
): ProxyController = JvmProxyController(logger = logger, configDir = configDir)
