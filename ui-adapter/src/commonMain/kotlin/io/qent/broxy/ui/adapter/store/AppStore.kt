package io.qent.broxy.ui.adapter.store

import io.qent.broxy.core.config.ConfigurationManager
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.presetmanagement.NamedPresetManagementItem
import io.qent.broxy.core.proxy.runtime.ProxyRuntimeFacade
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.ui.adapter.agents.AgentGateway
import io.qent.broxy.ui.adapter.agents.NoopAgentGateway
import io.qent.broxy.ui.adapter.agents.UiAgentExecutionUpdate
import io.qent.broxy.ui.adapter.capabilities.CapabilityCache
import io.qent.broxy.ui.adapter.capabilities.CapabilityCachePersistence
import io.qent.broxy.ui.adapter.capabilities.CapabilityFetcher
import io.qent.broxy.ui.adapter.capabilities.CapabilityRefresher
import io.qent.broxy.ui.adapter.capabilities.ServerCapsSnapshot
import io.qent.broxy.ui.adapter.capabilities.ServerStatusTracker
import io.qent.broxy.ui.adapter.catalog.CatalogInstallPlanner
import io.qent.broxy.ui.adapter.clients.AiClientConnectionRequest
import io.qent.broxy.ui.adapter.clients.AiClientConnector
import io.qent.broxy.ui.adapter.clients.AiClientImportServer
import io.qent.broxy.ui.adapter.clients.AiClientStatus
import io.qent.broxy.ui.adapter.data.CatalogRepository
import io.qent.broxy.ui.adapter.data.ImportedServerHideRepository
import io.qent.broxy.ui.adapter.data.ImportedServerInstallRepository
import io.qent.broxy.ui.adapter.data.SystemPicker
import io.qent.broxy.ui.adapter.data.UiSettingsRepository
import io.qent.broxy.ui.adapter.data.directoryExists
import io.qent.broxy.ui.adapter.icons.ServerIconRepository
import io.qent.broxy.ui.adapter.models.UiAgentAiFeaturesSettings
import io.qent.broxy.ui.adapter.models.UiAgentDraft
import io.qent.broxy.ui.adapter.models.UiAgentGenerationStage
import io.qent.broxy.ui.adapter.models.UiAgentOperation
import io.qent.broxy.ui.adapter.models.UiAgentProviderSettings
import io.qent.broxy.ui.adapter.models.UiAgentRunStatus
import io.qent.broxy.ui.adapter.models.UiAgentToolRef
import io.qent.broxy.ui.adapter.models.UiAiClient
import io.qent.broxy.ui.adapter.models.UiAiClientNoticeSeverity
import io.qent.broxy.ui.adapter.models.UiAiClientStatusLoadFailedNotice
import io.qent.broxy.ui.adapter.models.UiCatalogInstallPermissionRequest
import io.qent.broxy.ui.adapter.models.UiHttpDraft
import io.qent.broxy.ui.adapter.models.UiHttpTransport
import io.qent.broxy.ui.adapter.models.UiLlmProvider
import io.qent.broxy.ui.adapter.models.UiMcpServerConfig
import io.qent.broxy.ui.adapter.models.UiMcpServersConfig
import io.qent.broxy.ui.adapter.models.UiPreset
import io.qent.broxy.ui.adapter.models.UiPresetCore
import io.qent.broxy.ui.adapter.models.UiPresetDraft
import io.qent.broxy.ui.adapter.models.UiPromptRef
import io.qent.broxy.ui.adapter.models.UiResourceRef
import io.qent.broxy.ui.adapter.models.UiRunDetails
import io.qent.broxy.ui.adapter.models.UiRunSummary
import io.qent.broxy.ui.adapter.models.UiSchedulePreview
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
import io.qent.broxy.ui.adapter.models.latestFailedRunsByAgent
import io.qent.broxy.ui.adapter.models.toCore
import io.qent.broxy.ui.adapter.models.toUi
import io.qent.broxy.ui.adapter.models.toUiModel
import io.qent.broxy.ui.adapter.remote.RemoteConnector
import io.qent.broxy.ui.adapter.store.internal.AgenticInstallPermissionCoordinator
import io.qent.broxy.ui.adapter.store.internal.AppStoreIntents
import io.qent.broxy.ui.adapter.store.internal.AuthorizationPopupCoordinator
import io.qent.broxy.ui.adapter.store.internal.ImportedClientGroup
import io.qent.broxy.ui.adapter.store.internal.ImportedServerCandidate
import io.qent.broxy.ui.adapter.store.internal.ProxyRuntime
import io.qent.broxy.ui.adapter.store.internal.StoreSnapshot
import io.qent.broxy.ui.adapter.store.internal.StoreStateAccess
import io.qent.broxy.ui.adapter.store.internal.clampRefreshIntervalSeconds
import io.qent.broxy.ui.adapter.store.internal.httpEndpointFor
import io.qent.broxy.ui.adapter.store.internal.importedServerHideKey
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

