package io.qent.broxy.ui.adapter.store

import io.qent.broxy.core.config.ConfigurationManager
import io.qent.broxy.core.proxy.runtime.ProxyRuntimeFacade
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.ui.adapter.capabilities.CapabilityCache
import io.qent.broxy.ui.adapter.capabilities.CapabilityCachePersistence
import io.qent.broxy.ui.adapter.capabilities.CapabilityFetcher
import io.qent.broxy.ui.adapter.capabilities.CapabilityRefresher
import io.qent.broxy.ui.adapter.capabilities.ServerStatusTracker
import io.qent.broxy.ui.adapter.clients.AiClientConnectionRequest
import io.qent.broxy.ui.adapter.clients.AiClientConnector
import io.qent.broxy.ui.adapter.clients.AiClientStatus
import io.qent.broxy.ui.adapter.data.UiSettingsRepository
import io.qent.broxy.ui.adapter.icons.ServerIconRepository
import io.qent.broxy.ui.adapter.models.UiAiClient
import io.qent.broxy.ui.adapter.models.UiAiClientNoticeSeverity
import io.qent.broxy.ui.adapter.models.UiAiClientStatusLoadFailedNotice
import io.qent.broxy.ui.adapter.models.UiHttpDraft
import io.qent.broxy.ui.adapter.models.UiHttpTransport
import io.qent.broxy.ui.adapter.models.UiMcpServerConfig
import io.qent.broxy.ui.adapter.models.UiMcpServersConfig
import io.qent.broxy.ui.adapter.models.UiPresetDraft
import io.qent.broxy.ui.adapter.models.UiPromptRef
import io.qent.broxy.ui.adapter.models.UiResourceRef
import io.qent.broxy.ui.adapter.models.UiServerCapsSnapshot
import io.qent.broxy.ui.adapter.models.UiServerDraft
import io.qent.broxy.ui.adapter.models.UiSettings
import io.qent.broxy.ui.adapter.models.UiStdioDraft
import io.qent.broxy.ui.adapter.models.UiStdioTransport
import io.qent.broxy.ui.adapter.models.UiStreamableHttpDraft
import io.qent.broxy.ui.adapter.models.UiStreamableHttpTransport
import io.qent.broxy.ui.adapter.models.UiToolRef
import io.qent.broxy.ui.adapter.models.UiWebSocketDraft
import io.qent.broxy.ui.adapter.models.UiWebSocketTransport
import io.qent.broxy.ui.adapter.models.toCore
import io.qent.broxy.ui.adapter.models.toUi
import io.qent.broxy.ui.adapter.models.toUiModel
import io.qent.broxy.ui.adapter.remote.RemoteConnector
import io.qent.broxy.ui.adapter.store.internal.AppStoreIntents
import io.qent.broxy.ui.adapter.store.internal.AuthorizationPopupCoordinator
import io.qent.broxy.ui.adapter.store.internal.ProxyRuntime
import io.qent.broxy.ui.adapter.store.internal.StoreSnapshot
import io.qent.broxy.ui.adapter.store.internal.StoreStateAccess
import io.qent.broxy.ui.adapter.store.internal.clampRefreshIntervalSeconds
import io.qent.broxy.ui.adapter.store.internal.httpEndpointFor
import io.qent.broxy.ui.adapter.store.internal.logFailure
import io.qent.broxy.ui.adapter.store.internal.registerAuthorizationPresenter
import io.qent.broxy.ui.adapter.store.internal.shouldApplyProxyUpdates
import io.qent.broxy.ui.adapter.store.internal.toUiState
import io.qent.broxy.ui.adapter.store.internal.withPresets
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AppStore implements UDF for the app: exposes Flow<UIState> and side-effecting intents.
 * No Compose dependencies. UI calls intents via functions inside the state.
 */
