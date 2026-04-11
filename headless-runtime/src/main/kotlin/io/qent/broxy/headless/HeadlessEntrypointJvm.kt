package io.qent.broxy.headless

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.qent.broxy.core.capabilities.FilePersistedCapabilityCacheStore
import io.qent.broxy.core.config.JsonConfigurationRepository
import io.qent.broxy.core.mcp.DefaultMcpServerConnection
import io.qent.broxy.core.mcp.IsolatedMcpServerConnection
import io.qent.broxy.core.mcp.auth.OAuthState
import io.qent.broxy.core.mcp.auth.OAuthStateStore
import io.qent.broxy.core.mcp.auth.restoreFromLocked
import io.qent.broxy.core.mcp.auth.toSnapshotLocked
import io.qent.broxy.core.models.BuiltInPresetResolver
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.presetmanagement.JvmPresetManagementBackend
import io.qent.broxy.core.proxy.ProxyMcpServer
import io.qent.broxy.core.proxy.inbound.buildSdkServer
import io.qent.broxy.core.proxy.inbound.syncSdkServer
import io.qent.broxy.core.utils.AppCacheDir
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.core.utils.CompositeLogger
import io.qent.broxy.core.utils.DailyFileLogger
import io.qent.broxy.core.utils.StdErrLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.nio.file.Path

/**
 * Starts the proxy in STDIO inbound mode and blocks until the STDIO session ends.
 * This is intended to be called from a packaged Desktop app binary with
 * no CLI args so that Claude Desktop can spawn it as an MCP STDIO server
 * without a separate CLI jar. The preset is resolved from:
 *
 * 1) [presetIdOverride] (if provided),
 * 2) `defaultPresetId` in `mcp.json`,
 * 3) the only available preset (if exactly one exists).
 *
 * @param presetIdOverride Optional preset ID override
 * @param configDir Optional configuration directory (defaults to ~/.config/broxy)
 */
@Suppress("LongMethod")
fun runStdioProxy(
    presetIdOverride: String? = null,
    configDir: String? = null,
): Result<Unit> =
    runCatching {
        val baseDir = resolveConfigDir(configDir)
        val sink = createHeadlessLogger(baseDir)
        val headlessConfig = loadHeadlessConfig(baseDir, sink, presetIdOverride)
        val logger = CollectingLogger(delegate = sink)
        logStartup(sink, headlessConfig.effectivePresetId, configDir)
        val inbound = TransportConfig.StdioTransport(command = "", args = emptyList())

        val timeouts = resolveTimeouts(headlessConfig.config)
        val authStateStore = OAuthStateStore(baseDir = baseDir, logger = sink)
        val rawCache = RawCapabilitiesCache(baseDir = baseDir, logger = sink)
        val cachedCapabilities = rawCache.loadAll().associateBy { it.serverId }
        val warmup = RawCapabilitiesWarmup(cachedCapabilities, System.currentTimeMillis())
        val downstreams =
            buildDownstreams(
                headlessConfig.config,
                logger,
                authStateStore,
                timeouts,
                warmup,
            )

        val syncBridge = SdkSyncBridge(logger)
        val proxy = createProxy(headlessConfig.config, downstreams, logger, syncBridge, rawCache)
        val managementBackend =
            JvmPresetManagementBackend(
                configurationRepository = JsonConfigurationRepository(baseDir = baseDir, logger = sink),
                liveCapabilitiesProvider = { proxy.snapshotDownstreamCapabilities() },
                capabilityCacheStore =
                    FilePersistedCapabilityCacheStore(
                        baseDir = AppCacheDir.resolve(),
                        logger = sink,
                    ),
                logger = sink,
            )
        proxy.presetManagementBackend = managementBackend
        syncBridge.attachProxy(proxy)
        proxy.start(headlessConfig.preset, inbound)

        val server = buildSdkServer(proxy, logger)
        syncBridge.attachServer(server)
        val transport = createStdioTransport()

        val shutdownSignal = CompletableDeferred<Unit>()
        transport.onClose { shutdownSignal.complete(Unit) }

        runBlocking {
            proxy.refreshFilteredCapabilities()
        }
        val refreshLoop =
            startRefreshLoop(
                proxy = proxy,
                downstreams = downstreams,
                refreshIntervalMillis =
                    resolveRefreshIntervalMillis(
                        headlessConfig.config.capabilitiesRefreshIntervalSeconds,
                    ),
                logger = logger,
            )

        try {
            runBlocking {
                server.createSession(transport)
                shutdownSignal.await()
            }
        } finally {
            runBlocking {
                refreshLoop.stop()
                runCatching { transport.close() }
            }
            // Shutdown order: stop refresh loop, close transport, stop proxy, then disconnect downstreams.
            runCatching { proxy.stop() }
            runBlocking { downstreams.forEach { runCatching { it.disconnect() } } }
            downstreams.forEach { runCatching { it.close() } }
            sink.info("Broxy STDIO proxy stopped")
        }
    }

