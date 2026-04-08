package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.core.config.ConfigurationManager
import io.qent.broxy.core.proxy.runtime.ProxyRuntimeFacade
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.ui.adapter.agents.AgentGateway
import io.qent.broxy.ui.adapter.capabilities.CapabilityRefresher
import io.qent.broxy.ui.adapter.clients.AiClientConnector
import io.qent.broxy.ui.adapter.data.ImportedServerHideRepository
import io.qent.broxy.ui.adapter.data.ImportedServerInstallRepository
import io.qent.broxy.ui.adapter.data.UiSettingsRepository
import io.qent.broxy.ui.adapter.icons.ServerIconRepository
import io.qent.broxy.ui.adapter.models.DEFAULT_UI_AGENT_WORKSPACE_PATH
import io.qent.broxy.ui.adapter.models.UiAgentCodexConfig
import io.qent.broxy.ui.adapter.models.UiAgentDraft
import io.qent.broxy.ui.adapter.models.UiAgentFileSystemAccess
import io.qent.broxy.ui.adapter.models.UiAgentFileSystemSettings
import io.qent.broxy.ui.adapter.models.UiAgentLlmConfig
import io.qent.broxy.ui.adapter.models.UiAgentOperation
import io.qent.broxy.ui.adapter.models.UiAgentProviderSettings
import io.qent.broxy.ui.adapter.models.UiAgentRuntime
import io.qent.broxy.ui.adapter.models.UiAiClient
import io.qent.broxy.ui.adapter.models.UiLlmProvider
import io.qent.broxy.ui.adapter.models.UiPreset
import io.qent.broxy.ui.adapter.models.UiPresetDraft
import io.qent.broxy.ui.adapter.models.UiServer
import io.qent.broxy.ui.adapter.models.UiServerDraft
import io.qent.broxy.ui.adapter.models.UiSettings
import io.qent.broxy.ui.adapter.remote.RemoteConnector
import io.qent.broxy.ui.adapter.store.Intents
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class AppStoreIntents(
    private val scope: CoroutineScope,
    private val logger: CollectingLogger,
    private val configurationManager: ConfigurationManager,
    private val uiSettingsRepository: UiSettingsRepository,
    private val serverIconRepository: ServerIconRepository,
    private val state: StoreStateAccess,
    private val capabilityRefresher: CapabilityRefresher,
    private val proxyRuntime: ProxyRuntime,
    private val proxyRuntimeFacade: ProxyRuntimeFacade,
    private val aiClientConnectors: List<AiClientConnector>,
    private val buildAiClients: suspend (Int) -> List<UiAiClient>,
    private val agentGateway: AgentGateway,
    private val loadConfiguration: suspend () -> Result<Unit>,
    private val loadAgentsState: suspend () -> Result<Unit>,
    private val ioDispatcher: CoroutineDispatcher,
    private val refreshEnabledCaps: suspend (Boolean) -> Unit,
    private val refreshImportedServers: suspend () -> Unit,
    private val loadCatalogSnapshot: suspend () -> Result<Unit>,
    private val refreshCatalogSnapshot: suspend (Boolean) -> Result<Unit>,
    private val syncBackgroundRefresh: () -> Unit,
    private val publishReady: () -> Unit,
    private val remoteConnector: RemoteConnector,
    private val allowAgenticInstallPermission: (Long) -> Unit,
    private val denyAgenticInstallPermission: (Long) -> Unit,
    private val now: () -> Long,
    private val timezoneIdProvider: () -> String,
    private val importedServerHideRepository: ImportedServerHideRepository,
    private val importedServerInstallRepository: ImportedServerInstallRepository,
    private val requestNotificationPermission: (agentId: String) -> Unit,
) : Intents {
    private val context =
        IntentExecutionContext(
            scope = scope,
            logger = logger,
            state = state,
            capabilityRefresher = capabilityRefresher,
            proxyRuntime = proxyRuntime,
            proxyRuntimeFacade = proxyRuntimeFacade,
            aiClientConnectors = aiClientConnectors,
            buildAiClients = buildAiClients,
            importedServerHideRepository = importedServerHideRepository,
            importedServerInstallRepository = importedServerInstallRepository,
            loadConfiguration = loadConfiguration,
            ioDispatcher = ioDispatcher,
            refreshEnabledCaps = refreshEnabledCaps,
            refreshImportedServers = refreshImportedServers,
            loadCatalogSnapshot = loadCatalogSnapshot,
            refreshCatalogSnapshot = refreshCatalogSnapshot,
            syncBackgroundRefresh = syncBackgroundRefresh,
            publishReady = publishReady,
            remoteConnector = remoteConnector,
            uiSettingsRepository = uiSettingsRepository,
            serverIconRepository = serverIconRepository,
        )

    private val configGateway: StoreConfigGateway = ConfigurationManagerStoreConfigGateway(configurationManager)
    private val serverHandler = ServerIntentsHandler(context, configGateway)
    private val presetHandler = PresetIntentsHandler(context, configGateway)
    private val runtimeSettingsHandler = RuntimeSettingsIntentsHandler(context, configGateway)
    private val integrationsHandler = IntegrationsIntentsHandler(context)
    private val catalogImportHandler =
        CatalogImportIntentsHandler(
            context = context,
            upsertServer = { draft -> serverHandler.upsertServer(draft) },
            upsertCatalogServer = { draft -> serverHandler.upsertCatalogServer(draft) },
            removeServer = { serverId -> serverHandler.removeServer(serverId) },
        )

    override fun refresh() = serverHandler.refresh()

    override fun addOrUpdateServerUi(ui: UiServer) = serverHandler.addOrUpdateServerUi(ui)

    override fun addServerBasic(
        id: String,
        name: String,
    ) = serverHandler.addServerBasic(id, name)

    override fun upsertServer(draft: UiServerDraft) = serverHandler.upsertServer(draft)

    override fun upsertCatalogServer(draft: UiServerDraft) = serverHandler.upsertCatalogServer(draft)

    override fun removeServer(id: String) = serverHandler.removeServer(id)

    override fun toggleServer(
        id: String,
        enabled: Boolean,
    ) = serverHandler.toggleServer(id, enabled)

    override fun reorderServers(serverIds: List<String>) = serverHandler.reorderServers(serverIds)

    override fun refreshServerCapabilities(serverId: String) = serverHandler.refreshServerCapabilities(serverId)

    override fun importServerFromClient(
        clientId: String,
        sourceServerId: String,
    ) = catalogImportHandler.importServerFromClient(clientId, sourceServerId)

    override fun saveImportedServerFromClient(
        clientId: String,
        sourceServerId: String,
        draft: UiServerDraft,
    ) = catalogImportHandler.saveImportedServerFromClient(clientId, sourceServerId, draft)

    override fun hideImportedServer(
        clientId: String,
        sourceServerId: String,
    ) = catalogImportHandler.hideImportedServer(clientId, sourceServerId)

    override fun resetHiddenImportedServers() = catalogImportHandler.resetHiddenImportedServers()

    override fun consumePendingImportedServerCreate() = catalogImportHandler.consumePendingImportedServerCreate()

    override fun refreshCatalog() = catalogImportHandler.refreshCatalog()

    override fun installCatalogServer(serverId: String) = catalogImportHandler.installCatalogServer(serverId)

    override fun uninstallCatalogServer(serverId: String) = catalogImportHandler.uninstallCatalogServer(serverId)

    override fun consumePendingCatalogInstall() = catalogImportHandler.consumePendingCatalogInstall()

    override fun consumePendingCatalogInstalledServer() = serverHandler.consumePendingCatalogInstalledServer()

    override fun pickServerIcon(serverId: String) = serverHandler.pickServerIcon(serverId)

    override fun clearServerIcon(serverId: String) = serverHandler.clearServerIcon(serverId)

    override fun addOrUpdatePreset(preset: UiPreset) = presetHandler.addOrUpdatePreset(preset)

    override fun upsertPreset(draft: UiPresetDraft) = presetHandler.upsertPreset(draft)

    override fun removePreset(id: String) = presetHandler.removePreset(id)

    override fun reorderPresets(presetIds: List<String>) = presetHandler.reorderPresets(presetIds)

    override fun upsertAgent(draft: UiAgentDraft) {
        scope.launch {
            val previousAgents = state.snapshot.agents
            val result =
                withContext(ioDispatcher) {
                    agentGateway.upsertAgent(draft)
                }
            if (result.isFailure) {
                val msg = logFailure(logger, "upsertAgent(id=${draft.id})", result.exceptionOrNull(), "Failed to save agent")
                state.updateSnapshot { copy(agents = previousAgents) }
                state.setError(msg)
                publishReady()
                return@launch
            }
            loadAgentsState().onFailure { error ->
                val msg = logFailure(logger, "upsertAgent/loadAgents(id=${draft.id})", error, "Failed to load agents")
                state.setError(msg)
            }
            publishReady()
        }
    }

    override fun removeAgent(id: String) {
        scope.launch {
            val previousAgents = state.snapshot.agents
            state.updateSnapshot { copy(agents = agents.filterNot { it.id == id }) }
            publishReady()
            val result =
                withContext(ioDispatcher) {
                    agentGateway.deleteAgent(id)
                }
            if (result.isFailure) {
                val msg = logFailure(logger, "removeAgent(id=$id)", result.exceptionOrNull(), "Failed to delete agent")
                state.updateSnapshot { copy(agents = previousAgents) }
                state.setError(msg)
            } else {
                loadAgentsState().onFailure { error ->
                    logFailure(logger, "removeAgent/loadAgents(id=$id)", error, "Failed to load agents")
                }
            }
            publishReady()
        }
    }

    override fun reorderAgents(agentIds: List<String>) {
        scope.launch {
            val previousAgents = state.snapshot.agents
            val reordered =
                reorderByIds(previousAgents, agentIds) { it.id }
                    ?: run {
                        logFailure(
                            logger,
                            "reorderAgents",
                            IllegalArgumentException("Invalid agent reorder request"),
                            "Failed to reorder agents",
                        )
                        return@launch
                    }
            if (reordered == previousAgents) return@launch
            state.updateSnapshot { copy(agents = reordered) }
            publishReady()
            val result =
                withContext(ioDispatcher) {
                    agentGateway.reorderAgents(agentIds)
                }
            if (result.isFailure) {
                val msg = logFailure(logger, "reorderAgents", result.exceptionOrNull(), "Failed to save agent order")
                state.updateSnapshot { copy(agents = previousAgents) }
                state.setError(msg)
                publishReady()
                return@launch
            }
            loadAgentsState().onFailure { error ->
                logFailure(
                    logger,
                    "reorderAgents/loadAgents",
                    error,
                    "Failed to load agents",
                )
            }
            publishReady()
        }
    }

    override fun runAgent(
        id: String,
        prompt: String,
        llm: UiAgentLlmConfig,
        fileSystem: UiAgentFileSystemSettings,
        cron: String?,
        clearExistingScheduleBeforeRun: Boolean,
        runtime: UiAgentRuntime,
        codex: UiAgentCodexConfig?,
    ) {
        scope.launch {
            val normalizedPrompt = prompt.trim()
            if (normalizedPrompt.isBlank()) {
                state.setError("Prompt cannot be blank")
                publishReady()
                return@launch
            }
            val normalizedModel = llm.model.trim()
            if (runtime == UiAgentRuntime.LANGCHAIN && normalizedModel.isBlank()) {
                state.setError("Model cannot be blank")
                publishReady()
                return@launch
            }
            val normalizedLlm = llm.copy(model = normalizedModel)
            val normalizedCodex =
                codex?.copy(
                    model = codex.model.trim(),
                    reasoningEffort = codex.reasoningEffort,
                )
            val normalizedFileSystem = normalizeFileSystemSettings(fileSystem)
            val normalizedCron = cron?.trim()?.takeIf { it.isNotBlank() }
            val isManualLaunch = normalizedCron == null
            val shouldClearScheduleBeforeManualRun = isManualLaunch && clearExistingScheduleBeforeRun
            val previousAgents = state.snapshot.agents
            val previousOperations = state.snapshot.agentOperations
            if (isManualLaunch) {
                requestNotificationPermission(id)
                val startedAt = now()
                state.updateSnapshot {
                    copy(
                        agentOperations = agentOperations + (id to UiAgentOperation.PreparingRun),
                        agents =
                            agents.map { agent ->
                                if (agent.id != id) return@map agent
                                agent.copy(
                                    isRunning = true,
                                    runningSinceEpochMillis = agent.runningSinceEpochMillis ?: startedAt,
                                    activeOperation = UiAgentOperation.PreparingRun,
                                    schedule = if (shouldClearScheduleBeforeManualRun) null else agent.schedule,
                                )
                            },
                    )
                }
                publishReady()
            }
            val outcome =
                withContext(ioDispatcher) {
                    if (normalizedCron == null) {
                        if (shouldClearScheduleBeforeManualRun) {
                            val clearResult = agentGateway.clearSchedule(id)
                            if (clearResult.isFailure) {
                                RunAgentOutcome(
                                    result = clearResult.map { Unit },
                                    scheduleCleared = false,
                                    clearAttempted = true,
                                )
                            } else {
                                RunAgentOutcome(
                                    result =
                                        agentGateway.runAgentNow(
                                            id = id,
                                            prompt = normalizedPrompt,
                                            runtime = runtime,
                                            llm = normalizedLlm,
                                            codex = normalizedCodex,
                                            fileSystem = normalizedFileSystem,
                                        ),
                                    scheduleCleared = true,
                                    clearAttempted = true,
                                )
                            }
                        } else {
                            RunAgentOutcome(
                                result =
                                    agentGateway.runAgentNow(
                                        id = id,
                                        prompt = normalizedPrompt,
                                        runtime = runtime,
                                        llm = normalizedLlm,
                                        codex = normalizedCodex,
                                        fileSystem = normalizedFileSystem,
                                    ),
                                scheduleCleared = false,
                                clearAttempted = false,
                            )
                        }
                    } else {
                        RunAgentOutcome(
                            result =
                                agentGateway
                                    .saveSchedule(
                                        id = id,
                                        cron = normalizedCron,
                                        prompt = normalizedPrompt,
                                        timezoneId = timezoneIdProvider(),
                                        runtime = runtime,
                                        llm = normalizedLlm,
                                        codex = normalizedCodex,
                                        fileSystem = normalizedFileSystem,
                                    ).map { Unit },
                            scheduleCleared = false,
                            clearAttempted = false,
                        )
                    }
                }
            if (outcome.result.isFailure) {
                val message =
                    if (isManualLaunch && outcome.clearAttempted && !outcome.scheduleCleared) {
                        "Failed to remove schedule"
                    } else if (normalizedCron == null) {
                        "Failed to run agent"
                    } else {
                        "Failed to save schedule"
                    }
                val msg = logFailure(logger, "runAgent(id=$id)", outcome.result.exceptionOrNull(), message)
                if (isManualLaunch) {
                    val rollbackAgents =
                        if (outcome.scheduleCleared) {
                            previousAgents.map { agent ->
                                if (agent.id == id) agent.copy(schedule = null) else agent
                            }
                        } else {
                            previousAgents
                        }
                    state.updateSnapshot {
                        copy(
                            agents = rollbackAgents,
                            agentOperations = previousOperations,
                        )
                    }
                }
                state.setError(msg)
                publishReady()
                return@launch
            }
            if (isManualLaunch) {
                return@launch
            }
            loadAgentsState().onFailure { error ->
                logFailure(logger, "runAgent/loadAgents(id=$id)", error, "Failed to load agents")
            }
            publishReady()
        }
    }

    override fun stopAgent(id: String) {
        scope.launch {
            val result =
                withContext(ioDispatcher) {
                    agentGateway.stopAgent(id)
                }
            if (result.isFailure) {
                val msg = logFailure(logger, "stopAgent(id=$id)", result.exceptionOrNull(), "Failed to stop agent")
                state.setError(msg)
                publishReady()
                return@launch
            }
            loadAgentsState().onFailure { error ->
                logFailure(logger, "stopAgent/loadAgents(id=$id)", error, "Failed to load agents")
            }
            publishReady()
        }
    }

    override fun removeAgentSchedule(id: String) {
        scope.launch {
            val result =
                withContext(ioDispatcher) {
                    agentGateway.clearSchedule(id)
                }
            if (result.isFailure) {
                val msg =
                    logFailure(
                        logger,
                        "removeAgentSchedule(id=$id)",
                        result.exceptionOrNull(),
                        "Failed to remove schedule",
                    )
                state.setError(msg)
                publishReady()
                return@launch
            }
            loadAgentsState().onFailure { error ->
                logFailure(
                    logger,
                    "removeAgentSchedule/loadAgents(id=$id)",
                    error,
                    "Failed to load agents",
                )
            }
            publishReady()
        }
    }

    override fun saveAgentProviderSettings(settings: UiAgentProviderSettings) {
        scope.launch {
            val result =
                withContext(ioDispatcher) {
                    agentGateway.saveProviderSettings(settings)
                }
            if (result.isFailure) {
                val msg =
                    logFailure(
                        logger,
                        "saveAgentProviderSettings",
                        result.exceptionOrNull(),
                        "Failed to save provider settings",
                    )
                state.setError(msg)
            } else {
                val saved = result.getOrThrow()
                state.updateSnapshot { copy(agentProviderSettings = saved) }
            }
            publishReady()
        }
    }

    override fun saveAgentProviderApiKey(
        provider: UiLlmProvider,
        apiKey: String,
    ) {
        scope.launch {
            val result =
                withContext(ioDispatcher) {
                    agentGateway.saveProviderApiKey(provider, apiKey)
                }
            if (result.isFailure) {
                val msg =
                    logFailure(
                        logger,
                        "saveAgentProviderApiKey(provider=$provider)",
                        result.exceptionOrNull(),
                        "Failed to save provider API key",
                    )
                state.setError(msg)
                publishReady()
                return@launch
            }
            state.updateSnapshot {
                copy(
                    agentProviderSettings =
                        when (provider) {
                            UiLlmProvider.OPENAI ->
                                agentProviderSettings.copy(
                                    openAi = agentProviderSettings.openAi.copy(hasSavedApiKey = true),
                                )
                            UiLlmProvider.ANTHROPIC ->
                                agentProviderSettings.copy(
                                    anthropic = agentProviderSettings.anthropic.copy(hasSavedApiKey = true),
                                )
                            UiLlmProvider.LM_STUDIO -> agentProviderSettings
                        },
                )
            }
            publishReady()
        }
    }

    override fun clearAgentProviderApiKey(provider: UiLlmProvider) {
        scope.launch {
            val result =
                withContext(ioDispatcher) {
                    agentGateway.clearProviderApiKey(provider)
                }
            if (result.isFailure) {
                val msg =
                    logFailure(
                        logger,
                        "clearAgentProviderApiKey(provider=$provider)",
                        result.exceptionOrNull(),
                        "Failed to clear provider API key",
                    )
                state.setError(msg)
            } else {
                state.updateSnapshot {
                    copy(
                        agentProviderSettings =
                            when (provider) {
                                UiLlmProvider.OPENAI ->
                                    agentProviderSettings.copy(
                                        openAi = agentProviderSettings.openAi.copy(hasSavedApiKey = false),
                                    )
                                UiLlmProvider.ANTHROPIC ->
                                    agentProviderSettings.copy(
                                        anthropic = agentProviderSettings.anthropic.copy(hasSavedApiKey = false),
                                    )
                                UiLlmProvider.LM_STUDIO -> agentProviderSettings
                            },
                    )
                }
            }
            publishReady()
        }
    }

    override fun selectProxyPreset(presetId: String?) = presetHandler.selectProxyPreset(presetId)

    override fun setPresetManagementAgenticMode(enabled: Boolean) = presetHandler.setPresetManagementAgenticMode(enabled)

    override fun updateInboundHttpPort(port: Int) = runtimeSettingsHandler.updateInboundHttpPort(port)

    override fun updateRequestTimeout(seconds: Int) = runtimeSettingsHandler.updateRequestTimeout(seconds)

    override fun updateCapabilitiesTimeout(seconds: Int) = runtimeSettingsHandler.updateCapabilitiesTimeout(seconds)

    override fun updateMcpFilePath(path: String) = runtimeSettingsHandler.updateMcpFilePath(path)

    override fun updateConnectionRetryCount(count: Int) = runtimeSettingsHandler.updateConnectionRetryCount(count)

    override fun updateIgnoreHttpsCertificateErrors(enabled: Boolean) = runtimeSettingsHandler.updateIgnoreHttpsCertificateErrors(enabled)

    override fun updateCapabilitiesRefreshInterval(seconds: Int) = runtimeSettingsHandler.updateCapabilitiesRefreshInterval(seconds)

    override fun updateTrayIconVisibility(visible: Boolean) = runtimeSettingsHandler.updateTrayIconVisibility(visible)

    override fun updateAgentRunNotificationsEnabled(enabled: Boolean) {
        scope.launch {
            if (state.snapshot.agentRunNotificationsEnabled == enabled) return@launch
            val previous = state.snapshot.agentRunNotificationsEnabled
            val previousShowTrayIcon = state.snapshot.showTrayIcon
            state.updateSnapshot { copy(agentRunNotificationsEnabled = enabled) }
            val result =
                withContext(ioDispatcher) {
                    runCatching {
                        val existing =
                            runCatching { uiSettingsRepository.loadUiSettings() }
                                .onFailure { logger.warn("Failed to load ui.json before save: ${it.message}", it) }
                                .getOrElse {
                                    UiSettings(
                                        showTrayIcon = previousShowTrayIcon,
                                        agentRunNotificationsEnabled = previous,
                                    )
                                }
                        uiSettingsRepository.saveUiSettings(existing.copy(agentRunNotificationsEnabled = enabled))
                    }
                }
            if (result.isFailure) {
                val msg =
                    logFailure(
                        logger,
                        "updateAgentRunNotificationsEnabled",
                        result.exceptionOrNull(),
                        "Failed to update agent run notifications preference",
                    )
                state.updateSnapshot { copy(agentRunNotificationsEnabled = previous) }
                state.setError(msg)
            }
            publishReady()
        }
    }

    override fun updateFallbackPromptsAndResourcesToTools(enabled: Boolean) =
        runtimeSettingsHandler.updateFallbackPromptsAndResourcesToTools(enabled)

    override fun updateAdapterMode(enabled: Boolean) = runtimeSettingsHandler.updateAdapterMode(enabled)

    override fun toggleProxyServer() = runtimeSettingsHandler.toggleProxyServer()

    override fun openLogsFolder() = integrationsHandler.openLogsFolder()

    override fun openExternalUrl(url: String) = integrationsHandler.openExternalUrl(url)

    override fun openRemotePortal() = integrationsHandler.openRemotePortal()

    override fun startRemoteAuthorization() = integrationsHandler.startRemoteAuthorization()

    override fun connectRemote() = integrationsHandler.connectRemote()

    override fun disconnectRemote() = integrationsHandler.disconnectRemote()

    override fun logoutRemote() = integrationsHandler.logoutRemote()

    override fun cancelAuthorization(serverId: String) = serverHandler.cancelAuthorization(serverId)

    override fun openAuthorizationInBrowser(
        serverId: String,
        urlOverride: String?,
    ) = serverHandler.openAuthorizationInBrowser(serverId, urlOverride)

    override fun dismissAuthorizationPopup(serverId: String) = serverHandler.dismissAuthorizationPopup(serverId)

    override fun allowAgenticInstallPermission(requestId: Long) = allowAgenticInstallPermission.invoke(requestId)

    override fun denyAgenticInstallPermission(requestId: Long) = denyAgenticInstallPermission.invoke(requestId)

    override fun connectAiClient(clientId: String) = integrationsHandler.connectAiClient(clientId)

    override fun disconnectAiClient(clientId: String) = integrationsHandler.disconnectAiClient(clientId)

    override fun openAiClientInfo(clientId: String) = integrationsHandler.openAiClientInfo(clientId)

    private fun normalizeFileSystemSettings(fileSystem: UiAgentFileSystemSettings?): UiAgentFileSystemSettings {
        val normalizedPath =
            fileSystem
                ?.path
                ?.trim()
                .orEmpty()
                .ifBlank { DEFAULT_UI_AGENT_WORKSPACE_PATH }
        return UiAgentFileSystemSettings(
            path = normalizedPath,
            access = fileSystem?.access ?: UiAgentFileSystemAccess.NONE,
        )
    }

    private fun <T> reorderByIds(
        items: List<T>,
        orderedIds: List<String>,
        idSelector: (T) -> String,
    ): List<T>? {
        if (items.size != orderedIds.size) return null
        if (orderedIds.toSet().size != orderedIds.size) return null
        val byId = items.associateBy(idSelector)
        if (!orderedIds.all { it in byId }) return null
        return orderedIds.map { byId.getValue(it) }
    }
}

private data class RunAgentOutcome(
    val result: Result<Unit>,
    val scheduleCleared: Boolean,
    val clearAttempted: Boolean,
)
