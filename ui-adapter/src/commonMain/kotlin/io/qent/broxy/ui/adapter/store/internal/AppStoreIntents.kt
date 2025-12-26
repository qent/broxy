package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.core.capabilities.CapabilityRefresher
import io.qent.broxy.core.config.ConfigurationManager
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.proxy.runtime.ProxyLifecycle
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.ui.adapter.models.UiMcpServerConfig
import io.qent.broxy.ui.adapter.models.UiPreset
import io.qent.broxy.ui.adapter.models.UiPresetCore
import io.qent.broxy.ui.adapter.models.UiPresetDraft
import io.qent.broxy.ui.adapter.models.UiServer
import io.qent.broxy.ui.adapter.models.UiServerDraft
import io.qent.broxy.ui.adapter.models.UiStdioTransport
import io.qent.broxy.ui.adapter.remote.RemoteConnector
import io.qent.broxy.ui.adapter.store.Intents
import io.qent.broxy.ui.adapter.store.toCorePreset
import io.qent.broxy.ui.adapter.store.toTransportConfig
import io.qent.broxy.ui.adapter.store.toUiPresetSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import io.qent.broxy.ui.adapter.data.openExternalUrl as openExternalUrlPlatform
import io.qent.broxy.ui.adapter.data.openLogsFolder as openLogsFolderPlatform
import io.qent.broxy.ui.adapter.data.signalOAuthCancellation as signalOAuthCancellationPlatform