fun logStdioInfo(message: String) {
    StdErrLogger.info(message)
}

private data class HeadlessConfig(
    val config: McpServersConfig,
    val preset: Preset,
    val effectivePresetId: String?,
)

private fun createHeadlessLogger(baseDir: Path): CompositeLogger =
    CompositeLogger(
        StdErrLogger,
        DailyFileLogger(baseDir),
    )

private fun loadHeadlessConfig(
    baseDir: Path,
    sink: CompositeLogger,
    presetIdOverride: String?,
): HeadlessConfig {
    val repo = JsonConfigurationRepository(baseDir = baseDir, logger = sink)
    val config = repo.loadMcpConfig()
    val effectivePresetId =
        presetIdOverride?.takeIf { it.isNotBlank() }
            ?: config.defaultPresetId?.takeIf { it.isNotBlank() }
            ?: repo.listPresets().singleOrNull()?.id
    val preset =
        if (effectivePresetId == null) {
            Preset.empty()
        } else {
            BuiltInPresetResolver.resolve(effectivePresetId)
                ?: runCatching { repo.loadPreset(effectivePresetId) }.getOrElse { Preset.empty() }
        }
    return HeadlessConfig(
        config = config,
        preset = preset,
        effectivePresetId = effectivePresetId,
    )
}

private fun buildDownstreams(
    config: McpServersConfig,
    logger: CollectingLogger,
    authStateStore: OAuthStateStore,
    timeouts: HeadlessTimeouts,
    warmup: RawCapabilitiesWarmup,
): List<IsolatedMcpServerConnection> =
    config.servers
        .filter { it.enabled }
        .map { serverCfg ->
            val resourceUrl = resolveAuthResourceUrl(serverCfg)
            val authState =
                resourceUrl?.let {
                    OAuthState().also { state ->
                        authStateStore.load(serverCfg.id, it)?.let { snapshot ->
                            runBlocking { state.restoreFromLocked(snapshot) }
                        }
                    }
                }
            val connection =
                DefaultMcpServerConnection(
                    config = serverCfg,
                    logger = logger,
                    authState = authState,
                    authStateObserver = { state ->
                        if (resourceUrl != null) {
                            val snapshot = runBlocking { state.toSnapshotLocked(resourceUrl) }
                            authStateStore.save(serverCfg.id, snapshot)
                        }
                    },
                    maxRetries = config.connectionRetryCount,
                    ignoreHttpsCertificateErrors = config.ignoreHttpsCertificateErrors,
                    initialCallTimeoutMillis = timeouts.callTimeoutMillis,
                    initialCapabilitiesTimeoutMillis = timeouts.capabilitiesTimeoutMillis,
                    initialConnectTimeoutMillis = timeouts.connectTimeoutMillis,
                    initialAuthorizationTimeoutMillis = timeouts.authorizationTimeoutMillis,
                )
            warmup.entries[serverCfg.id]?.let { entry ->
                // Treat persisted capabilities as fresh on headless startup to avoid OAuth on client attach.
                val ageMillis = 0L
                runBlocking {
                    connection.warmCapabilitiesCache(entry.capabilities, ageMillis)
                }
            }
            IsolatedMcpServerConnection(connection)
        }

