package io.qent.broxy.ui.adapter.store

import io.qent.broxy.core.capabilities.CapabilityCache
import io.qent.broxy.core.capabilities.CapabilityFetcher
import io.qent.broxy.core.capabilities.CapabilityRefresher
import io.qent.broxy.core.capabilities.ServerStatusTracker
import io.qent.broxy.core.config.ConfigurationManager
import io.qent.broxy.core.proxy.runtime.ProxyController
import io.qent.broxy.core.proxy.runtime.ProxyLifecycle
import io.qent.broxy.core.proxy.runtime.createProxyController
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.ui.adapter.data.provideConfigurationRepository
import io.qent.broxy.ui.adapter.data.provideDefaultLogger
import io.qent.broxy.ui.adapter.models.UiHttpDraft
import io.qent.broxy.ui.adapter.models.UiHttpTransport
import io.qent.broxy.ui.adapter.models.UiMcpServerConfig
import io.qent.broxy.ui.adapter.models.UiMcpServersConfig
import io.qent.broxy.ui.adapter.models.UiPresetDraft
import io.qent.broxy.ui.adapter.models.UiPromptRef
import io.qent.broxy.ui.adapter.models.UiResourceRef
import io.qent.broxy.ui.adapter.models.UiServerCapsSnapshot
import io.qent.broxy.ui.adapter.models.UiServerDraft
import io.qent.broxy.ui.adapter.models.UiStdioDraft
import io.qent.broxy.ui.adapter.models.UiStdioTransport
import io.qent.broxy.ui.adapter.models.UiStreamableHttpDraft
import io.qent.broxy.ui.adapter.models.UiStreamableHttpTransport
import io.qent.broxy.ui.adapter.models.UiToolRef
import io.qent.broxy.ui.adapter.models.UiWebSocketDraft
import io.qent.broxy.ui.adapter.models.UiWebSocketTransport
import io.qent.broxy.ui.adapter.models.toUiModel
import io.qent.broxy.ui.adapter.remote.RemoteConnector
import io.qent.broxy.ui.adapter.remote.createRemoteConnector
import io.qent.broxy.ui.adapter.services.fetchServerCapabilities
import io.qent.broxy.ui.adapter.store.internal.AppStoreIntents
import io.qent.broxy.ui.adapter.store.internal.ProxyRuntime
import io.qent.broxy.ui.adapter.store.internal.StoreSnapshot
import io.qent.broxy.ui.adapter.store.internal.StoreStateAccess
import io.qent.broxy.ui.adapter.store.internal.toUiState
import io.qent.broxy.ui.adapter.store.internal.withPresets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * AppStore implements UDF for the app: exposes Flow<UIState> and side-effecting intents.
 * No Compose dependencies. UI calls intents via functions inside the state.
 */