class AppStore(
    private val configurationRepository: ConfigurationRepository,
    private val uiSettingsRepository: UiSettingsRepository = UiSettingsRepository.Noop,
    private val serverIconRepository: ServerIconRepository = ServerIconRepository.Noop,
    private val proxyRuntime: ProxyRuntimeFacade,
    private val capabilityFetcher: CapabilityFetcher,
    private val logger: CollectingLogger,
    private val aiClientConnectors: List<AiClientConnector>,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val enableBackgroundRefresh: Boolean = true,
    private val remoteConnector: RemoteConnector,
    private val capabilityCachePersistence: CapabilityCachePersistence = CapabilityCachePersistence.Noop,
) {
    private val storeJob = SupervisorJob(scope.coroutineContext[Job])
    private val storeScope = CoroutineScope(scope.coroutineContext + storeJob)

    private val capabilityCache = CapabilityCache(now, capabilityCachePersistence)
    private val statusTracker = ServerStatusTracker()

    private val _state = MutableStateFlow<UIState>(UIState.Loading)
    val state: StateFlow<UIState> = _state

    private val snapshotFlow = MutableStateFlow(StoreSnapshot(remoteEnabled = remoteConnector.isEnabled))
    private val snapshot: StoreSnapshot
        get() = snapshotFlow.value

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
            scope = storeScope,
            capabilityFetcher = capabilityFetcher,
            capabilityCache = capabilityCache,
            statusTracker = statusTracker,
            logger = logger,
            serversProvider = { snapshot.servers.toCore() },
            capabilitiesTimeoutProvider = { snapshot.capabilitiesTimeoutSeconds },
            connectionRetryCountProvider = { snapshot.connectionRetryCount },
            publishUpdate = ::publishReady,
            refreshIntervalMillis = ::refreshIntervalMillis,
        )
    private val proxyCoordinator =
        ProxyRuntime(
            configurationRepository = configurationRepository,
            proxyRuntime = proxyRuntime,
            logger = logger,
            state = stateAccess,
            publishReady = ::publishReady,
            remoteConnector = remoteConnector,
            onProxyStatusChanged = ::syncBackgroundRefresh,
        )
    private val intents: Intents =
        AppStoreIntents(
            scope = storeScope,
            logger = logger,
            configurationManager = configurationManager,
            uiSettingsRepository = uiSettingsRepository,
            serverIconRepository = serverIconRepository,
            state = stateAccess,
            capabilityRefresher = capabilityRefresher,
            proxyRuntime = proxyCoordinator,
            proxyRuntimeFacade = proxyRuntime,
            aiClientConnectors = aiClientConnectors,
            buildAiClients = ::buildAiClients,
            loadConfiguration = { loadConfigurationSnapshot() },
            ioDispatcher = ioDispatcher,
            refreshEnabledCaps = { force -> capabilityRefresher.refreshEnabledServers(force) },
            syncBackgroundRefresh = ::syncBackgroundRefresh,
            publishReady = ::publishReady,
            remoteConnector = remoteConnector,
            now = now,
        )
    private val authorizationCoordinator =
        AuthorizationPopupCoordinator(
            state = stateAccess,
            intents = intents,
            publishReady = ::publishReady,
            logger = logger,
        )

    init {
        registerAuthorizationPresenter(authorizationCoordinator)
        observeRemote()
        observeProxyCapabilities()
        observeProxyStatuses()
    }

    fun start() {
        storeScope.launch {
            val loadResult = loadConfigurationSnapshot()
            if (loadResult.isFailure) {
                val msg = logFailure(logger, "loadConfiguration", loadResult.exceptionOrNull(), "Failed to load configuration")
                setErrorState(msg)
                return@launch
            }
            capabilityRefresher.syncWithServers(snapshot.servers.toCore())
            publishReady()
            proxyCoordinator.ensureInboundRunning(forceRestart = true)
            if (!shouldApplyProxyUpdates(snapshot.proxyStatus, proxyRuntime.isRunning)) {
                capabilityRefresher.refreshEnabledServers(force = false)
            }
            syncBackgroundRefresh()
            if (snapshot.remoteEnabled) {
                remoteConnector.start()
            }
        }
    }

    fun stop() {
        capabilityRefresher.restartBackgroundJob(false)
        registerAuthorizationPresenter(null)
        runCatching { proxyCoordinator.stopInbound() }
        if (snapshot.remoteEnabled) {
            runCatching { remoteConnector.disconnect() }
        }
        storeJob.cancel()
    }

    fun getServerDraft(id: String): UiServerDraft? {
        val cfg = snapshot.servers.firstOrNull { it.id == id } ?: return null
        val draftTransport =
            when (val transport = cfg.transport) {
                is UiStdioTransport -> UiStdioDraft(command = transport.command, args = transport.args)
                is UiHttpTransport -> UiHttpDraft(url = transport.url, headers = transport.headers)
                is UiStreamableHttpTransport -> UiStreamableHttpDraft(url = transport.url, headers = transport.headers)
                is UiWebSocketTransport -> UiWebSocketDraft(url = transport.url, headers = transport.headers)
            }
        return UiServerDraft(
            id = cfg.id,
            name = cfg.name,
            enabled = cfg.enabled,
            transport = draftTransport,
            env = cfg.env,
            originalId = cfg.id,
            iconPath = cfg.iconPath,
        )
    }

    suspend fun pickServerIcon(): Result<String?> =
        withContext(ioDispatcher) {
            serverIconRepository.pickAndImportIcon()
        }

    suspend fun discardServerIcon(iconPath: String): Result<Unit> =
        withContext(ioDispatcher) {
            serverIconRepository.deleteIcon(iconPath)
        }

    fun getPresetDraft(id: String): UiPresetDraft? =
        runCatching { configurationRepository.loadPreset(id) }
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
                    createdAtEpochMillis = preset.createdAtEpochMillis,
                )
            }.onFailure { error ->
                logFailure(logger, "getPresetDraft(id=$id)", error, "Failed to load preset")
            }.getOrNull()

    fun listServerConfigs(): List<UiMcpServerConfig> = snapshot.servers.toList()

    suspend fun listEnabledServerCaps(): List<UiServerCapsSnapshot> = capabilityRefresher.listEnabledServerCaps().map { it.toUiModel() }

    suspend fun getServerCaps(
        serverId: String,
        forceRefresh: Boolean = false,
    ): UiServerCapsSnapshot? = capabilityRefresher.getServerCaps(serverId, forceRefresh)?.toUiModel()

    private fun observeRemote() {
        storeScope.launch {
            remoteConnector.state.collect { state ->
                updateSnapshot { copy(remote = state) }
                publishReadyIfNotError()
            }
        }
    }

    private fun observeProxyCapabilities() {
        storeScope.launch {
            proxyRuntime.capabilityUpdates.collect { capabilitiesById ->
                if (!shouldApplyProxyUpdates(snapshot.proxyStatus, proxyRuntime.isRunning)) return@collect
                capabilityRefresher.applyProxyCapabilities(capabilitiesById)
            }
        }
    }

    private fun observeProxyStatuses() {
        storeScope.launch {
            proxyRuntime.serverStatusUpdates.collect { update ->
                if (!shouldApplyProxyUpdates(snapshot.proxyStatus, proxyRuntime.isRunning)) return@collect
                capabilityRefresher.applyProxyStatus(update)
            }
        }
    }

    private suspend fun buildAiClients(port: Int): List<UiAiClient> {
        return withContext(ioDispatcher) {
            if (aiClientConnectors.isEmpty()) return@withContext emptyList()
            val request = AiClientConnectionRequest(httpEndpoint = httpEndpointFor(port))
            aiClientConnectors.map { connector ->
                val status =
                    connector
                        .loadStatus(request)
                        .onFailure { error ->
                            logFailure(
                                logger,
                                "loadAiClientStatus(id=${connector.descriptor.id})",
                                error,
                                "Failed to load AI client status",
                            )
                        }.getOrElse {
                            AiClientStatus(
                                isConnected = false,
                                canConnect = false,
                                notice =
                                    UiAiClientStatusLoadFailedNotice(
                                        details = it.message,
                                        severity = UiAiClientNoticeSeverity.Error,
                                    ),
                            )
                        }
                UiAiClient(
                    id = connector.descriptor.id,
                    name = connector.descriptor.name,
                    description = connector.descriptor.description,
                    iconId = connector.descriptor.iconId,
                    infoUrl = connector.descriptor.infoUrl,
                    isConnected = status.isConnected,
                    canConnect = status.canConnect,
                    notice = status.notice,
                )
            }
        }
    }

    private fun updateSnapshot(transform: StoreSnapshot.() -> StoreSnapshot) {
        snapshotFlow.update { it.transform() }
    }

    private suspend fun loadConfigurationSnapshot(): Result<Unit> =
        try {
            val (config, loadedPresets, uiSettings) =
                withContext(ioDispatcher) {
                    val loadedConfig = configurationRepository.loadMcpConfig()
                    val presets = configurationRepository.listPresets().map { it.toUi().toUiPresetSummary() }
                    val settings =
                        runCatching { uiSettingsRepository.loadUiSettings() }
                            .onFailure { logger.warn("Failed to load ui.json: ${it.message}", it) }
                            .getOrElse { UiSettings() }
                    Triple(loadedConfig, presets, settings)
                }
            val uiConfig = config.toUi()
            val clients = buildAiClients(uiConfig.inboundHttpPort)
            proxyRuntime.updateCallTimeout(uiConfig.requestTimeoutSeconds)
            proxyRuntime.updateCapabilitiesTimeout(uiConfig.capabilitiesTimeoutSeconds)
            proxyRuntime.updateConnectionRetryCount(uiConfig.connectionRetryCount)
            proxyRuntime.updateFallbackPromptsAndResourcesToTools(uiConfig.fallbackPromptsAndResourcesToTools)
            proxyRuntime.updateAdapterMode(uiConfig.adapterMode)
            updateSnapshot {
                copy(
                    isLoading = false,
                    errorMessage = null,
                    servers = uiConfig.servers,
                    defaultPresetId = uiConfig.defaultPresetId,
                    inboundHttpPort = uiConfig.inboundHttpPort,
                    requestTimeoutSeconds = uiConfig.requestTimeoutSeconds,
                    capabilitiesTimeoutSeconds = uiConfig.capabilitiesTimeoutSeconds,
                    authorizationTimeoutSeconds = uiConfig.authorizationTimeoutSeconds,
                    connectionRetryCount = uiConfig.connectionRetryCount,
                    capabilitiesRefreshIntervalSeconds = clampRefreshIntervalSeconds(uiConfig.capabilitiesRefreshIntervalSeconds),
                    showTrayIcon = uiSettings.showTrayIcon,
                    fallbackPromptsAndResourcesToTools = uiConfig.fallbackPromptsAndResourcesToTools,
                    adapterMode = uiConfig.adapterMode,
                    clients = clients,
                ).withPresets(loadedPresets)
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }

    private fun snapshotConfig(): UiMcpServersConfig =
        UiMcpServersConfig(
            servers = snapshot.servers,
            defaultPresetId = snapshot.defaultPresetId,
            inboundHttpPort = snapshot.inboundHttpPort,
            requestTimeoutSeconds = snapshot.requestTimeoutSeconds,
            capabilitiesTimeoutSeconds = snapshot.capabilitiesTimeoutSeconds,
            authorizationTimeoutSeconds = snapshot.authorizationTimeoutSeconds,
            connectionRetryCount = snapshot.connectionRetryCount,
            capabilitiesRefreshIntervalSeconds = snapshot.capabilitiesRefreshIntervalSeconds,
            fallbackPromptsAndResourcesToTools = snapshot.fallbackPromptsAndResourcesToTools,
            adapterMode = snapshot.adapterMode,
        )

    private fun publishReady() {
        _state.value = snapshot.toUiState(intents, capabilityCache, statusTracker)
    }

    private fun publishReadyIfNotError() {
        if (_state.value !is UIState.Error) {
            publishReady()
        }
    }

    private fun syncBackgroundRefresh() {
        val enabled = !shouldApplyProxyUpdates(snapshot.proxyStatus, proxyRuntime.isRunning) && enableBackgroundRefresh
        capabilityRefresher.restartBackgroundJob(enabled)
    }

    private fun setErrorState(message: String) {
        updateSnapshot { copy(errorMessage = message) }
        _state.value = UIState.Error(message)
    }

    private fun refreshIntervalMillis(): Long = clampRefreshIntervalSeconds(snapshot.capabilitiesRefreshIntervalSeconds) * 1_000L
}
