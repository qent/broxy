package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.core.config.ConfigurationManager
import io.qent.broxy.core.proxy.runtime.ProxyRuntimeFacade
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.ui.adapter.capabilities.CapabilityRefresher
import io.qent.broxy.ui.adapter.capabilities.ServerStateUpdate
import io.qent.broxy.ui.adapter.clients.AiClientConnectionRequest
import io.qent.broxy.ui.adapter.clients.AiClientConnector
import io.qent.broxy.ui.adapter.data.UiSettingsRepository
import io.qent.broxy.ui.adapter.icons.ServerIconRepository
import io.qent.broxy.ui.adapter.models.UiAiClient
import io.qent.broxy.ui.adapter.models.UiMcpServerConfig
import io.qent.broxy.ui.adapter.models.UiMcpServersConfig
import io.qent.broxy.ui.adapter.models.UiPreset
import io.qent.broxy.ui.adapter.models.UiPresetCore
import io.qent.broxy.ui.adapter.models.UiPresetDraft
import io.qent.broxy.ui.adapter.models.UiProxyStatus
import io.qent.broxy.ui.adapter.models.UiServer
import io.qent.broxy.ui.adapter.models.UiServerDraft
import io.qent.broxy.ui.adapter.models.UiSettings
import io.qent.broxy.ui.adapter.models.UiStdioTransport
import io.qent.broxy.ui.adapter.models.toCore
import io.qent.broxy.ui.adapter.models.toUi
import io.qent.broxy.ui.adapter.remote.RemoteConnector
import io.qent.broxy.ui.adapter.remote.RemotePresetChange
import io.qent.broxy.ui.adapter.store.Intents
import io.qent.broxy.ui.adapter.store.toPresetCore
import io.qent.broxy.ui.adapter.store.toTransportConfig
import io.qent.broxy.ui.adapter.store.toUiPresetSummary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import io.qent.broxy.ui.adapter.data.openExternalUrl as openExternalUrlPlatform
import io.qent.broxy.ui.adapter.data.openLogsFolder as openLogsFolderPlatform
import io.qent.broxy.ui.adapter.data.signalOAuthCancellation as signalOAuthCancellationPlatform