private data class RawCapabilitiesWarmup(
    val entries: Map<String, RawCapabilitiesCacheEntry>,
    val nowMillis: Long,
)

private class SdkSyncBridge(
    private val logger: CollectingLogger,
) {
    private var proxy: ProxyMcpServer? = null
    private var server: Server? = null

    fun attachProxy(proxy: ProxyMcpServer) {
        this.proxy = proxy
    }

    fun attachServer(server: Server) {
        this.server = server
    }

    fun onCapabilitiesUpdated() {
        val activeProxy = proxy ?: return
        val activeServer = server ?: return
        runCatching { syncSdkServer(activeServer, activeProxy, logger) }
    }
}

private fun createProxy(
    config: McpServersConfig,
    downstreams: List<IsolatedMcpServerConnection>,
    logger: CollectingLogger,
    syncBridge: SdkSyncBridge,
    rawCache: RawCapabilitiesCache,
): ProxyMcpServer =
    ProxyMcpServer(
        downstreams = downstreams,
        logger = logger,
        onCapabilitiesUpdated = { snapshot ->
            syncBridge.onCapabilitiesUpdated()
            rawCache.saveSnapshot(snapshot, System.currentTimeMillis())
        },
        fallbackPromptsAndResourcesToToolsEnabled = config.fallbackPromptsAndResourcesToTools,
    )

private fun createStdioTransport(): StdioServerTransport =
    StdioServerTransport(
        System.`in`.asSource().buffered(),
        System.out.asSink().buffered(),
    )

private fun logStartup(
    sink: CompositeLogger,
    presetId: String?,
    configDir: String?,
) {
    val presetLabel = presetId ?: "none"
    val configLabel = configDir ?: "~/.config/broxy"
    sink.info("Starting Broxy STDIO proxy (presetId='$presetLabel', configDir='$configLabel')")
}

private class RefreshLoop(
    private val scope: CoroutineScope,
    private val job: Job?,
) {
    suspend fun stop() {
        job?.cancelAndJoin()
        scope.cancel()
    }
}

private fun startRefreshLoop(
    proxy: ProxyMcpServer,
    downstreams: List<IsolatedMcpServerConnection>,
    refreshIntervalMillis: Long,
    logger: CollectingLogger,
): RefreshLoop {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val serverIds = downstreams.map { it.serverId }
    val refreshParallelism = computeRefreshParallelism(serverIds.size)
    val refreshLimiter = Semaphore(refreshParallelism)
    val job =
        if (refreshIntervalMillis > 0 && serverIds.isNotEmpty()) {
            scope.launch {
                // Headless refresh uses proxy-level capability fetches, but matches UI refresher cadence (min 30s).
                while (isActive) {
                    delay(refreshIntervalMillis)
                    refreshServers(proxy, serverIds, refreshLimiter, logger, "Background")
                }
            }
        } else {
            null
        }
    return RefreshLoop(scope, job)
}

private suspend fun refreshServers(
    proxy: ProxyMcpServer,
    serverIds: List<String>,
    refreshLimiter: Semaphore,
    logger: CollectingLogger,
    label: String,
) {
    if (serverIds.isEmpty()) return
    supervisorScope {
        serverIds
            .map { serverId ->
                launch {
                    val failure =
                        runCatching {
                            refreshLimiter.withPermit {
                                proxy.refreshServerCapabilities(serverId, forceRefresh = false)
                            }
                        }.exceptionOrNull() ?: return@launch
                    if (failure is CancellationException) {
                        throw failure
                    }
                    logger.warn("$label capabilities refresh failed for '$serverId'", failure)
                }
            }.joinAll()
    }
}