class AppStore(
    private val configurationRepository: ConfigurationRepository,
    private val proxyLifecycle: ProxyLifecycle,
    private val capabilityFetcher: CapabilityFetcher,
    private val logger: CollectingLogger,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val now: () -> Long = { System.currentTimeMillis() },
    private val enableBackgroundRefresh: Boolean = true,
    private val remoteConnector: RemoteConnector,
) {
    private val capabilityCache = CapabilityCache(now)
    private val statusTracker = ServerStatusTracker()

    private val _state = MutableStateFlow<UIState>(UIState.Loading)
    val state: StateFlow<UIState> = _state

    private var snapshot = StoreSnapshot()

    private val stateAccess =
        StoreStateAccess(
            snapshotProvider = { snapshot },
            snapshotUpdater = { updateSnapshot(it) },
            snapshotConfigProvider = { snapshotConfig() },
            errorHandler = { setErrorState(it) },
        )
    private val configurationManager = ConfigurationManager(configurationRepository, logger)

    private val capabilityRefresher =
        CapabilityRefresher(
            scope = scope,
            capabilityFetcher = capabilityFetcher,
            capabilityCache = capabilityCache,
            statusTracker = statusTracker,
            logger = logger,
            serversProvider = { snapshot.servers },
            capabilitiesTimeoutProvider = { snapshot.capabilitiesTimeoutSeconds },
            publishUpdate = ::publishReady,
            refreshIntervalMillis = ::refreshIntervalMillis,
        )
    private val proxyRuntime =
        ProxyRuntime(
            configurationRepository = configurationRepository,
            proxyLifecycle = proxyLifecycle,
            logger = logger,
            state = stateAccess,
            publishReady = ::publishReady,
            remoteConnector = remoteConnector,
        )
    private val intents: Intents =
        AppStoreIntents(
            scope = scope,
            logger = logger,
            configurationManager = configurationManager,
            state = stateAccess,
            capabilityRefresher = capabilityRefresher,
            proxyRuntime = proxyRuntime,
            proxyLifecycle = proxyLifecycle,
            loadConfiguration = { loadConfigurationSnapshot() },
            refreshEnabledCaps = { force -> capabilityRefresher.refreshEnabledServers(force) },
            restartRefreshJob = { capabilityRefresher.restartBackgroundJob(enableBackgroundRefresh) },
            publishReady = ::publishReady,
            remoteConnector = remoteConnector,
        )

    init {
        observeRemote()
    }

    fun start() {
        scope.launch {
            val loadResult = loadConfigurationSnapshot()
            if (loadResult.isFailure) {
                val msg = loadResult.exceptionOrNull()?.message ?: "Failed to load configuration"
                logger.info("[AppStore] load failed: $msg")
                setErrorState(msg)
                return@launch
            }
            capabilityRefresher.syncWithServers(snapshot.servers)
            publishReady()
            proxyRuntime.ensureInboundRunning(forceRestart = true)
            capabilityRefresher.refreshEnabledServers(force = true)
            capabilityRefresher.restartBackgroundJob(enableBackgroundRefresh)
            remoteConnector.start()
        }
    }

    fun stop() {
        runCatching { proxyRuntime.stopInbound() }
        runCatching { remoteConnector.disconnect() }
    }

    fun getServerDraft(id: String): UiServerDraft? {
        val cfg = snapshot.servers.firstOrNull { it.id == id } ?: return null
        val draftTransport =
            when (val transport = cfg.transport) {
                is UiStdioTransport -> UiStdioDraft(command = transport.command, args = transport.args)
                is UiHttpTransport -> UiHttpDraft(url = transport.url, headers = transport.headers)
                is UiStreamableHttpTransport -> UiStreamableHttpDraft(url = transport.url, headers = transport.headers)
                is UiWebSocketTransport -> UiWebSocketDraft(url = transport.url)
            }
        return UiServerDraft(
            id = cfg.id,
            name = cfg.name,
            enabled = cfg.enabled,
            transport = draftTransport,
            env = cfg.env,
            originalId = cfg.id,
        )
    }

    fun getPresetDraft(id: String): UiPresetDraft? {
        return runCatching { configurationRepository.loadPreset(id) }
            .map { preset ->
                UiPresetDraft(
                    id = preset.id,
                    name = preset.name,
                    tools =
                        preset.tools.map { tool ->
                            UiToolRef(serverId = tool.serverId, toolName = tool.toolName, enabled = tool.enabled)
                        },
                    prompts =
                        preset.prompts.orEmpty().map { prompt ->
                            UiPromptRef(
                                serverId = prompt.serverId,
                                promptName = prompt.promptName,
                                enabled = prompt.enabled,
                            )
                        },
                    resources =
                        preset.resources.orEmpty().map { resource ->
                            UiResourceRef(
                                serverId = resource.serverId,
                                resourceKey = resource.resourceKey,
                                enabled = resource.enabled,
                            )
                        },
                    promptsConfigured = preset.prompts != null,
                    resourcesConfigured = preset.resources != null,
                    originalId = preset.id,
                )
            }
            .onFailure { error ->
                logger.info("[AppStore] getPresetDraft('$id') failed: ${error.message}")
            }
            .getOrNull()
    }

    fun listServerConfigs(): List<UiMcpServerConfig> = snapshot.servers.toList()

    suspend fun listEnabledServerCaps(): List<UiServerCapsSnapshot> = capabilityRefresher.listEnabledServerCaps().map { it.toUiModel() }

    suspend fun getServerCaps(
        serverId: String,
        forceRefresh: Boolean = false,
    ): UiServerCapsSnapshot? {
        return capabilityRefresher.getServerCaps(serverId, forceRefresh)?.toUiModel()
    }

    private fun observeRemote() {
        scope.launch {
            remoteConnector.state.collect { state ->
                updateSnapshot { copy(remote = state) }
                publishReadyIfNotError()
            }
        }
    }

    private fun updateSnapshot(transform: StoreSnapshot.() -> StoreSnapshot) {
        snapshot = snapshot.transform()
    }

    private suspend fun loadConfigurationSnapshot(): Result<Unit> =
        try {
            val config = configurationRepository.loadMcpConfig()
            val loadedPresets = configurationRepository.listPresets().map { it.toUiPresetSummary() }
            proxyLifecycle.updateCallTimeout(config.requestTimeoutSeconds)
            proxyLifecycle.updateCapabilitiesTimeout(config.capabilitiesTimeoutSeconds)
            updateSnapshot {
                copy(
                    isLoading = false,
                    servers = config.servers,
                    selectedPresetId = config.defaultPresetId,
                    inboundSsePort = config.inboundSsePort,
                    requestTimeoutSeconds = config.requestTimeoutSeconds,
                    capabilitiesTimeoutSeconds = config.capabilitiesTimeoutSeconds,
                    capabilitiesRefreshIntervalSeconds = config.capabilitiesRefreshIntervalSeconds.coerceAtLeast(30),
                    showTrayIcon = config.showTrayIcon,
                ).withPresets(loadedPresets)
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }

    private fun snapshotConfig(): UiMcpServersConfig =
        UiMcpServersConfig(
            servers = snapshot.servers,
            defaultPresetId = snapshot.selectedPresetId,
            inboundSsePort = snapshot.inboundSsePort,
            requestTimeoutSeconds = snapshot.requestTimeoutSeconds,
            capabilitiesTimeoutSeconds = snapshot.capabilitiesTimeoutSeconds,
            showTrayIcon = snapshot.showTrayIcon,
            capabilitiesRefreshIntervalSeconds = snapshot.capabilitiesRefreshIntervalSeconds,
        )

    private fun publishReady() {
        _state.value = snapshot.toUiState(intents, capabilityCache, statusTracker)
    }

    private fun publishReadyIfNotError() {
        if (_state.value !is UIState.Error) {
            publishReady()
        }
    }

    private fun setErrorState(message: String) {
        _state.value = UIState.Error(message)
    }

    private fun refreshIntervalMillis(): Long = snapshot.capabilitiesRefreshIntervalSeconds.coerceAtLeast(30) * 1_000L
}

fun createAppStore(
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    logger: CollectingLogger = provideDefaultLogger(),
    repository: ConfigurationRepository = provideConfigurationRepository(),
    proxyFactory: (CollectingLogger) -> ProxyController = { createProxyController(it) },
    capabilityFetcher: CapabilityFetcher = { config, timeout -> fetchServerCapabilities(config, timeout, logger) },
    now: () -> Long = { System.currentTimeMillis() },
    enableBackgroundRefresh: Boolean = true,
): AppStore {
    val proxyController = proxyFactory(logger)
    val proxyLifecycle = ProxyLifecycle(proxyController, logger)
    val remoteConnector =
        createRemoteConnector(
            logger = logger,
            proxyLifecycle = proxyLifecycle,
            scope = scope,
        )
    return AppStore(
        configurationRepository = repository,
        proxyLifecycle = proxyLifecycle,
        capabilityFetcher = capabilityFetcher,
        logger = logger,
        scope = scope,
        now = now,
        enableBackgroundRefresh = enableBackgroundRefresh,
        remoteConnector = remoteConnector,
    )
}
