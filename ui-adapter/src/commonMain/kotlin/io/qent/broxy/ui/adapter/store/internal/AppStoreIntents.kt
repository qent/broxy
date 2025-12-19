package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.core.capabilities.CapabilityRefresher
import io.qent.broxy.core.config.ConfigurationManager
import io.qent.broxy.core.proxy.runtime.ProxyLifecycle
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.ui.adapter.models.*
import io.qent.broxy.ui.adapter.remote.RemoteConnector
import io.qent.broxy.ui.adapter.store.Intents
import io.qent.broxy.ui.adapter.store.toCorePreset
import io.qent.broxy.ui.adapter.store.toTransportConfig
import io.qent.broxy.ui.adapter.store.toUiPresetSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import io.qent.broxy.ui.adapter.data.openLogsFolder as openLogsFolderPlatform

internal class AppStoreIntents(
    private val scope: CoroutineScope,
    private val logger: CollectingLogger,
    private val configurationManager: ConfigurationManager,
    private val configurationRepository: ConfigurationRepository,
    private val state: StoreStateAccess,
    private val capabilityRefresher: CapabilityRefresher,
    private val proxyRuntime: ProxyRuntime,
    private val proxyLifecycle: ProxyLifecycle,
    private val loadConfiguration: suspend () -> Result<Unit>,
    private val refreshEnabledCaps: suspend (Boolean) -> Unit,
    private val restartRefreshJob: () -> Unit,
    private val publishReady: () -> Unit,
    private val remoteConnector: RemoteConnector
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
            refreshEnabledCaps(true)
            restartRefreshJob()
        }
    }

    override fun addOrUpdateServerUi(ui: UiServer) {
        scope.launch {
            val previousServers = state.snapshot.servers
            val previousConfig = state.snapshotConfig()
            val updated = previousServers.toMutableList()
            val idx = updated.indexOfFirst { it.id == ui.id }
            val base = updated.getOrNull(idx) ?: UiMcpServerConfig(
                id = ui.id,
                name = ui.name,
                transport = UiStdioTransport(command = ""),
                enabled = ui.enabled
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
                    "Failed to save servers"
                )
            } else {
                capabilityRefresher.updateCachedName(ui.id, ui.name)
                val saved = result.getOrNull()
                capabilityRefresher.syncWithServers(saved?.servers ?: updated)
                triggerServerRefresh(setOf(ui.id), force = true)
                proxyRuntime.ensureInboundRunning(forceRestart = true)
            }
            publishReady()
        }
    }

    override fun addServerBasic(id: String, name: String) {
        scope.launch {
            val previousServers = state.snapshot.servers
            val previousConfig = state.snapshotConfig()
            if (previousServers.any { it.id == id }) return@launch
            val updated = previousServers.toMutableList().apply {
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
                    "Failed to save servers"
                )
            } else {
                capabilityRefresher.syncWithServers(result.getOrNull()?.servers ?: updated)
                proxyRuntime.ensureInboundRunning(forceRestart = true)
            }
            publishReady()
        }
    }

    override fun upsertServer(draft: UiServerDraft) {
        scope.launch {
            val originalId = draft.originalId?.trim()?.takeIf { it.isNotBlank() }
            val trimmedId = draft.id.trim()
            val normalizedDraft = if (trimmedId == draft.id && originalId == draft.originalId) {
                draft
            } else {
                draft.copy(id = trimmedId, originalId = originalId)
            }

            val previousServers = state.snapshot.servers
            val previousConfig = state.snapshotConfig()
            val updated = previousServers.toMutableList()
            val oldId = originalId?.takeIf { it != normalizedDraft.id }
            val isRename = oldId != null
            if (isRename) {
                updated.removeAll { it.id == oldId }
            }
            val cfg = UiMcpServerConfig(
                id = normalizedDraft.id,
                name = normalizedDraft.name,
                enabled = normalizedDraft.enabled,
                transport = normalizedDraft.transport.toTransportConfig(),
                env = normalizedDraft.env
            )
            val idx = updated.indexOfFirst { it.id == cfg.id }
            if (idx >= 0) updated[idx] = cfg else updated += cfg
            state.updateSnapshot { copy(servers = updated) }

            val result = if (isRename) {
                configurationManager.renameServer(previousConfig, oldId = oldId!!, server = cfg)
            } else {
                configurationManager.upsertServer(previousConfig, cfg)
            }

            if (result.isFailure) {
                revertServersOnFailure(
                    "upsertServer",
                    previousServers,
                    result.exceptionOrNull(),
                    "Failed to save server"
                )
            } else {
                capabilityRefresher.syncWithServers(result.getOrNull()?.servers ?: updated)
                if (isRename) {
                    capabilityRefresher.markServerRemoved(oldId!!)
                    migratePresetsServerId(oldId = oldId, newId = cfg.id)
                        .onFailure { error ->
                            val msg = error.message ?: "Failed to update presets after server rename"
                            logger.info("[AppStore] upsertServer rename preset migration failed: $msg")
                            state.setError(msg)
                        }
                }
                triggerServerRefresh(setOf(cfg.id), force = true)
                proxyRuntime.ensureInboundRunning(forceRestart = true)
            }
            publishReady()
        }
    }

    override fun removeServer(id: String) {
        scope.launch {
            val previousServers = state.snapshot.servers
            val previousConfig = state.snapshotConfig()
            val updated = previousServers.filterNot { it.id == id }
            state.updateSnapshot { copy(servers = updated) }
            val result = configurationManager.removeServer(previousConfig, id)
            if (result.isFailure) {
                revertServersOnFailure(
                    "removeServer",
                    previousServers,
                    result.exceptionOrNull(),
                    "Failed to save servers"
                )
            } else {
                capabilityRefresher.syncWithServers(result.getOrNull()?.servers ?: updated)
                capabilityRefresher.markServerRemoved(id)
                proxyRuntime.ensureInboundRunning(forceRestart = true)
            }
            publishReady()
        }
    }

    override fun toggleServer(id: String, enabled: Boolean) {
        scope.launch {
            val previousServers = state.snapshot.servers
            val previousConfig = state.snapshotConfig()
            val idx = previousServers.indexOfFirst { it.id == id }
            if (idx < 0) return@launch
            val updated = previousServers.toMutableList()
            updated[idx] = updated[idx].copy(enabled = enabled)
            state.updateSnapshot { copy(servers = updated) }
            val result = configurationManager.toggleServer(previousConfig, id, enabled)
            if (result.isFailure) {
                revertServersOnFailure(
                    "toggleServer",
                    previousServers,
                    result.exceptionOrNull(),
                    "Failed to save server state"
                )
            } else {
                val savedConfig = result.getOrNull()
                if (!enabled) {
                    capabilityRefresher.markServerDisabled(id)
                } else {
                    triggerServerRefresh(setOf(id), force = true)
                }
                if (savedConfig != null && proxyLifecycle.isRunning()) {
                    val updateResult = proxyLifecycle.updateServers(savedConfig)
                    if (updateResult.isFailure) {
                        logger.info("[AppStore] toggleServer updateServers failed: ${updateResult.exceptionOrNull()?.message}")
                    }
                }
            }
            publishReady()
        }
    }

    override fun addOrUpdatePreset(preset: UiPreset) {
        scope.launch {
            val previousPresets = state.snapshot.presets
            val updated = previousPresets.toMutableList()
            val idx = updated.indexOfFirst { it.id == preset.id }
            if (idx >= 0) updated[idx] = preset else updated += preset
            state.updateSnapshot { copy(presets = updated) }
            val result = configurationManager.savePreset(
                UiPresetCore(
                    id = preset.id,
                    name = preset.name,
                    tools = emptyList(),
                    prompts = null,
                    resources = null
                )
            )
            if (result.isFailure) {
                revertPresetsOnFailure(
                    "addOrUpdatePreset",
                    previousPresets,
                    result.exceptionOrNull(),
                    "Failed to save preset"
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
            val preset = normalizedDraft.toCorePreset()
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
            val idx = updatedPresets.indexOfFirst { it.id == summary.id }
            if (idx >= 0) updatedPresets[idx] = summary else updatedPresets += summary

            var selectedPresetId = previousSnapshot.selectedPresetId
            if (saveResult.isSuccess && isRename) {
                val oldId = originalId ?: run {
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
            val shouldRestart = saveResult.isSuccess &&
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
                    "Failed to delete preset"
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
                restartRefreshJob()
                refreshEnabledCaps(true)
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

    override fun updateRemoteServerIdentifier(value: String) {
        remoteConnector.updateServerIdentifier(value)
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
        defaultMessage: String
    ) {
        val message = failure?.message ?: defaultMessage
        logger.info("[AppStore] $operation failed: $message")
        state.updateSnapshot { copy(servers = previousServers) }
        state.setError(message)
    }

    private fun revertPresetsOnFailure(
        operation: String,
        previousPresets: List<UiPreset>,
        failure: Throwable?,
        defaultMessage: String
    ) {
        val message = failure?.message ?: defaultMessage
        logger.info("[AppStore] $operation failed: $message")
        state.updateSnapshot { copy(presets = previousPresets) }
        state.setError(message)
    }

    private fun triggerServerRefresh(ids: Set<String>, force: Boolean) {
        if (ids.isEmpty()) return
        scope.launch { capabilityRefresher.refreshServersById(ids, force) }
    }

    private suspend fun migratePresetsServerId(oldId: String, newId: String): Result<Unit> = runCatching {
        if (oldId == newId) return@runCatching
        val presets = configurationRepository.listPresets()
        presets.forEach { preset ->
            val updated = preset.copy(
                tools = preset.tools.map { ref ->
                    if (ref.serverId == oldId) ref.copy(serverId = newId) else ref
                },
                prompts = preset.prompts?.map { ref ->
                    if (ref.serverId == oldId) ref.copy(serverId = newId) else ref
                },
                resources = preset.resources?.map { ref ->
                    if (ref.serverId == oldId) ref.copy(serverId = newId) else ref
                }
            )
            if (updated != preset) {
                configurationRepository.savePreset(updated)
            }
        }
    }
}