private const val REMOTE_PORTAL_URL = "https://broxy.run/login"

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
    private val loadConfiguration: suspend () -> Result<Unit>,
    private val ioDispatcher: CoroutineDispatcher,
    private val refreshEnabledCaps: suspend (Boolean) -> Unit,
    private val syncBackgroundRefresh: () -> Unit,
    private val publishReady: () -> Unit,
    private val remoteConnector: RemoteConnector,
    private val now: () -> Long,
) : Intents {
    private val toggleLock = Mutex()
    private val proxyToggleLock = Mutex()

    override fun refresh() {
        scope.launch {
            val refreshResult = loadConfiguration()
            if (refreshResult.isFailure) {
                val msg = logFailure(logger, "refresh", refreshResult.exceptionOrNull(), "Failed to refresh")
                state.setError(msg)
                publishReady()
                return@launch
            }
            capabilityRefresher.syncWithServers(state.snapshot.servers.toCore())
            publishReady()
            proxyRuntime.ensureInboundRunning(forceRestart = true)
            if (!shouldApplyProxyUpdates(state.snapshot.proxyStatus, proxyRuntimeFacade.isRunning)) {
                refreshEnabledCaps(true)
            }
            syncBackgroundRefresh()
        }
    }

    override fun addOrUpdateServerUi(ui: UiServer) {
        scope.launch {
            val previousServers = state.snapshot.servers
            val previousConfig = state.snapshotConfig()
            val updated = previousServers.toMutableList()
            val idx = updated.indexOfFirst { it.id == ui.id }
            val base =
                updated.getOrNull(idx) ?: UiMcpServerConfig(
                    id = ui.id,
                    name = ui.name,
                    transport = UiStdioTransport(command = ""),
                    enabled = ui.enabled,
                )
            val merged = base.copy(name = ui.name, enabled = ui.enabled)
            if (idx >= 0) updated[idx] = merged else updated += merged
            state.updateSnapshot { copy(servers = updated) }
            val shouldShowConnecting =
                idx < 0 &&
                    merged.enabled &&
                    !capabilityRefresher.hasCachedSnapshot(merged.id)
            if (shouldShowConnecting) {
                capabilityRefresher.updateServerState(merged.id, ServerStateUpdate.Connecting)
            } else {
                publishReady()
            }
            val result =
                withContext(ioDispatcher) {
                    configurationManager.upsertServer(previousConfig.toCore(), merged.toCore())
                }
            if (result.isFailure) {
                revertServersOnFailure(
                    "addOrUpdateServerUi",
                    previousServers,
                    result.exceptionOrNull(),
                    "Failed to save servers",
                )
            } else {
                capabilityRefresher.updateCachedName(ui.id, ui.name)
                val saved = result.getOrNull()
                capabilityRefresher.syncWithServers(saved?.servers ?: updated.toCore())
                triggerServerRefresh(setOf(ui.id), force = true)
                applyServerConfigToProxy(saved?.toUi(), "addOrUpdateServerUi")
            }
            publishReady()
        }
    }

    override fun addServerBasic(
        id: String,
        name: String,
    ) {
        scope.launch {
            var previousServers: List<UiMcpServerConfig>? = null
            var newServer: UiMcpServerConfig? = null
            state.updateSnapshot {
                previousServers = servers
                if (servers.any { it.id == id }) return@updateSnapshot this
                val server = UiMcpServerConfig(id = id, name = name, transport = UiStdioTransport(command = ""), enabled = true)
                newServer = server
                val updated = servers.toMutableList().apply { add(server) }
                copy(servers = updated)
            }
            val addedServer = newServer ?: return@launch
            if (addedServer.enabled && !capabilityRefresher.hasCachedSnapshot(addedServer.id)) {
                capabilityRefresher.updateServerState(addedServer.id, ServerStateUpdate.Connecting)
            } else {
                publishReady()
            }
            val currentServers = state.snapshot.servers
            val result =
                withContext(ioDispatcher) {
                    configurationManager.upsertServer(state.snapshotConfig().toCore(), addedServer.toCore())
                }
            if (result.isFailure) {
                revertServersOnFailure(
                    "addServerBasic",
                    previousServers ?: state.snapshot.servers,
                    result.exceptionOrNull(),
                    "Failed to save servers",
                )
            } else {
                val saved = result.getOrNull()
                capabilityRefresher.syncWithServers(saved?.servers ?: currentServers.toCore())
                applyServerConfigToProxy(saved?.toUi(), "addServerBasic")
            }
            publishReady()
        }
    }

    override fun upsertServer(draft: UiServerDraft) {
        scope.launch {
            val originalId = draft.originalId?.trim()?.takeIf { it.isNotBlank() }
            val trimmedId = draft.id.trim()
            val trimmedIconPath = draft.iconPath?.trim()?.takeIf { it.isNotBlank() }
            val normalizedDraft =
                if (trimmedId == draft.id && originalId == draft.originalId && trimmedIconPath == draft.iconPath) {
                    draft
                } else {
                    draft.copy(id = trimmedId, originalId = originalId, iconPath = trimmedIconPath)
                }

            val previousServers = state.snapshot.servers
            val previousConfig = state.snapshotConfig()
            val updated = previousServers.toMutableList()
            val oldId = originalId?.takeIf { it != normalizedDraft.id }
            val isRename = oldId != null
            val renameId = if (isRename) requireNotNull(oldId) else null
            val previousIconPath =
                previousServers.firstOrNull { it.id == (renameId ?: normalizedDraft.id) }?.iconPath
            val cfg =
                UiMcpServerConfig(
                    id = normalizedDraft.id,
                    name = normalizedDraft.name,
                    enabled = normalizedDraft.enabled,
                    transport = normalizedDraft.transport.toTransportConfig(),
                    env = normalizedDraft.env,
                    iconPath = normalizedDraft.iconPath,
                )
            if (renameId != null) {
                val oldIndex = updated.indexOfFirst { it.id == renameId }
                val existingIndex = updated.indexOfFirst { it.id == cfg.id }
                if (existingIndex >= 0) {
                    updated[existingIndex] = cfg
                } else {
                    if (oldIndex >= 0) {
                        updated.removeAt(oldIndex)
                    }
                    val insertIndex = if (oldIndex >= 0) oldIndex else updated.size
                    updated.add(insertIndex.coerceAtMost(updated.size), cfg)
                }
                updated.removeAll { it.id == renameId }
            } else {
                val idx = updated.indexOfFirst { it.id == cfg.id }
                if (idx >= 0) updated[idx] = cfg else updated += cfg
            }
            val isNewServer = previousServers.none { it.id == cfg.id }
            state.updateSnapshot { copy(servers = updated) }
            val shouldShowConnecting =
                isNewServer &&
                    cfg.enabled &&
                    !capabilityRefresher.hasCachedSnapshot(cfg.id)
            if (shouldShowConnecting) {
                capabilityRefresher.updateServerState(cfg.id, ServerStateUpdate.Connecting)
            } else {
                publishReady()
            }

            val renameResult =
                if (renameId != null) {
                    withContext(ioDispatcher) {
                        configurationManager.renameServer(previousConfig.toCore(), oldId = renameId, server = cfg.toCore())
                    }
                } else {
                    null
                }
            val saveResult =
                renameResult?.map { it.config }
                    ?: withContext(ioDispatcher) {
                        configurationManager.upsertServer(previousConfig.toCore(), cfg.toCore())
                    }

            if (saveResult.isFailure) {
                revertServersOnFailure(
                    "upsertServer",
                    previousServers,
                    saveResult.exceptionOrNull(),
                    "Failed to save server",
                )
                cleanupIconOnFailure(normalizedDraft.iconPath, previousServers)
            } else {
                val savedConfig = saveResult.getOrNull()
                capabilityRefresher.syncWithServers(savedConfig?.servers ?: updated.toCore())
                if (renameId != null) {
                    capabilityRefresher.updateServerState(renameId, ServerStateUpdate.Removed)
                    val migrationError = renameResult?.getOrNull()?.presetMigrationError
                    if (migrationError != null) {
                        val msg =
                            logFailure(
                                logger,
                                "upsertServer(renamePresets,id=${cfg.id})",
                                migrationError,
                                "Failed to update presets after server rename",
                            )
                        state.setError(msg)
                    }
                }
                triggerServerRefresh(setOf(cfg.id), force = true)
                applyServerConfigToProxy(savedConfig?.toUi(), "upsertServer")
                removeIconIfUnused(previousIconPath, updated)
            }
            publishReady()
        }
    }

    override fun removeServer(id: String) {
        scope.launch {
            val previousServers = state.snapshot.servers
            val previousPopup = state.snapshot.authorizationPopup
            val previousConfig = state.snapshotConfig()
            val removedIconPath = previousServers.firstOrNull { it.id == id }?.iconPath
            val updated = previousServers.filterNot { it.id == id }
            val clearedPopup = if (previousPopup?.serverId == id) null else previousPopup
            state.updateSnapshot { copy(servers = updated, authorizationPopup = clearedPopup) }
            val result =
                withContext(ioDispatcher) {
                    configurationManager.removeServer(previousConfig.toCore(), id)
                }
            if (result.isFailure) {
                revertServersOnFailure(
                    "removeServer",
                    previousServers,
                    result.exceptionOrNull(),
                    "Failed to save servers",
                )
            } else {
                val saved = result.getOrNull()
                capabilityRefresher.syncWithServers(saved?.servers ?: updated.toCore())
                capabilityRefresher.updateServerState(id, ServerStateUpdate.Removed)
                applyServerConfigToProxy(saved?.toUi(), "removeServer")
                removeIconIfUnused(removedIconPath, updated)
            }
            publishReady()
        }
    }

    override fun toggleServer(
        id: String,
        enabled: Boolean,
    ) {
        scope.launch {
            val previousServers = state.snapshot.servers
            val previousPopup = state.snapshot.authorizationPopup
            val idx = previousServers.indexOfFirst { it.id == id }
            if (idx < 0) return@launch
            val updated = previousServers.toMutableList()
            updated[idx] = updated[idx].copy(enabled = enabled)
            val clearedPopup =
                if (!enabled && previousPopup?.serverId == id) {
                    null
                } else {
                    previousPopup
                }
            state.updateSnapshot {
                copy(
                    servers = updated,
                    authorizationPopup = clearedPopup,
                )
            }
            val hasCachedSnapshot = capabilityRefresher.hasCachedSnapshot(id)
            if (!enabled) {
                if (previousPopup?.serverId == id) {
                    scope.launch {
                        val cancelResult =
                            withContext(ioDispatcher) {
                                signalOAuthCancellationPlatform(previousPopup.redirectUri)
                            }
                        cancelResult.onFailure {
                            logFailure(logger, "toggleServer(id=$id)/cancelAuthorization", it, "Failed to cancel authorization")
                        }
                    }
                }
                capabilityRefresher.updateServerState(id, ServerStateUpdate.Disabled)
            } else if (!hasCachedSnapshot) {
                capabilityRefresher.updateServerState(id, ServerStateUpdate.Connecting)
            }
            publishReady()

            toggleLock.withLock {
                val currentEnabled =
                    state.snapshot.servers
                        .firstOrNull { it.id == id }
                        ?.enabled
                        ?: return@withLock
                if (currentEnabled != enabled) return@withLock

                val result =
                    withContext(ioDispatcher) {
                        configurationManager.toggleServer(state.snapshotConfig().toCore(), id, enabled)
                    }
                if (result.isFailure) {
                    val message = logFailure(logger, "toggleServer(id=$id)", result.exceptionOrNull(), "Failed to save server state")
                    val shouldRevert = state.snapshot.servers == updated
                    if (shouldRevert) {
                        state.updateSnapshot { copy(servers = previousServers) }
                        if (enabled) {
                            capabilityRefresher.updateServerState(id, ServerStateUpdate.Disabled)
                        } else if (!hasCachedSnapshot) {
                            capabilityRefresher.updateServerState(id, ServerStateUpdate.Connecting)
                        }
                    }
                    state.setError(message)
                    publishReady()
                    return@withLock
                }
                val savedConfig = result.getOrNull()
                val refreshedEnabled =
                    state.snapshot.servers
                        .firstOrNull { it.id == id }
                        ?.enabled
                if (refreshedEnabled != enabled) return@withLock
                if (enabled) {
                    triggerServerRefresh(setOf(id), force = false)
                }
                if (savedConfig != null && proxyRuntimeFacade.isRunning) {
                    val updateResult = proxyRuntimeFacade.updateServers(savedConfig)
                    if (updateResult.isFailure) {
                        logFailure(
                            logger,
                            "toggleServer(id=$id)/updateServers",
                            updateResult.exceptionOrNull(),
                            "Failed to update proxy servers",
                        )
                    }
                }
                publishReady()
            }
        }
    }

    override fun cancelAuthorization(serverId: String) {
        val popup = state.snapshot.authorizationPopup
        if (popup?.serverId == serverId) {
            scope.launch {
                val cancelResult =
                    withContext(ioDispatcher) {
                        signalOAuthCancellationPlatform(popup.redirectUri)
                    }
                cancelResult.onFailure {
                    logFailure(logger, "cancelAuthorization(id=$serverId)", it, "Failed to cancel authorization")
                }
            }
        }
        capabilityRefresher.updateServerState(serverId, ServerStateUpdate.Disabled)
        dismissAuthorizationPopup(serverId)
        toggleServer(serverId, enabled = false)
    }

    override fun openAuthorizationInBrowser(
        serverId: String,
        urlOverride: String?,
    ) {
        scope.launch {
            val popup = state.snapshot.authorizationPopup
            if (popup?.serverId != serverId) return@launch
            val targetUrl = urlOverride?.trim()?.takeIf { it.isNotBlank() } ?: popup.authorizationUrl
            val result =
                withContext(ioDispatcher) {
                    openExternalUrlPlatform(targetUrl)
                }
            if (result.isFailure) {
                logFailure(logger, "openAuthorizationInBrowser(id=$serverId)", result.exceptionOrNull(), "Failed to open authorization")
            }
        }
    }

    override fun dismissAuthorizationPopup(serverId: String) {
        scope.launch {
            val popup = state.snapshot.authorizationPopup
            if (popup?.serverId != serverId) return@launch
            state.updateSnapshot { copy(authorizationPopup = null) }
            publishReady()
        }
    }

    override fun refreshServerCapabilities(serverId: String) {
        scope.launch {
            state.updateSnapshot { copy(refreshingServerIds = refreshingServerIds + serverId) }
            publishReady()
            try {
                if (proxyRuntimeFacade.isRunning) {
                    val refreshResult =
                        withContext(ioDispatcher) {
                            proxyRuntimeFacade.refreshServerCapabilities(serverId)
                        }
                    if (refreshResult.isFailure) {
                        logFailure(
                            logger,
                            "refreshServerCapabilities(id=$serverId)",
                            refreshResult.exceptionOrNull(),
                            "Failed to refresh server capabilities",
                        )
                    }
                    return@launch
                }
                withContext(ioDispatcher) {
                    capabilityRefresher.refreshServersById(setOf(serverId), force = true)
                }
            } finally {
                state.updateSnapshot { copy(refreshingServerIds = refreshingServerIds - serverId) }
                publishReady()
            }
        }
    }

    override fun pickServerIcon(serverId: String) {
        scope.launch {
            val previousServers = state.snapshot.servers
            val target = previousServers.firstOrNull { it.id == serverId } ?: return@launch
            val pickResult =
                withContext(ioDispatcher) {
                    serverIconRepository.pickAndImportIcon()
                }
            if (pickResult.isFailure) {
                val msg =
                    logFailure(
                        logger,
                        "pickServerIcon(id=$serverId)",
                        pickResult.exceptionOrNull(),
                        "Failed to pick server icon",
                    )
                state.setError(msg)
                publishReady()
                return@launch
            }
            val pickedPath = pickResult.getOrNull()?.trim().orEmpty()
            if (pickedPath.isBlank()) {
                return@launch
            }
            val updatedServer = target.copy(iconPath = pickedPath)
            val updatedServers = previousServers.map { if (it.id == serverId) updatedServer else it }
            state.updateSnapshot { copy(servers = updatedServers) }
            publishReady()
            val saveResult =
                withContext(ioDispatcher) {
                    configurationManager.upsertServer(state.snapshotConfig().toCore(), updatedServer.toCore())
                }
            if (saveResult.isFailure) {
                revertServersOnFailure(
                    "pickServerIcon",
                    previousServers,
                    saveResult.exceptionOrNull(),
                    "Failed to save server icon",
                )
                cleanupIconOnFailure(pickedPath, previousServers)
                publishReady()
                return@launch
            }
            val savedConfig = saveResult.getOrNull()
            applyServerConfigToProxy(savedConfig?.toUi(), "pickServerIcon")
            removeIconIfUnused(target.iconPath, updatedServers)
            publishReady()
        }
    }

    override fun clearServerIcon(serverId: String) {
        scope.launch {
            val previousServers = state.snapshot.servers
            val target = previousServers.firstOrNull { it.id == serverId } ?: return@launch
            val previousIconPath = target.iconPath?.trim()?.takeIf { it.isNotBlank() } ?: return@launch
            val updatedServer = target.copy(iconPath = null)
            val updatedServers = previousServers.map { if (it.id == serverId) updatedServer else it }
            state.updateSnapshot { copy(servers = updatedServers) }
            publishReady()
            val saveResult =
                withContext(ioDispatcher) {
                    configurationManager.upsertServer(state.snapshotConfig().toCore(), updatedServer.toCore())
                }
            if (saveResult.isFailure) {
                revertServersOnFailure(
                    "clearServerIcon",
                    previousServers,
                    saveResult.exceptionOrNull(),
                    "Failed to clear server icon",
                )
                publishReady()
                return@launch
            }
            val savedConfig = saveResult.getOrNull()
            applyServerConfigToProxy(savedConfig?.toUi(), "clearServerIcon")
            removeIconIfUnused(previousIconPath, updatedServers)
            publishReady()
        }
    }

    override fun addOrUpdatePreset(preset: UiPreset) {
        scope.launch {
            val previousSnapshot = state.snapshot
            val previousPresets = previousSnapshot.presets
            val updated = previousPresets.toMutableList()
            val idx = updated.indexOfFirst { it.id == preset.id }
            if (idx >= 0) updated[idx] = preset else updated += preset
            state.updateSnapshot { copy(presets = updated) }
            val result =
                withContext(ioDispatcher) {
                    configurationManager.savePreset(
                        UiPresetCore(
                            id = preset.id,
                            name = preset.name,
                            tools = emptyList(),
                            prompts = null,
                            resources = null,
                        ).toCore(),
                    )
                }
            if (result.isFailure) {
                revertPresetsOnFailure(
                    "addOrUpdatePreset",
                    previousSnapshot,
                    result.exceptionOrNull(),
                    "Failed to save preset",
                )
            }
            publishReady()
        }
    }

    override fun upsertPreset(draft: UiPresetDraft) {
        scope.launch {
            val originalId = draft.originalId?.trim()?.takeIf { it.isNotBlank() }
            val trimmedId = draft.id.trim()
            val normalizedDraft = if (trimmedId == draft.id) draft else draft.copy(id = trimmedId)
            val basePreset = normalizedDraft.toPresetCore()
            val preset =
                if (basePreset.createdAtEpochMillis == null) {
                    basePreset.copy(createdAtEpochMillis = now())
                } else {
                    basePreset
                }
            val previousSnapshot = state.snapshot
            val previousConfig = state.snapshotConfig()
            val isRename = originalId != null && originalId != preset.id
            val renameId = if (isRename) requireNotNull(originalId) else null

            val saveResult =
                withContext(ioDispatcher) {
                    configurationManager.savePreset(preset.toCore())
                }
            if (saveResult.isFailure) {
                val msg = logFailure(logger, "upsertPreset", saveResult.exceptionOrNull(), "Failed to save preset")
                state.setError(msg)
            }

            val updatedPresets = previousSnapshot.presets.toMutableList()
            val summary = preset.toUiPresetSummary()
            if (renameId != null) {
                val oldIndex = updatedPresets.indexOfFirst { it.id == renameId }
                val existingIndex = updatedPresets.indexOfFirst { it.id == summary.id }
                if (existingIndex >= 0) {
                    updatedPresets[existingIndex] = summary
                } else {
                    if (oldIndex >= 0) {
                        updatedPresets.removeAt(oldIndex)
                    }
                    val insertIndex = if (oldIndex >= 0) oldIndex else updatedPresets.size
                    updatedPresets.add(insertIndex.coerceAtMost(updatedPresets.size), summary)
                }
                updatedPresets.removeAll { it.id == renameId }
            } else {
                val idx = updatedPresets.indexOfFirst { it.id == summary.id }
                if (idx >= 0) updatedPresets[idx] = summary else updatedPresets += summary
            }

            var defaultPresetId = previousSnapshot.defaultPresetId
            if (saveResult.isSuccess && renameId != null) {
                val wasDefault = previousSnapshot.defaultPresetId == renameId
                if (wasDefault) {
                    val configSave =
                        withContext(ioDispatcher) {
                            configurationManager.settings.updateDefaultPresetId(previousConfig.toCore(), preset.id)
                        }
                    if (configSave.isFailure) {
                        val msg =
                            logFailure(
                                logger,
                                "upsertPreset(renameDefault,id=$renameId)",
                                configSave.exceptionOrNull(),
                                "Failed to update default preset",
                            )
                        state.setError(msg)
                        state.updateSnapshot { copy(presets = updatedPresets) }
                        publishReady()
                        return@launch
                    }
                    defaultPresetId = preset.id
                }

                val deleteResult =
                    withContext(ioDispatcher) {
                        configurationManager.deletePreset(renameId)
                    }
                if (deleteResult.isFailure) {
                    val msg =
                        logFailure(
                            logger,
                            "upsertPreset(deleteOld,id=$renameId)",
                            deleteResult.exceptionOrNull(),
                            "Failed to remove old preset",
                        )
                    state.setError(msg)
                }
                updatedPresets.removeAll { it.id == renameId }
            }

            state.updateSnapshot { copy(presets = updatedPresets, defaultPresetId = defaultPresetId) }
            val shouldRestart =
                saveResult.isSuccess &&
                    (
                        previousSnapshot.activeProxyPresetId == preset.id ||
                            (renameId != null && previousSnapshot.activeProxyPresetId == renameId)
                    )
            publishReady()
            if (shouldRestart) {
                val reloadId =
                    if (renameId != null && previousSnapshot.activeProxyPresetId == renameId) {
                        preset.id
                    } else {
                        null
                    }
                val reloadResult = proxyRuntime.ensureInboundRunning(presetIdOverride = reloadId, forceReloadPreset = true)
                if (reloadResult.isFailure) {
                    val msg = failureMessage(reloadResult.exceptionOrNull(), "Failed to apply preset")
                    pushToast(msg)
                }
            }
        }
    }

    override fun removePreset(id: String) {
        scope.launch {
            val previous = state.snapshot
            val updated = previous.presets.filterNot { it.id == id }
            state.updateSnapshot { withPresets(updated) }
            val result =
                withContext(ioDispatcher) {
                    configurationManager.deletePreset(id)
                }
            if (result.isFailure) {
                revertPresetsOnFailure(
                    "removePreset",
                    previous,
                    result.exceptionOrNull(),
                    "Failed to delete preset",
                )
            }
            publishReady()
            if (previous.defaultPresetId == id) {
                val saveResult =
                    withContext(ioDispatcher) {
                        configurationManager.settings.updateDefaultPresetId(state.snapshotConfig().toCore(), null)
                    }
                if (saveResult.isFailure) {
                    logFailure(
                        logger,
                        "removePreset(clearDefault,id=$id)",
                        saveResult.exceptionOrNull(),
                        "Failed to clear default preset",
                    )
                }
            }
            val reloadId =
                if (previous.activeProxyPresetId == id) {
                    UiPresetCore.EMPTY_PRESET_ID
                } else {
                    null
                }
            val reloadResult = proxyRuntime.ensureInboundRunning(presetIdOverride = reloadId, forceReloadPreset = true)
            if (reloadResult.isFailure) {
                val msg = failureMessage(reloadResult.exceptionOrNull(), "Failed to apply preset")
                pushToast(msg)
            }
        }
    }

    override fun selectProxyPreset(presetId: String?) {
        scope.launch {
            val isRunning = state.snapshot.proxyStatus is UiProxyStatus.Running
            val currentActive = state.snapshot.activeProxyPresetId
            if (isRunning && currentActive == presetId) return@launch
            val applyResult =
                proxyRuntime.ensureInboundRunning(
                    presetIdOverride = presetId,
                    forceReloadPreset = true,
                )
            if (applyResult.isFailure) {
                val msg = failureMessage(applyResult.exceptionOrNull(), "Failed to apply preset")
                pushToast(msg)
                return@launch
            }
            val previousConfig = state.snapshotConfig()
            val saveResult =
                withContext(ioDispatcher) {
                    configurationManager.settings.updateDefaultPresetId(previousConfig.toCore(), presetId)
                }
            if (saveResult.isFailure) {
                val msg =
                    logFailure(
                        logger,
                        "selectProxyPreset(saveDefault)",
                        saveResult.exceptionOrNull(),
                        "Failed to save default preset",
                    )
                pushToast(msg)
                return@launch
            }
            state.updateSnapshot { copy(defaultPresetId = presetId) }
            publishReady()
        }
    }

    override fun updateInboundHttpPort(port: Int) {
        scope.launch {
            val clamped = clampPort(port)
            val previous = state.snapshot.inboundHttpPort
            val previousClients = state.snapshot.clients
            if (previous == clamped) return@launch
            val previousConfig = state.snapshotConfig()
            val updatedClients = buildAiClients(clamped)
            state.updateSnapshot { copy(inboundHttpPort = clamped, clients = updatedClients) }
            publishReady()
            val result =
                withContext(ioDispatcher) {
                    configurationManager.settings.updateInboundHttpPort(previousConfig.toCore(), clamped)
                }
            if (result.isFailure) {
                val msg = logFailure(logger, "updateInboundHttpPort", result.exceptionOrNull(), "Failed to update HTTP port")
                state.updateSnapshot { copy(inboundHttpPort = previous, clients = previousClients) }
                state.setError(msg)
                publishReady()
                return@launch
            }
            proxyRuntime.ensureInboundRunning(forceRestart = true)
        }
    }

    override fun updateRequestTimeout(seconds: Int) {
        scope.launch {
            val previous = state.snapshot.requestTimeoutSeconds
            val previousConfig = state.snapshotConfig()
            state.updateSnapshot { copy(requestTimeoutSeconds = seconds) }
            proxyRuntimeFacade.updateCallTimeout(state.snapshot.requestTimeoutSeconds)
            val result =
                withContext(ioDispatcher) {
                    configurationManager.settings.updateRequestTimeout(previousConfig.toCore(), seconds)
                }
            if (result.isFailure) {
                val msg = logFailure(logger, "updateRequestTimeout", result.exceptionOrNull(), "Failed to update timeout")
                state.updateSnapshot { copy(requestTimeoutSeconds = previous) }
                proxyRuntimeFacade.updateCallTimeout(previous)
                state.setError(msg)
            }
            publishReady()
        }
    }

    override fun updateCapabilitiesTimeout(seconds: Int) {
        scope.launch {
            val previous = state.snapshot.capabilitiesTimeoutSeconds
            val previousConfig = state.snapshotConfig()
            state.updateSnapshot { copy(capabilitiesTimeoutSeconds = seconds) }
            proxyRuntimeFacade.updateCapabilitiesTimeout(state.snapshot.capabilitiesTimeoutSeconds)
            val result =
                withContext(ioDispatcher) {
                    configurationManager.settings.updateCapabilitiesTimeout(previousConfig.toCore(), seconds)
                }
            if (result.isFailure) {
                val msg = logFailure(logger, "updateCapabilitiesTimeout", result.exceptionOrNull(), "Failed to update capabilities timeout")
                state.updateSnapshot { copy(capabilitiesTimeoutSeconds = previous) }
                proxyRuntimeFacade.updateCapabilitiesTimeout(previous)
                state.setError(msg)
            }
            publishReady()
        }
    }

    override fun updateConnectionRetryCount(count: Int) {
        scope.launch {
            val clamped = clampConnectionRetryCount(count)
            val previous = state.snapshot.connectionRetryCount
            val previousConfig = state.snapshotConfig()
            state.updateSnapshot { copy(connectionRetryCount = clamped) }
            proxyRuntimeFacade.updateConnectionRetryCount(clamped)
            val result =
                withContext(ioDispatcher) {
                    configurationManager.settings.updateConnectionRetryCount(previousConfig.toCore(), clamped)
                }
            if (result.isFailure) {
                val msg =
                    logFailure(
                        logger,
                        "updateConnectionRetryCount",
                        result.exceptionOrNull(),
                        "Failed to update connection retries",
                    )
                state.updateSnapshot { copy(connectionRetryCount = previous) }
                proxyRuntimeFacade.updateConnectionRetryCount(previous)
                state.setError(msg)
            }
            publishReady()
        }
    }

    override fun updateIgnoreHttpsCertificateErrors(enabled: Boolean) {
        scope.launch {
            if (state.snapshot.ignoreHttpsCertificateErrors == enabled) return@launch
            val previous = state.snapshot.ignoreHttpsCertificateErrors
            val previousConfig = state.snapshotConfig()
            state.updateSnapshot { copy(ignoreHttpsCertificateErrors = enabled) }
            proxyRuntimeFacade.updateIgnoreHttpsCertificateErrors(enabled)
            val result =
                withContext(ioDispatcher) {
                    configurationManager.settings.updateIgnoreHttpsCertificateErrors(previousConfig.toCore(), enabled)
                }
            if (result.isFailure) {
                val msg =
                    logFailure(
                        logger,
                        "updateIgnoreHttpsCertificateErrors",
                        result.exceptionOrNull(),
                        "Failed to update HTTPS certificate handling",
                    )
                state.updateSnapshot { copy(ignoreHttpsCertificateErrors = previous) }
                proxyRuntimeFacade.updateIgnoreHttpsCertificateErrors(previous)
                state.setError(msg)
            }
            publishReady()
        }
    }

    override fun updateCapabilitiesRefreshInterval(seconds: Int) {
        scope.launch {
            val clamped = clampRefreshIntervalSeconds(seconds)
            if (state.snapshot.capabilitiesRefreshIntervalSeconds == clamped) return@launch
            val previous = state.snapshot.capabilitiesRefreshIntervalSeconds
            val previousConfig = state.snapshotConfig()
            state.updateSnapshot { copy(capabilitiesRefreshIntervalSeconds = clamped) }
            val result =
                withContext(ioDispatcher) {
                    configurationManager.settings.updateRefreshInterval(previousConfig.toCore(), clamped)
                }
            if (result.isFailure) {
                val msg =
                    logFailure(
                        logger,
                        "updateCapabilitiesRefreshInterval",
                        result.exceptionOrNull(),
                        "Failed to update refresh interval",
                    )
                state.updateSnapshot { copy(capabilitiesRefreshIntervalSeconds = previous) }
                state.setError(msg)
            } else {
                if (!shouldApplyProxyUpdates(state.snapshot.proxyStatus, proxyRuntimeFacade.isRunning)) {
                    refreshEnabledCaps(true)
                }
                syncBackgroundRefresh()
            }
            publishReady()
        }
    }

    override fun updateTrayIconVisibility(visible: Boolean) {
        scope.launch {
            if (state.snapshot.showTrayIcon == visible) return@launch
            val previous = state.snapshot.showTrayIcon
            state.updateSnapshot { copy(showTrayIcon = visible) }
            val result =
                withContext(ioDispatcher) {
                    runCatching {
                        val existing =
                            runCatching { uiSettingsRepository.loadUiSettings() }
                                .onFailure { logger.warn("Failed to load ui.json before save: ${it.message}", it) }
                                .getOrElse { UiSettings(showTrayIcon = previous) }
                        uiSettingsRepository.saveUiSettings(existing.copy(showTrayIcon = visible))
                    }
                }
            if (result.isFailure) {
                val msg = logFailure(logger, "updateTrayIconVisibility", result.exceptionOrNull(), "Failed to update tray preference")
                state.updateSnapshot { copy(showTrayIcon = previous) }
                state.setError(msg)
            }
            publishReady()
        }
    }

    override fun updateFallbackPromptsAndResourcesToTools(enabled: Boolean) {
        scope.launch {
            if (state.snapshot.fallbackPromptsAndResourcesToTools == enabled) return@launch
            val previous = state.snapshot.fallbackPromptsAndResourcesToTools
            val previousConfig = state.snapshotConfig()
            state.updateSnapshot { copy(fallbackPromptsAndResourcesToTools = enabled) }
            proxyRuntimeFacade.updateFallbackPromptsAndResourcesToTools(enabled)
            val result =
                withContext(ioDispatcher) {
                    configurationManager.settings.updateFallbackPromptsAndResourcesToTools(previousConfig.toCore(), enabled)
                }
            if (result.isFailure) {
                val msg =
                    logFailure(
                        logger,
                        "updateFallbackPromptsAndResourcesToTools",
                        result.exceptionOrNull(),
                        "Failed to update prompt/resource tool fallback",
                    )
                state.updateSnapshot { copy(fallbackPromptsAndResourcesToTools = previous) }
                proxyRuntimeFacade.updateFallbackPromptsAndResourcesToTools(previous)
                state.setError(msg)
            }
            publishReady()
        }
    }

    override fun updateAdapterMode(enabled: Boolean) {
        scope.launch {
            if (state.snapshot.adapterMode == enabled) return@launch
            val previous = state.snapshot.adapterMode
            val previousConfig = state.snapshotConfig()
            state.updateSnapshot { copy(adapterMode = enabled) }
            publishReady()
            val result =
                withContext(ioDispatcher) {
                    proxyRuntimeFacade.updateAdapterMode(enabled)
                    configurationManager.settings.updateAdapterMode(previousConfig.toCore(), enabled)
                }
            if (result.isFailure) {
                val msg =
                    logFailure(
                        logger,
                        "updateAdapterMode",
                        result.exceptionOrNull(),
                        "Failed to update adapter mode",
                    )
                state.updateSnapshot { copy(adapterMode = previous) }
                withContext(ioDispatcher) {
                    proxyRuntimeFacade.updateAdapterMode(previous)
                }
                state.setError(msg)
            } else if (state.snapshot.proxyStatus is UiProxyStatus.Running) {
                remoteConnector.notifyPresetChanged(state.snapshot.activeProxyPresetId, RemotePresetChange.COMPOSITION)
            }
            publishReady()
        }
    }

    override fun toggleProxyServer() {
        scope.launch {
            val status = state.snapshot.proxyStatus
            if (status is UiProxyStatus.Starting || status is UiProxyStatus.Stopping) return@launch
            proxyToggleLock.withLock {
                when (val current = state.snapshot.proxyStatus) {
                    UiProxyStatus.Running ->
                        withContext(ioDispatcher) {
                            proxyRuntime.stopInbound()
                        }

                    UiProxyStatus.Stopped,
                    is UiProxyStatus.Error,
                    -> proxyRuntime.ensureInboundRunning(forceRestart = true)

                    UiProxyStatus.Starting,
                    UiProxyStatus.Stopping,
                    -> Unit
                }
            }
        }
    }

    override fun openLogsFolder() {
        scope.launch {
            val result =
                withContext(ioDispatcher) {
                    openLogsFolderPlatform()
                }
            if (result.isFailure) {
                logFailure(logger, "openLogsFolder", result.exceptionOrNull(), "Failed to open logs folder")
            } else {
                logInfo(logger, "openLogsFolder", "opened")
            }
        }
    }

    override fun openRemotePortal() {
        scope.launch {
            val result =
                withContext(ioDispatcher) {
                    openExternalUrlPlatform(REMOTE_PORTAL_URL)
                }
            if (result.isFailure) {
                logFailure(logger, "openRemotePortal", result.exceptionOrNull(), "Failed to open remote portal")
            }
        }
    }

    override fun startRemoteAuthorization() {
        remoteConnector.beginAuthorization()
    }

    override fun connectRemote() {
        remoteConnector.connect()
    }

    override fun disconnectRemote() {
        remoteConnector.disconnect()
    }

    override fun logoutRemote() {
        remoteConnector.logout()
    }

    override fun connectAiClient(clientId: String) {
        scope.launch {
            val connector = aiClientConnectors.firstOrNull { it.descriptor.id == clientId } ?: return@launch
            val request = AiClientConnectionRequest(httpEndpoint = httpEndpointFor(state.snapshot.inboundHttpPort))
            val result =
                withContext(ioDispatcher) {
                    connector.connect(request)
                }
            if (result.isFailure) {
                val msg = logFailure(logger, "connectAiClient(id=$clientId)", result.exceptionOrNull(), "Failed to connect client")
                state.setError(msg)
            }
            refreshAiClients()
            publishReady()
        }
    }

    override fun disconnectAiClient(clientId: String) {
        scope.launch {
            val connector = aiClientConnectors.firstOrNull { it.descriptor.id == clientId } ?: return@launch
            val request = AiClientConnectionRequest(httpEndpoint = httpEndpointFor(state.snapshot.inboundHttpPort))
            val result =
                withContext(ioDispatcher) {
                    connector.disconnect(request)
                }
            if (result.isFailure) {
                val msg =
                    logFailure(
                        logger,
                        "disconnectAiClient(id=$clientId)",
                        result.exceptionOrNull(),
                        "Failed to disconnect client",
                    )
                state.setError(msg)
            }
            refreshAiClients()
            publishReady()
        }
    }

    override fun openAiClientInfo(clientId: String) {
        scope.launch {
            val connector = aiClientConnectors.firstOrNull { it.descriptor.id == clientId } ?: return@launch
            val result =
                withContext(ioDispatcher) {
                    openExternalUrlPlatform(connector.descriptor.infoUrl)
                }
            if (result.isFailure) {
                logFailure(
                    logger,
                    "openAiClientInfo(id=$clientId)",
                    result.exceptionOrNull(),
                    "Failed to open client info",
                )
            }
        }
    }

    private fun revertServersOnFailure(
        operation: String,
        previousServers: List<UiMcpServerConfig>,
        failure: Throwable?,
        defaultMessage: String,
    ) {
        val message = logFailure(logger, operation, failure, defaultMessage)
        state.updateSnapshot {
            copy(servers = previousServers)
        }
        state.setError(message)
    }

    private fun revertPresetsOnFailure(
        operation: String,
        previousSnapshot: StoreSnapshot,
        failure: Throwable?,
        defaultMessage: String,
    ) {
        val message = logFailure(logger, operation, failure, defaultMessage)
        state.updateSnapshot { previousSnapshot }
        state.setError(message)
    }

    private fun pushToast(message: String) {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) return
        state.updateSnapshot { copy(toastMessage = trimmed, toastMessageId = toastMessageId + 1) }
        publishReady()
    }

    private fun triggerServerRefresh(
        ids: Set<String>,
        force: Boolean,
    ) {
        if (ids.isEmpty()) return
        if (shouldApplyProxyUpdates(state.snapshot.proxyStatus, proxyRuntimeFacade.isRunning)) return
        scope.launch { capabilityRefresher.refreshServersById(ids, force) }
    }

    private suspend fun applyServerConfigToProxy(
        config: UiMcpServersConfig?,
        operation: String,
    ) {
        if (config == null) return
        if (proxyRuntimeFacade.isRunning) {
            val updateResult = proxyRuntimeFacade.updateServers(config.toCore())
            if (updateResult.isFailure) {
                logFailure(
                    logger,
                    "$operation/updateServers",
                    updateResult.exceptionOrNull(),
                    "Failed to update proxy servers",
                )
            }
            return
        }
        proxyRuntime.ensureInboundRunning()
    }

    private suspend fun removeIconIfUnused(
        iconPath: String?,
        servers: List<UiMcpServerConfig>,
    ) {
        val trimmed = iconPath?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (servers.any { it.iconPath == trimmed }) return
        withContext(ioDispatcher) {
            serverIconRepository.deleteIcon(trimmed)
        }.onFailure {
            logFailure(logger, "removeIconIfUnused(path=$trimmed)", it, "Failed to delete unused server icon")
        }
    }

    private suspend fun cleanupIconOnFailure(
        iconPath: String?,
        previousServers: List<UiMcpServerConfig>,
    ) {
        val trimmed = iconPath?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (previousServers.any { it.iconPath == trimmed }) return
        withContext(ioDispatcher) {
            serverIconRepository.deleteIcon(trimmed)
        }.onFailure {
            logFailure(logger, "cleanupIconOnFailure(path=$trimmed)", it, "Failed to delete unused server icon")
        }
    }

    private suspend fun refreshAiClients() {
        if (aiClientConnectors.isEmpty()) return
        val clients = buildAiClients(state.snapshot.inboundHttpPort)
        state.updateSnapshot { copy(clients = clients) }
    }
}