internal class AppStoreIntents(
    private val scope: CoroutineScope,
    private val logger: CollectingLogger,
    private val configurationManager: ConfigurationManager,
    private val state: StoreStateAccess,
    private val capabilityRefresher: CapabilityRefresher,
    private val proxyRuntime: ProxyRuntime,
    private val proxyLifecycle: ProxyLifecycle,
    private val loadConfiguration: suspend () -> Result<Unit>,
    private val refreshEnabledCaps: suspend (Boolean) -> Unit,
    private val restartRefreshJob: (Boolean) -> Unit,
    private val publishReady: () -> Unit,
    private val remoteConnector: RemoteConnector,
    private val now: () -> Long,
) : Intents {
    override fun refresh() {
        scope.launch {
            val refreshResult = loadConfiguration()
            if (refreshResult.isFailure) {
                val msg = refreshResult.exceptionOrNull()?.message ?: "Failed to refresh"
                logger.info("[AppStore] refresh failed: $msg")
                state.setError(msg)
            } else {
                capabilityRefresher.syncWithServers(state.snapshot.servers)
            }
            publishReady()
            proxyRuntime.ensureInboundRunning(forceRestart = true)
            if (proxyLifecycle.isRunning()) {
                restartRefreshJob(false)
            } else {
                refreshEnabledCaps(true)
                restartRefreshJob(true)
            }
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
            val result = configurationManager.upsertServer(previousConfig, merged)
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
                capabilityRefresher.syncWithServers(saved?.servers ?: updated)
                triggerServerRefresh(setOf(ui.id), force = true)
                applyServerConfigToProxy(saved, "addOrUpdateServerUi")
            }
            publishReady()
        }
    }

    override fun addServerBasic(
        id: String,
        name: String,
    ) {
        scope.launch {
            val previousServers = state.snapshot.servers
            val previousConfig = state.snapshotConfig()
            if (previousServers.any { it.id == id }) return@launch
            val updated =
                previousServers.toMutableList().apply {
                    add(UiMcpServerConfig(id = id, name = name, transport = UiStdioTransport(command = ""), enabled = true))
                }
            state.updateSnapshot { copy(servers = updated) }
            val newServer = updated.first { it.id == id }
            val result = configurationManager.upsertServer(previousConfig, newServer)
            if (result.isFailure) {
                revertServersOnFailure(
                    "addServerBasic",
                    previousServers,
                    result.exceptionOrNull(),
                    "Failed to save servers",
                )
            } else {
                val saved = result.getOrNull()
                capabilityRefresher.syncWithServers(saved?.servers ?: updated)
                applyServerConfigToProxy(saved, "addServerBasic")
            }
            publishReady()
        }
    }

    override fun upsertServer(draft: UiServerDraft) {
        scope.launch {
            val originalId = draft.originalId?.trim()?.takeIf { it.isNotBlank() }
            val trimmedId = draft.id.trim()
            val normalizedDraft =
                if (trimmedId == draft.id && originalId == draft.originalId) {
                    draft
                } else {
                    draft.copy(id = trimmedId, originalId = originalId)
                }

            val previousServers = state.snapshot.servers
            val previousConfig = state.snapshotConfig()
            val updated = previousServers.toMutableList()
            val oldId = originalId?.takeIf { it != normalizedDraft.id }
            val isRename = oldId != null
            val cfg =
                UiMcpServerConfig(
                    id = normalizedDraft.id,
                    name = normalizedDraft.name,
                    enabled = normalizedDraft.enabled,
                    transport = normalizedDraft.transport.toTransportConfig(),
                    env = normalizedDraft.env,
                )
            if (isRename) {
                val oldIndex = updated.indexOfFirst { it.id == oldId }
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
                updated.removeAll { it.id == oldId }
            } else {
                val idx = updated.indexOfFirst { it.id == cfg.id }
                if (idx >= 0) updated[idx] = cfg else updated += cfg
            }
            state.updateSnapshot { copy(servers = updated) }

            val renameResult =
                if (isRename) {
                    configurationManager.renameServer(previousConfig, oldId = oldId!!, server = cfg)
                } else {
                    null
                }
            val saveResult =
                renameResult?.map { it.config }
                    ?: configurationManager.upsertServer(previousConfig, cfg)

            if (saveResult.isFailure) {
                revertServersOnFailure(
                    "upsertServer",
                    previousServers,
                    saveResult.exceptionOrNull(),
                    "Failed to save server",
                )
            } else {
                val savedConfig = saveResult.getOrNull()
                capabilityRefresher.syncWithServers(savedConfig?.servers ?: updated)
                if (isRename) {
                    capabilityRefresher.markServerRemoved(oldId!!)
                    val migrationError = renameResult?.getOrNull()?.presetMigrationError
                    if (migrationError != null) {
                        val msg = migrationError.message ?: "Failed to update presets after server rename"
                        logger.info("[AppStore] upsertServer rename preset migration failed: $msg")
                        state.setError(msg)
                    }
                }
                triggerServerRefresh(setOf(cfg.id), force = true)
                applyServerConfigToProxy(savedConfig, "upsertServer")
            }
            publishReady()
        }
    }

    override fun removeServer(id: String) {
        scope.launch {
            val previousServers = state.snapshot.servers
            val previousPopup = state.snapshot.authorizationPopup
            val previousConfig = state.snapshotConfig()
            val updated = previousServers.filterNot { it.id == id }
            val clearedPopup = if (previousPopup?.serverId == id) null else previousPopup
            state.updateSnapshot { copy(servers = updated, authorizationPopup = clearedPopup) }
            val result = configurationManager.removeServer(previousConfig, id)
            if (result.isFailure) {
                revertServersOnFailure(
                    "removeServer",
                    previousServers,
                    result.exceptionOrNull(),
                    "Failed to save servers",
                )
            } else {
                val saved = result.getOrNull()
                capabilityRefresher.syncWithServers(saved?.servers ?: updated)
                capabilityRefresher.markServerRemoved(id)
                applyServerConfigToProxy(saved, "removeServer")
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
            val previousPendingToggles = state.snapshot.pendingServerToggles
            val previousPopup = state.snapshot.authorizationPopup
            val previousConfig = state.snapshotConfig()
            val idx = previousServers.indexOfFirst { it.id == id }
            if (idx < 0) return@launch
            val updated = previousServers.toMutableList()
            updated[idx] = updated[idx].copy(enabled = enabled)
            val updatedPending =
                if (!enabled) {
                    previousPendingToggles + id
                } else {
                    previousPendingToggles - id
                }
            val clearedPopup =
                if (!enabled && previousPopup?.serverId == id) {
                    null
                } else {
                    previousPopup
                }
            state.updateSnapshot {
                copy(
                    servers = updated,
                    pendingServerToggles = updatedPending,
                    authorizationPopup = clearedPopup,
                )
            }
            if (enabled) {
                if (!capabilityRefresher.hasCachedSnapshot(id)) {
                    capabilityRefresher.markServerConnecting(id)
                }
            }
            val result = configurationManager.toggleServer(previousConfig, id, enabled)
            if (result.isFailure) {
                revertServersOnFailure(
                    "toggleServer",
                    previousServers,
                    result.exceptionOrNull(),
                    "Failed to save server state",
                    previousPendingToggles,
                )
            } else {
                val savedConfig = result.getOrNull()
                if (!enabled) {
                    capabilityRefresher.markServerDisabled(id)
                } else {
                    triggerServerRefresh(setOf(id), force = false)
                }
                if (savedConfig != null && proxyLifecycle.isRunning()) {
                    val updateResult = proxyLifecycle.updateServers(savedConfig)
                    if (updateResult.isFailure) {
                        logger.info("[AppStore] toggleServer updateServers failed: ${updateResult.exceptionOrNull()?.message}")
                    }
                }
                if (!enabled) {
                    state.updateSnapshot { copy(pendingServerToggles = pendingServerToggles - id) }
                }
            }
            publishReady()
        }
    }

    override fun cancelAuthorization(serverId: String) {
        val popup = state.snapshot.authorizationPopup
        if (popup?.serverId == serverId) {
            scope.launch {
                signalOAuthCancellationPlatform(popup.redirectUri)
                    .onFailure {
                        logger.info("[AppStore] cancelAuthorization signal failed: ${it.message}")
                    }
            }
        }
        capabilityRefresher.markServerDisabled(serverId)
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
            val result = openExternalUrlPlatform(targetUrl)
            if (result.isFailure) {
                logger.info("[AppStore] openAuthorizationInBrowser failed: ${result.exceptionOrNull()?.message}")
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
            if (proxyLifecycle.isRunning()) {
                val proxy = proxyLifecycle.currentProxy()
                if (proxy != null) {
                    try {
                        proxy.refreshServerCapabilities(serverId)
                    } catch (error: Throwable) {
                        logger.info("[AppStore] refreshServerCapabilities failed: ${error.message}")
                    }
                    return@launch
                }
            }
            capabilityRefresher.refreshServersById(setOf(serverId), force = true)
        }
    }

    override fun addOrUpdatePreset(preset: UiPreset) {
        scope.launch {
            val previousPresets = state.snapshot.presets
            val updated = previousPresets.toMutableList()
            val idx = updated.indexOfFirst { it.id == preset.id }
            if (idx >= 0) updated[idx] = preset else updated += preset
            state.updateSnapshot { copy(presets = updated) }
            val result =
                configurationManager.savePreset(
                    UiPresetCore(
                        id = preset.id,
                        name = preset.name,
                        tools = emptyList(),
                        prompts = null,
                        resources = null,
                    ),
                )
            if (result.isFailure) {
                revertPresetsOnFailure(
                    "addOrUpdatePreset",
                    previousPresets,
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
            val basePreset = normalizedDraft.toCorePreset()
            val preset =
                if (basePreset.createdAtEpochMillis == null) {
                    basePreset.copy(createdAtEpochMillis = now())
                } else {
                    basePreset
                }
            val previousSnapshot = state.snapshot
            val previousConfig = state.snapshotConfig()
            val isRename = originalId != null && originalId != preset.id

            val saveResult = configurationManager.savePreset(preset)
            if (saveResult.isFailure) {
                val msg = saveResult.exceptionOrNull()?.message ?: "Failed to save preset"
                logger.info("[AppStore] upsertPreset failed: $msg")
                state.setError(msg)
            }

            val updatedPresets = previousSnapshot.presets.toMutableList()
            val summary = preset.toUiPresetSummary()
            if (isRename) {
                val oldId = originalId ?: ""
                val oldIndex = updatedPresets.indexOfFirst { it.id == oldId }
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
                updatedPresets.removeAll { it.id == oldId }
            } else {
                val idx = updatedPresets.indexOfFirst { it.id == summary.id }
                if (idx >= 0) updatedPresets[idx] = summary else updatedPresets += summary
            }

            var selectedPresetId = previousSnapshot.selectedPresetId
            if (saveResult.isSuccess && isRename) {
                val oldId =
                    originalId ?: run {
                        logger.info("[AppStore] upsertPreset rename skipped: missing originalId")
                        state.updateSnapshot { copy(presets = updatedPresets) }
                        publishReady()
                        return@launch
                    }
                val wasSelected = previousSnapshot.selectedPresetId == originalId
                if (wasSelected) {
                    val configSave = configurationManager.updateDefaultPresetId(previousConfig, preset.id)
                    if (configSave.isFailure) {
                        val msg = configSave.exceptionOrNull()?.message ?: "Failed to update default preset"
                        logger.info("[AppStore] upsertPreset rename failed to update default preset: $msg")
                        state.setError(msg)
                        state.updateSnapshot { copy(presets = updatedPresets) }
                        publishReady()
                        return@launch
                    }
                    selectedPresetId = preset.id
                }

                val deleteResult = configurationManager.deletePreset(oldId)
                if (deleteResult.isFailure) {
                    val msg = deleteResult.exceptionOrNull()?.message ?: "Failed to remove old preset"
                    logger.info("[AppStore] upsertPreset rename failed to delete old preset: $msg")
                    state.setError(msg)
                }
                updatedPresets.removeAll { it.id == oldId }
            }

            state.updateSnapshot { copy(presets = updatedPresets, selectedPresetId = selectedPresetId) }
            val shouldRestart =
                saveResult.isSuccess &&
                    (previousSnapshot.selectedPresetId == preset.id || (isRename && previousSnapshot.selectedPresetId == originalId))
            publishReady()
            if (shouldRestart) proxyRuntime.ensureInboundRunning(forceReloadPreset = true)
        }
    }

    override fun removePreset(id: String) {
        scope.launch {
            val previous = state.snapshot
            val updated = previous.presets.filterNot { it.id == id }
            state.updateSnapshot { withPresets(updated) }
            val result = configurationManager.deletePreset(id)
            if (result.isFailure) {
                revertPresetsOnFailure(
                    "removePreset",
                    previous.presets,
                    result.exceptionOrNull(),
                    "Failed to delete preset",
                )
            }
            publishReady()
            if (previous.selectedPresetId == id) {
                val saveResult = configurationManager.updateDefaultPresetId(state.snapshotConfig(), null)
                if (saveResult.isFailure) {
                    val msg = saveResult.exceptionOrNull()?.message ?: "Failed to clear default preset"
                    logger.info("[AppStore] removePreset failed to clear default preset: $msg")
                }
            }
            proxyRuntime.ensureInboundRunning(forceReloadPreset = true)
        }
    }

    override fun selectProxyPreset(presetId: String?) {
        scope.launch {
            val previousPresetId = state.snapshot.selectedPresetId
            if (previousPresetId == presetId) return@launch
            val previousConfig = state.snapshotConfig()
            state.updateSnapshot { copy(selectedPresetId = presetId) }
            publishReady()
            val saveResult = configurationManager.updateDefaultPresetId(previousConfig, presetId)
            if (saveResult.isFailure) {
                val msg = saveResult.exceptionOrNull()?.message ?: "Failed to save preset selection"
                logger.info("[AppStore] selectProxyPreset failed: $msg")
                state.updateSnapshot { copy(selectedPresetId = previousPresetId) }
                state.setError(msg)
                publishReady()
                return@launch
            }
            proxyRuntime.ensureInboundRunning(forceReloadPreset = true)
        }
    }

    override fun updateInboundSsePort(port: Int) {
        scope.launch {
            val clamped = port.coerceIn(1, 65535)
            val previous = state.snapshot.inboundSsePort
            if (previous == clamped) return@launch
            val previousConfig = state.snapshotConfig()
            state.updateSnapshot { copy(inboundSsePort = clamped) }
            publishReady()
            val result = configurationManager.updateInboundSsePort(previousConfig, clamped)
            if (result.isFailure) {
                val msg = result.exceptionOrNull()?.message ?: "Failed to update HTTP port"
                logger.info("[AppStore] updateInboundSsePort failed: $msg")
                state.updateSnapshot { copy(inboundSsePort = previous) }
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
            proxyLifecycle.updateCallTimeout(state.snapshot.requestTimeoutSeconds)
            val result = configurationManager.updateRequestTimeout(previousConfig, seconds)
            if (result.isFailure) {
                val msg = result.exceptionOrNull()?.message ?: "Failed to update timeout"
                logger.info("[AppStore] updateRequestTimeout failed: $msg")
                state.updateSnapshot { copy(requestTimeoutSeconds = previous) }
                proxyLifecycle.updateCallTimeout(previous)
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
            proxyLifecycle.updateCapabilitiesTimeout(state.snapshot.capabilitiesTimeoutSeconds)
            val result = configurationManager.updateCapabilitiesTimeout(previousConfig, seconds)
            if (result.isFailure) {
                val msg = result.exceptionOrNull()?.message ?: "Failed to update capabilities timeout"
                logger.info("[AppStore] updateCapabilitiesTimeout failed: $msg")
                state.updateSnapshot { copy(capabilitiesTimeoutSeconds = previous) }
                proxyLifecycle.updateCapabilitiesTimeout(previous)
                state.setError(msg)
            }
            publishReady()
        }
    }

    override fun updateConnectionRetryCount(count: Int) {
        scope.launch {
            val clamped = count.coerceAtLeast(1)
            val previous = state.snapshot.connectionRetryCount
            val previousConfig = state.snapshotConfig()
            state.updateSnapshot { copy(connectionRetryCount = clamped) }
            proxyLifecycle.updateConnectionRetryCount(clamped)
            val result = configurationManager.updateConnectionRetryCount(previousConfig, clamped)
            if (result.isFailure) {
                val msg = result.exceptionOrNull()?.message ?: "Failed to update connection retries"
                logger.info("[AppStore] updateConnectionRetryCount failed: $msg")
                state.updateSnapshot { copy(connectionRetryCount = previous) }
                proxyLifecycle.updateConnectionRetryCount(previous)
                state.setError(msg)
            }
            publishReady()
        }
    }

    override fun updateCapabilitiesRefreshInterval(seconds: Int) {
        scope.launch {
            val clamped = seconds.coerceAtLeast(30)
            if (state.snapshot.capabilitiesRefreshIntervalSeconds == clamped) return@launch
            val previous = state.snapshot.capabilitiesRefreshIntervalSeconds
            val previousConfig = state.snapshotConfig()
            state.updateSnapshot { copy(capabilitiesRefreshIntervalSeconds = clamped) }
            val result = configurationManager.updateRefreshInterval(previousConfig, clamped)
            if (result.isFailure) {
                val msg = result.exceptionOrNull()?.message ?: "Failed to update refresh interval"
                logger.info("[AppStore] updateCapabilitiesRefreshInterval failed: $msg")
                state.updateSnapshot { copy(capabilitiesRefreshIntervalSeconds = previous) }
                state.setError(msg)
            } else {
                if (proxyLifecycle.isRunning()) {
                    restartRefreshJob(false)
                } else {
                    restartRefreshJob(true)
                    refreshEnabledCaps(true)
                }
            }
            publishReady()
        }
    }

    override fun updateTrayIconVisibility(visible: Boolean) {
        scope.launch {
            if (state.snapshot.showTrayIcon == visible) return@launch
            val previous = state.snapshot.showTrayIcon
            val previousConfig = state.snapshotConfig()
            state.updateSnapshot { copy(showTrayIcon = visible) }
            val result = configurationManager.updateTrayIconVisibility(previousConfig, visible)
            if (result.isFailure) {
                val msg = result.exceptionOrNull()?.message ?: "Failed to update tray preference"
                logger.info("[AppStore] updateTrayIconVisibility failed: $msg")
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
            proxyLifecycle.updateFallbackPromptsAndResourcesToTools(enabled)
            val result = configurationManager.updateFallbackPromptsAndResourcesToTools(previousConfig, enabled)
            if (result.isFailure) {
                val msg = result.exceptionOrNull()?.message ?: "Failed to update prompt/resource tool fallback"
                logger.info("[AppStore] updateFallbackPromptsAndResourcesToTools failed: $msg")
                state.updateSnapshot { copy(fallbackPromptsAndResourcesToTools = previous) }
                proxyLifecycle.updateFallbackPromptsAndResourcesToTools(previous)
                state.setError(msg)
            }
            publishReady()
        }
    }

    override fun openLogsFolder() {
        scope.launch {
            val result = openLogsFolderPlatform()
            if (result.isFailure) {
                logger.info("[AppStore] openLogsFolder failed: ${result.exceptionOrNull()?.message}")
            } else {
                logger.info("[AppStore] logs folder opened")
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

    private fun revertServersOnFailure(
        operation: String,
        previousServers: List<UiMcpServerConfig>,
        failure: Throwable?,
        defaultMessage: String,
        previousPendingToggles: Set<String>? = null,
    ) {
        val message = failure?.message ?: defaultMessage
        logger.info("[AppStore] $operation failed: $message")
        state.updateSnapshot {
            copy(
                servers = previousServers,
                pendingServerToggles = previousPendingToggles ?: pendingServerToggles,
            )
        }
        state.setError(message)
    }

    private fun revertPresetsOnFailure(
        operation: String,
        previousPresets: List<UiPreset>,
        failure: Throwable?,
        defaultMessage: String,
    ) {
        val message = failure?.message ?: defaultMessage
        logger.info("[AppStore] $operation failed: $message")
        state.updateSnapshot { copy(presets = previousPresets) }
        state.setError(message)
    }

    private fun triggerServerRefresh(
        ids: Set<String>,
        force: Boolean,
    ) {
        if (ids.isEmpty()) return
        if (proxyLifecycle.isRunning()) return
        scope.launch { capabilityRefresher.refreshServersById(ids, force) }
    }

    private suspend fun applyServerConfigToProxy(
        config: McpServersConfig?,
        operation: String,
    ) {
        if (config == null) return
        if (proxyLifecycle.isRunning()) {
            val updateResult = proxyLifecycle.updateServers(config)
            if (updateResult.isFailure) {
                logger.info("[AppStore] $operation updateServers failed: ${updateResult.exceptionOrNull()?.message}")
            }
            return
        }
        proxyRuntime.ensureInboundRunning()
    }
}