private const val CATALOG_STALE_TTL_MILLIS: Long = 24L * 60L * 60L * 1_000L
private const val AGENT_NOTIFICATION_MAX_LENGTH = 280
private const val AGENT_NOTIFICATION_DEFAULT_SUCCESS = "Agent run completed."
private const val AGENT_NOTIFICATION_DEFAULT_FAILURE = "Agent run failed."
private const val AGENT_NOTIFICATION_DEFAULT_SKIPPED = "Agent run skipped."
const val AGENT_GENERATION_ERROR_ALREADY_RUNNING = "agent_generation_error_already_running"
const val AGENT_GENERATION_ERROR_SAVE_FAILED = "agent_generation_error_save_failed"
const val AGENT_GENERATION_ERROR_BLANK_REQUEST = "agent_generation_error_blank_request"
private const val AGENT_GENERATION_DEFAULT_ID = "ai-agent"

data class AgentGenerationState(
    val request: String = "",
    val isRunning: Boolean = false,
    val stage: UiAgentGenerationStage? = null,
    val errorMessage: String? = null,
    val generatedAgentId: String? = null,
)

/**
 * AppStore implements UDF for the app: exposes Flow<UIState> and side-effecting intents.
 * No Compose dependencies. UI calls intents via functions inside the state.
 */
class AppStore(
    private val configurationRepository: ConfigurationRepository,
    private val uiSettingsRepository: UiSettingsRepository = UiSettingsRepository.Noop,
    private val serverIconRepository: ServerIconRepository = ServerIconRepository.Noop,
    private val systemPicker: SystemPicker = SystemPicker.Noop,
    private val proxyRuntime: ProxyRuntimeFacade,
    private val capabilityFetcher: CapabilityFetcher,
    private val logger: CollectingLogger,
    private val aiClientConnectors: List<AiClientConnector>,
    private val agentGateway: AgentGateway = NoopAgentGateway,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val timezoneIdProvider: () -> String = { "UTC" },
    private val enableBackgroundRefresh: Boolean = true,
    private val remoteConnector: RemoteConnector,
    private val importedServerHideRepository: ImportedServerHideRepository = ImportedServerHideRepository.Noop,
    private val importedServerInstallRepository: ImportedServerInstallRepository = ImportedServerInstallRepository.Noop,
    private val catalogRepository: CatalogRepository = CatalogRepository.Noop,
    private val capabilityCachePersistence: CapabilityCachePersistence = CapabilityCachePersistence.Noop,
) {
    private val storeJob = SupervisorJob(scope.coroutineContext[Job])
    private val storeScope = CoroutineScope(scope.coroutineContext + storeJob)

    private val capabilityCache = CapabilityCache(now, capabilityCachePersistence)
    private val statusTracker = ServerStatusTracker()

    private val _state = MutableStateFlow<UIState>(UIState.Loading)
    val state: StateFlow<UIState> = _state

    private val _agentRunNotifications = MutableSharedFlow<AgentRunNotification>(extraBufferCapacity = 64)
    val agentRunNotifications: Flow<AgentRunNotification> = _agentRunNotifications

    private val _agentNotificationPermissionRequests =
        MutableSharedFlow<AgentNotificationPermissionRequest>(extraBufferCapacity = 32)
    val agentNotificationPermissionRequests: Flow<AgentNotificationPermissionRequest> =
        _agentNotificationPermissionRequests
    private val _agentGenerationState = MutableStateFlow(AgentGenerationState())
    val agentGenerationState: StateFlow<AgentGenerationState> = _agentGenerationState
    private val agentGenerationLock = Mutex()

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
    private val agenticInstallPermissionCoordinator =
        AgenticInstallPermissionCoordinator(
            state = stateAccess,
            publishReady = ::publishReady,
            logger = logger,
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
            agentGateway = agentGateway,
            loadConfiguration = { loadConfigurationSnapshot() },
            loadAgentsState = { loadAgentsSnapshot() },
            ioDispatcher = ioDispatcher,
            refreshEnabledCaps = { force -> capabilityRefresher.refreshEnabledServers(force) },
            refreshImportedServers = ::refreshImportedServers,
            loadCatalogSnapshot = ::loadCatalogSnapshot,
            refreshCatalogSnapshot = ::refreshCatalogSnapshot,
            syncBackgroundRefresh = ::syncBackgroundRefresh,
            publishReady = ::publishReady,
            remoteConnector = remoteConnector,
            allowAgenticInstallPermission = { requestId ->
                storeScope.launch {
                    agenticInstallPermissionCoordinator.allow(requestId)
                }
            },
            denyAgenticInstallPermission = { requestId ->
                storeScope.launch {
                    agenticInstallPermissionCoordinator.deny(requestId)
                }
            },
            now = now,
            timezoneIdProvider = timezoneIdProvider,
            importedServerHideRepository = importedServerHideRepository,
            importedServerInstallRepository = importedServerInstallRepository,
            requestNotificationPermission = ::emitAgentNotificationPermissionRequest,
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
        observeAgentUpdates()
    }

    fun start() {
        storeScope.launch {
            val loadResult = loadConfigurationSnapshot()
            if (loadResult.isFailure) {
                val msg = logFailure(logger, "loadConfiguration", loadResult.exceptionOrNull(), "Failed to load configuration")
                setErrorState(msg)
                return@launch
            }
            loadCatalogSnapshot()
            runCatching {
                agentGateway.start()
                loadAgentsSnapshot().getOrThrow()
                loadRunsSnapshot().getOrThrow()
            }.onFailure { error ->
                logFailure(logger, "loadAgents", error, "Failed to load agents")
            }
            capabilityRefresher.syncWithServers(snapshot.servers.toCore())
            publishReady()
            proxyCoordinator.ensureInboundRunning(forceRestart = true)
            if (!shouldApplyProxyUpdates(snapshot.proxyStatus, proxyRuntime.isRunning)) {
                capabilityRefresher.refreshEnabledServers(force = false)
            }
            syncBackgroundRefresh()
            storeScope.launch {
                refreshImportedServers()
            }
            storeScope.launch {
                refreshCatalogSnapshot(force = false)
            }
            if (snapshot.remoteEnabled) {
                remoteConnector.start()
            }
        }
    }

    fun stop() {
        runCatching { agentGateway.stop() }
        capabilityRefresher.restartBackgroundJob(false)
        registerAuthorizationPresenter(null)
        runCatching {
            runBlocking {
                agenticInstallPermissionCoordinator.cancelAll()
            }
        }
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
            auth = cfg.auth,
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

    suspend fun pickAgentWorkspaceDirectory(initialPath: String?): Result<String?> =
        withContext(ioDispatcher) {
            systemPicker.pickDirectory(initialPath)
        }.onFailure { error ->
            val message =
                logFailure(
                    logger = logger,
                    action = "pickAgentWorkspaceDirectory(initialPath=${initialPath.orEmpty()})",
                    failure = error,
                    defaultMessage = "Failed to pick workspace directory",
                )
            setErrorState(message)
        }

    suspend fun agentWorkspaceDirectoryExists(path: String): Boolean =
        withContext(ioDispatcher) {
            directoryExists(path)
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
                    agentTools =
                        preset.agentTools.map { ref ->
                            UiAgentToolRef(
                                agentId = ref.agentId,
                                enabled = ref.enabled,
                            )
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
                    orderIndex = preset.orderIndex,
                )
            }.onFailure { error ->
                logFailure(logger, "getPresetDraft(id=$id)", error, "Failed to load preset")
            }.getOrNull()

    fun getAgentDraft(id: String): UiAgentDraft? {
        val agent = snapshot.agents.firstOrNull { it.id == id } ?: return null
        return UiAgentDraft(
            id = agent.id,
            name = agent.name,
            systemPrompt = agent.systemPrompt,
            description = agent.description,
            tools = agent.tools,
            agentTools = agent.agentTools,
            prompts = agent.prompts,
            resources = agent.resources,
            promptsConfigured = agent.promptsConfigured,
            resourcesConfigured = agent.resourcesConfigured,
            originalId = agent.id,
            orderIndex = agent.orderIndex,
            schedule = agent.schedule,
            manualLaunchDefaults = agent.manualLaunchDefaults,
        )
    }

    fun listServerConfigs(): List<UiMcpServerConfig> = snapshot.servers.toList()

    suspend fun listEnabledServerCaps(): List<UiServerCapsSnapshot> = capabilityRefresher.listEnabledServerCaps().map { it.toUiModel() }

    suspend fun listSelectableServerCaps(): List<UiServerCapsSnapshot> =
        capabilityRefresher
            .listCachedServerCaps(snapshot.servers.map { it.id })
            .map { it.toUiModel() }

    suspend fun getServerCaps(
        serverId: String,
        forceRefresh: Boolean = false,
    ): UiServerCapsSnapshot? = capabilityRefresher.getServerCaps(serverId, forceRefresh)?.toUiModel()

    internal fun currentServersForPresetManagement(): List<McpServerConfig> = snapshot.servers.toCore()

    internal fun currentPresetNamesForPresetManagement(): List<NamedPresetManagementItem> =
        snapshot.presets.map { preset ->
            NamedPresetManagementItem(
                id = preset.id,
                name = preset.name,
            )
        }

    internal suspend fun refreshPresetsForPresetManagement(): Result<Unit> =
        runCatching {
            val loadedPresets =
                withContext(ioDispatcher) {
                    configurationRepository.listPresets().map { it.toUi().toUiPresetSummary() }
                }
            updateSnapshot { withPresets(loadedPresets) }
            publishReadyIfNotError()
        }

    internal fun isPresetManagementAgenticModeEnabled(): Boolean = snapshot.agenticModeEnabled

    internal suspend fun requestAgenticInstallPermission(request: UiCatalogInstallPermissionRequest): Boolean =
        agenticInstallPermissionCoordinator.requestPermission(
            serverId = request.serverId,
            serverName = request.serverName,
            serverDescription = request.serverDescription,
            iconUrl = request.iconUrl,
        )

    internal suspend fun refreshServersForPresetManagement(): Result<Unit> =
        runCatching {
            loadConfigurationSnapshot().getOrThrow()
            capabilityRefresher.syncWithServers(snapshot.servers.toCore())
            publishReadyIfNotError()
        }

    suspend fun listProviderModels(
        provider: UiLlmProvider,
        forceRefresh: Boolean = false,
    ): Result<List<String>> {
        val result =
            withContext(ioDispatcher) {
                agentGateway.listProviderModels(provider, forceRefresh)
            }
        result.onSuccess { models ->
            updateSnapshot {
                copy(agentProviderSettings = agentProviderSettings.withProviderModels(provider, models))
            }
            publishReadyIfNotError()
        }
        return result
    }

    suspend fun listCodexModels(forceRefresh: Boolean = false): Result<List<String>> {
        val result =
            withContext(ioDispatcher) {
                agentGateway.listCodexModels(forceRefresh)
            }
        result.onSuccess { models ->
            updateSnapshot {
                copy(agentProviderSettings = agentProviderSettings.withCodexModels(models, now()))
            }
            publishReadyIfNotError()
        }
        return result
    }

    suspend fun previewAgentSchedule(
        cron: String,
        limit: Int = 3,
    ): Result<UiSchedulePreview> =
        withContext(ioDispatcher) {
            agentGateway.previewSchedule(
                cron = cron.trim(),
                timezoneId = timezoneIdProvider(),
                limit = limit,
            )
        }

    suspend fun loadRunDetails(runId: String): Result<UiRunDetails> =
        withContext(ioDispatcher) {
            runCatching {
                checkNotNull(agentGateway.loadRun(runId)) { "Run '$runId' not found" }
            }
        }

    suspend fun generateAgentDescription(draft: UiAgentDraft): Result<String> =
        withContext(ioDispatcher) {
            val snapshots =
                capabilityRefresher
                    .listCachedServerCaps(snapshot.servers.map { it.id })
                    .map { it.toUiModel() }
            agentGateway.generateAgentDescription(draft, snapshots)
        }

    fun updateAgentGenerationRequest(request: String) {
        _agentGenerationState.update { current ->
            current.copy(
                request = request,
                errorMessage = null,
            )
        }
    }

    fun clearAgentGenerationError() {
        _agentGenerationState.update { current ->
            current.copy(errorMessage = null)
        }
    }

    fun acknowledgeAgentGenerationCompletion() {
        _agentGenerationState.update { current ->
            current.copy(generatedAgentId = null)
        }
    }

    fun startGenerateAgentFromRequest(aiFeaturesOverride: UiAgentAiFeaturesSettings) {
        val rawRequest = _agentGenerationState.value.request
        val normalizedRequest = rawRequest.trim()
        if (normalizedRequest.isBlank()) {
            _agentGenerationState.update { current ->
                current.copy(
                    isRunning = false,
                    stage = null,
                    errorMessage = AGENT_GENERATION_ERROR_BLANK_REQUEST,
                    generatedAgentId = null,
                )
            }
            return
        }
        if (!agentGenerationLock.tryLock()) {
            _agentGenerationState.update { current ->
                current.copy(
                    errorMessage = AGENT_GENERATION_ERROR_ALREADY_RUNNING,
                    generatedAgentId = null,
                )
            }
            return
        }
        _agentGenerationState.update { current ->
            current.copy(
                request = rawRequest,
                isRunning = true,
                stage = UiAgentGenerationStage.SELECTING_SERVERS,
                errorMessage = null,
                generatedAgentId = null,
            )
        }
        storeScope.launch {
            try {
                val generationResult =
                    withContext(ioDispatcher) {
                        val cachedById =
                            capabilityRefresher
                                .listCachedServerCaps(snapshot.servers.map { it.id })
                                .associateBy { it.serverId }
                        val fullSnapshotContext =
                            snapshot.servers.map { server ->
                                cachedById[server.id] ?: ServerCapsSnapshot(serverId = server.id, name = server.name)
                            }
                        agentGateway.generateAgentFromRequest(
                            request = normalizedRequest,
                            capabilitySnapshots = fullSnapshotContext.map { it.toUiModel() },
                            aiFeaturesOverride = aiFeaturesOverride,
                            onProgress = { progress ->
                                _agentGenerationState.update { current ->
                                    current.copy(
                                        isRunning = true,
                                        stage = progress,
                                        errorMessage = null,
                                    )
                                }
                            },
                        )
                    }
                if (generationResult.isFailure) {
                    _agentGenerationState.update { current ->
                        current.copy(
                            isRunning = false,
                            stage = null,
                            errorMessage = generationResult.exceptionOrNull()?.message ?: "Failed to generate agent.",
                            generatedAgentId = null,
                        )
                    }
                    return@launch
                }

                val generated = generationResult.getOrThrow()
                val baseId = slugifyAgentId(generated.name).ifBlank { AGENT_GENERATION_DEFAULT_ID }
                val generatedId = generateUniqueAgentId(baseId, snapshot.agents.mapTo(linkedSetOf()) { it.id })
                val draft =
                    UiAgentDraft(
                        id = generatedId,
                        name = generated.name.trim().ifBlank { AGENT_GENERATION_DEFAULT_ID },
                        systemPrompt = generated.systemPrompt.trim(),
                        description = generated.description?.trim()?.takeIf { it.isNotBlank() },
                        tools = generated.tools,
                        prompts = generated.prompts,
                        resources = generated.resources,
                        promptsConfigured = true,
                        resourcesConfigured = true,
                        originalId = null,
                        orderIndex = snapshot.agents.size,
                    )
                val saveResult =
                    withContext(ioDispatcher) {
                        agentGateway.upsertAgent(draft)
                    }
                if (saveResult.isFailure) {
                    _agentGenerationState.update { current ->
                        current.copy(
                            isRunning = false,
                            stage = null,
                            errorMessage = saveResult.exceptionOrNull()?.message ?: AGENT_GENERATION_ERROR_SAVE_FAILED,
                            generatedAgentId = null,
                        )
                    }
                    return@launch
                }
                val saved = saveResult.getOrThrow()
                updateSnapshot {
                    val merged =
                        agents
                            .filterNot { it.id == saved.id }
                            .plus(
                                saved.copy(
                                    latestFailedRun = latestFailedRunByAgentId[saved.id],
                                    activeOperation = agentOperations[saved.id],
                                ),
                            )
                    copy(agents = merged)
                }
                publishReadyIfNotError()
                _agentGenerationState.update { current ->
                    current.copy(
                        isRunning = false,
                        stage = null,
                        errorMessage = null,
                        generatedAgentId = saved.id,
                    )
                }
            } catch (error: Throwable) {
                _agentGenerationState.update { current ->
                    current.copy(
                        isRunning = false,
                        stage = null,
                        errorMessage = error.message ?: "Failed to generate agent.",
                        generatedAgentId = null,
                    )
                }
            } finally {
                agentGenerationLock.unlock()
            }
        }
    }

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

    private fun observeAgentUpdates() {
        storeScope.launch {
            agentGateway.updates.collect { update ->
                when (update) {
                    is UiAgentExecutionUpdate.Running -> applyAgentRunningUpdate(update.agentId, update.startedAtEpochMillis)
                    is UiAgentExecutionUpdate.Operation -> applyAgentOperationUpdate(update.agentId, update.operation)
                    is UiAgentExecutionUpdate.Finished -> {
                        emitAgentRunNotification(update)
                        clearAgentRuntimeState(update.agentId)
                        applyRunSummaryUpdate(update.run)
                    }
                }
                publishReadyIfNotError()
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

    private suspend fun refreshImportedServers() {
        val installedServerIds =
            snapshot.servers
                .asSequence()
                .map { it.id }
                .toSet()
        val groups =
            withContext(ioDispatcher) {
                if (aiClientConnectors.isEmpty()) {
                    return@withContext emptyList()
                }
                val hiddenKeys =
                    runCatching { importedServerHideRepository.loadHiddenServerKeys() }
                        .onFailure { error ->
                            logFailure(
                                logger,
                                "loadHiddenImportedServers",
                                error,
                                "Failed to load hidden imported server list",
                            )
                        }.getOrElse { emptySet() }
                val installedMappings =
                    runCatching { importedServerInstallRepository.loadInstalledMappings() }
                        .onFailure { error ->
                            logFailure(
                                logger,
                                "loadInstalledImportedServers",
                                error,
                                "Failed to load installed imported server mappings",
                            )
                        }.getOrElse { emptyMap() }
                aiClientConnectors
                    .mapNotNull { connector ->
                        val importedServers =
                            connector
                                .loadImportableServers()
                                .onFailure { error ->
                                    logFailure(
                                        logger,
                                        "loadImportableServers(clientId=${connector.descriptor.id})",
                                        error,
                                        "Failed to load importable servers",
                                    )
                                }.getOrElse { emptyList() }
                        val servers =
                            importedServers
                                .asSequence()
                                .filterNot(::isBroxyImport)
                                .filterNot { server ->
                                    importedServerHideKey(
                                        clientId = connector.descriptor.id,
                                        sourceServerId = server.sourceServerId,
                                    ) in hiddenKeys
                                }.filterNot { server ->
                                    val importKey =
                                        importedServerHideKey(
                                            clientId = connector.descriptor.id,
                                            sourceServerId = server.sourceServerId,
                                        )
                                    val mappedServerId = installedMappings[importKey]
                                    mappedServerId != null && mappedServerId in installedServerIds
                                }.map { server ->
                                    ImportedServerCandidate(
                                        sourceServerId = server.sourceServerId,
                                        config =
                                            UiMcpServerConfig(
                                                id = server.sourceServerId,
                                                name = server.name,
                                                transport = server.transport,
                                                env = server.env,
                                                enabled = server.enabled,
                                            ),
                                    )
                                }.sortedBy { it.config.name.lowercase() }
                                .toList()
                        if (servers.isEmpty()) {
                            null
                        } else {
                            ImportedClientGroup(
                                clientId = connector.descriptor.id,
                                clientName = connector.descriptor.name,
                                clientIconId = connector.descriptor.iconId,
                                servers = servers,
                            )
                        }
                    }.sortedBy { it.clientName.lowercase() }
            }
        updateSnapshot { copy(importedServerGroups = groups) }
        publishReadyIfNotError()
    }

    private suspend fun loadCatalogSnapshot(): Result<Unit> {
        val loadResult =
            withContext(ioDispatcher) {
                catalogRepository.loadCatalog()
            }
        return loadResult
            .map { bundle ->
                val entries = CatalogInstallPlanner.buildServerEntries(bundle.servers)
                updateSnapshot {
                    copy(
                        catalogServerEntries = entries,
                        catalogLoading = false,
                        catalogErrorMessage = null,
                        catalogUpdatedAtEpochMillis = bundle.updatedAtEpochMillis,
                    )
                }
                publishReadyIfNotError()
            }.onFailure { error ->
                val message = logFailure(logger, "loadCatalog", error, "Failed to load catalog")
                updateSnapshot {
                    copy(
                        catalogServerEntries = emptyList(),
                        catalogLoading = false,
                        catalogErrorMessage = message,
                    )
                }
                publishReadyIfNotError()
            }
    }

    private suspend fun refreshCatalogSnapshot(force: Boolean): Result<Unit> {
        if (force) {
            updateSnapshot { copy(catalogLoading = true, catalogErrorMessage = null) }
            publishReadyIfNotError()
        }
        val refreshResult =
            withContext(ioDispatcher) {
                catalogRepository.refreshCatalog()
            }
        return refreshResult
            .map { updatedBundle ->
                if (updatedBundle != null) {
                    val entries = CatalogInstallPlanner.buildServerEntries(updatedBundle.servers)
                    updateSnapshot {
                        copy(
                            catalogServerEntries = entries,
                            catalogLoading = false,
                            catalogErrorMessage = null,
                            catalogUpdatedAtEpochMillis = updatedBundle.updatedAtEpochMillis,
                        )
                    }
                    publishReadyIfNotError()
                } else if (force) {
                    updateSnapshot { copy(catalogLoading = false) }
                    publishReadyIfNotError()
                }
            }.onFailure { error ->
                val message = logFailure(logger, "refreshCatalog", error, "Failed to refresh catalog")
                updateSnapshot { copy(catalogLoading = false, catalogErrorMessage = message) }
                publishReadyIfNotError()
            }
    }

    private fun isBroxyImport(server: AiClientImportServer): Boolean =
        server.sourceServerId.equals("broxy", ignoreCase = true) ||
            server.name.equals("broxy", ignoreCase = true)

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
            proxyRuntime.updateIgnoreHttpsCertificateErrors(uiConfig.ignoreHttpsCertificateErrors)
            proxyRuntime.updateFallbackPromptsAndResourcesToTools(uiConfig.fallbackPromptsAndResourcesToTools)
            proxyRuntime.updateAdapterMode(uiConfig.adapterMode)
            val initialDefaultPresetId = resolveInitialDefaultPresetId(uiConfig.defaultPresetId, loadedPresets)
            updateSnapshot {
                copy(
                    isLoading = false,
                    errorMessage = null,
                    servers = uiConfig.servers,
                    mcpFilePath = uiConfig.mcpFilePath,
                    defaultPresetId = initialDefaultPresetId,
                    inboundHttpPort = uiConfig.inboundHttpPort,
                    requestTimeoutSeconds = uiConfig.requestTimeoutSeconds,
                    capabilitiesTimeoutSeconds = uiConfig.capabilitiesTimeoutSeconds,
                    authorizationTimeoutSeconds = uiConfig.authorizationTimeoutSeconds,
                    connectionRetryCount = uiConfig.connectionRetryCount,
                    ignoreHttpsCertificateErrors = uiConfig.ignoreHttpsCertificateErrors,
                    capabilitiesRefreshIntervalSeconds = clampRefreshIntervalSeconds(uiConfig.capabilitiesRefreshIntervalSeconds),
                    showTrayIcon = uiSettings.showTrayIcon,
                    agentRunNotificationsEnabled = uiSettings.agentRunNotificationsEnabled,
                    fallbackPromptsAndResourcesToTools = uiConfig.fallbackPromptsAndResourcesToTools,
                    adapterMode = uiConfig.adapterMode,
                    clients = clients,
                ).withPresets(loadedPresets)
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }

    private suspend fun loadAgentsSnapshot(): Result<Unit> =
        runCatching {
            val (agents, settings) =
                withContext(ioDispatcher) {
                    val loadedAgents = agentGateway.listAgents()
                    val loadedSettings = agentGateway.loadProviderSettings()
                    loadedAgents to loadedSettings
                }
            val operations = snapshot.agentOperations
            val latestFailed = snapshot.latestFailedRunByAgentId
            updateSnapshot {
                copy(
                    agents =
                        agents.map { agent ->
                            agent.copy(
                                activeOperation = operations[agent.id],
                                latestFailedRun = latestFailed[agent.id],
                            )
                        },
                    agentProviderSettings = settings,
                )
            }
        }

    private suspend fun loadRunsSnapshot(): Result<Unit> =
        runCatching {
            val runs =
                withContext(ioDispatcher) {
                    agentGateway.listRuns()
                }.sortedWith(runSummaryComparator())
            val latestFailed = latestFailedRunsByAgent(runs)
            updateSnapshot {
                copy(
                    runs = runs,
                    latestFailedRunByAgentId = latestFailed,
                    agents =
                        agents.map { agent ->
                            agent.copy(latestFailedRun = latestFailed[agent.id])
                        },
                )
            }
        }

    private fun applyRunSummaryUpdate(run: UiRunSummary) {
        updateSnapshot {
            val updatedRuns =
                runs
                    .filterNot { it.runId == run.runId }
                    .plus(run)
                    .sortedWith(runSummaryComparator())
            val latestFailed = latestFailedRunsByAgent(updatedRuns)
            copy(
                runs = updatedRuns,
                latestFailedRunByAgentId = latestFailed,
                agents =
                    agents.map { agent ->
                        agent.copy(latestFailedRun = latestFailed[agent.id])
                    },
            )
        }
    }

    private fun applyAgentRunningUpdate(
        agentId: String,
        startedAtEpochMillis: Long,
    ) {
        updateSnapshot {
            copy(
                agents =
                    agents.map { agent ->
                        if (agent.id != agentId) return@map agent
                        agent.copy(
                            isRunning = true,
                            runningSinceEpochMillis = agent.runningSinceEpochMillis ?: startedAtEpochMillis,
                        )
                    },
            )
        }
    }

    private fun applyAgentOperationUpdate(
        agentId: String,
        operation: UiAgentOperation,
    ) {
        updateSnapshot {
            copy(
                agentOperations = agentOperations + (agentId to operation),
                agents =
                    agents.map { agent ->
                        if (agent.id != agentId) return@map agent
                        agent.copy(activeOperation = operation)
                    },
            )
        }
    }

    private fun clearAgentRuntimeState(agentId: String) {
        updateSnapshot {
            copy(
                agentOperations = agentOperations - agentId,
                agents =
                    agents.map { agent ->
                        if (agent.id != agentId) return@map agent
                        agent.copy(
                            isRunning = false,
                            runningSinceEpochMillis = null,
                            activeOperation = null,
                        )
                    },
            )
        }
    }

    private fun snapshotConfig(): UiMcpServersConfig =
        UiMcpServersConfig(
            servers = snapshot.servers,
            mcpFilePath = snapshot.mcpFilePath,
            defaultPresetId = snapshot.defaultPresetId,
            inboundHttpPort = snapshot.inboundHttpPort,
            requestTimeoutSeconds = snapshot.requestTimeoutSeconds,
            capabilitiesTimeoutSeconds = snapshot.capabilitiesTimeoutSeconds,
            authorizationTimeoutSeconds = snapshot.authorizationTimeoutSeconds,
            connectionRetryCount = snapshot.connectionRetryCount,
            ignoreHttpsCertificateErrors = snapshot.ignoreHttpsCertificateErrors,
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

    private fun resolveInitialDefaultPresetId(
        configuredDefaultPresetId: String?,
        loadedPresets: List<UiPreset>,
    ): String? =
        configuredDefaultPresetId?.takeIf { it.isNotBlank() }
            ?: if (loadedPresets.isEmpty()) {
                UiPresetCore.PRESET_MANAGEMENT_ID
            } else {
                null
            }

    private fun emitAgentRunNotification(update: UiAgentExecutionUpdate.Finished) {
        if (!snapshot.agentRunNotificationsEnabled) {
            return
        }
        val name = snapshot.agents.firstOrNull { it.id == update.agentId }?.name ?: update.agentId
        val event =
            AgentRunNotification(
                agentId = update.agentId,
                agentName = name,
                status = update.run.status,
                message = notificationMessage(update.run),
            )
        _agentRunNotifications.tryEmit(event)
    }

    private fun emitAgentNotificationPermissionRequest(agentId: String) {
        if (!snapshot.agentRunNotificationsEnabled) {
            return
        }
        _agentNotificationPermissionRequests.tryEmit(
            AgentNotificationPermissionRequest(agentId = agentId),
        )
    }

    private fun notificationMessage(record: UiRunSummary): String {
        val raw =
            when (record.status) {
                UiAgentRunStatus.SUCCESS -> record.response.orEmpty()
                UiAgentRunStatus.FAILED -> record.errorMessage.orEmpty()
                UiAgentRunStatus.SKIPPED -> record.errorMessage.orEmpty()
            }.ifBlank {
                when (record.status) {
                    UiAgentRunStatus.SUCCESS -> AGENT_NOTIFICATION_DEFAULT_SUCCESS
                    UiAgentRunStatus.FAILED -> AGENT_NOTIFICATION_DEFAULT_FAILURE
                    UiAgentRunStatus.SKIPPED -> AGENT_NOTIFICATION_DEFAULT_SKIPPED
                }
            }
        return normalizeNotificationText(raw)
    }

    private fun normalizeNotificationText(raw: String): String {
        val collapsed =
            raw
                .lineSequence()
                .joinToString(" ") { it.trim() }
                .replace("\\s+".toRegex(), " ")
                .trim()
        if (collapsed.length <= AGENT_NOTIFICATION_MAX_LENGTH) {
            return collapsed
        }
        return collapsed.take(AGENT_NOTIFICATION_MAX_LENGTH - 3).trimEnd() + "..."
    }
}

private fun UiAgentProviderSettings.withProviderModels(
    provider: UiLlmProvider,
    models: List<String>,
): UiAgentProviderSettings {
    val normalized =
        models
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    val updatedCache =
        when (provider) {
            UiLlmProvider.OPENAI -> modelCache.copy(openAi = normalized)
            UiLlmProvider.ANTHROPIC -> modelCache.copy(anthropic = normalized)
            UiLlmProvider.LM_STUDIO -> modelCache.copy(lmStudio = normalized)
        }
    return copy(modelCache = updatedCache)
}

private fun UiAgentProviderSettings.withCodexModels(
    models: List<String>,
    fetchedAtEpochMillis: Long,
): UiAgentProviderSettings {
    val normalized =
        models
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    return copy(
        modelCache =
            modelCache.copy(
                codex = normalized,
                codexFetchedAtEpochMillis = fetchedAtEpochMillis,
            ),
    )
}

private fun runSummaryComparator(): Comparator<UiRunSummary> =
    compareByDescending<UiRunSummary> { it.startedAtEpochMillis }
        .thenByDescending { it.finishedAtEpochMillis }
        .thenBy { it.runId }

private fun slugifyAgentId(name: String): String {
    val normalized = name.trim().lowercase()
    if (normalized.isBlank()) return ""
    val builder = StringBuilder()
    var lastWasDash = false
    normalized.forEach { ch ->
        when {
            ch.isLetterOrDigit() -> {
                builder.append(ch)
                lastWasDash = false
            }
            !lastWasDash -> {
                builder.append('-')
                lastWasDash = true
            }
        }
    }
    return builder.toString().trim('-')
}

private fun generateUniqueAgentId(
    baseId: String,
    existingIds: Set<String>,
): String {
    if (baseId.isBlank()) return AGENT_GENERATION_DEFAULT_ID
    var candidate = baseId
    var suffix = 2
    while (candidate in existingIds) {
        candidate = "$baseId-$suffix"
        suffix += 1
    }
    return candidate
}
