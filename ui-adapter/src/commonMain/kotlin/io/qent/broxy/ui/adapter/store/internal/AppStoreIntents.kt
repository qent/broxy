package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.core.capabilities.CapabilityRefresher
import io.qent.broxy.core.config.ConfigurationManager
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.ui.adapter.models.UiMcpServerConfig
import io.qent.broxy.ui.adapter.models.UiPreset
import io.qent.broxy.ui.adapter.models.UiPresetCore
import io.qent.broxy.ui.adapter.models.UiPresetDraft
import io.qent.broxy.ui.adapter.models.UiServer
import io.qent.broxy.ui.adapter.models.UiServerDraft
import io.qent.broxy.ui.adapter.models.UiStdioTransport
import io.qent.broxy.ui.adapter.models.UiTransportDraft
import io.qent.broxy.core.proxy.runtime.ProxyLifecycle
import io.qent.broxy.ui.adapter.store.Intents
import io.qent.broxy.ui.adapter.store.toCorePreset
import io.qent.broxy.ui.adapter.store.toTransportConfig
import io.qent.broxy.ui.adapter.store.toUiPresetSummary
import io.qent.broxy.ui.adapter.remote.RemoteConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
            proxyRuntime.ensureSseRunning(forceRestart = true)
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
                revertServersOnFailure("addOrUpdateServerUi", previousServers, result.exceptionOrNull(), "Failed to save servers")
            } else {
                capabilityRefresher.updateCachedName(ui.id, ui.name)
                val saved = result.getOrNull()
                capabilityRefresher.syncWithServers(saved?.servers ?: updated)
                triggerServerRefresh(setOf(ui.id), force = true)
                proxyRuntime.ensureSseRunning(forceRestart = true)
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
                revertServersOnFailure("addServerBasic", previousServers, result.exceptionOrNull(), "Failed to save servers")
            } else {
                capabilityRefresher.syncWithServers(result.getOrNull()?.servers ?: updated)
                proxyRuntime.ensureSseRunning(forceRestart = true)
            }
            publishReady()
        }
    }

    override fun upsertServer(draft: UiServerDraft) {
        scope.launch {
            val previousServers = state.snapshot.servers
            val previousConfig = state.snapshotConfig()
            val updated = previousServers.toMutableList()
            val idx = updated.indexOfFirst { it.id == draft.id }
            val cfg = UiMcpServerConfig(
                id = draft.id,
                name = draft.name,
                enabled = draft.enabled,
                transport = draft.transport.toTransportConfig(),
                env = draft.env
            )
            if (idx >= 0) updated[idx] = cfg else updated += cfg
            state.updateSnapshot { copy(servers = updated) }
            val result = configurationManager.upsertServer(previousConfig, cfg)
            if (result.isFailure) {
                revertServersOnFailure("upsertServer", previousServers, result.exceptionOrNull(), "Failed to save server")
            } else {
                capabilityRefresher.syncWithServers(result.getOrNull()?.servers ?: updated)
                triggerServerRefresh(setOf(draft.id), force = true)
                proxyRuntime.ensureSseRunning(forceRestart = true)
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
                revertServersOnFailure("removeServer", previousServers, result.exceptionOrNull(), "Failed to save servers")
            } else {
                capabilityRefresher.syncWithServers(result.getOrNull()?.servers ?: updated)
                capabilityRefresher.markServerRemoved(id)
                proxyRuntime.ensureSseRunning(forceRestart = true)
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
                revertServersOnFailure("toggleServer", previousServers, result.exceptionOrNull(), "Failed to save server state")
            } else {
                if (!enabled) {
                    capabilityRefresher.markServerDisabled(id)
                } else {
                    triggerServerRefresh(setOf(id), force = true)
                }
                proxyRuntime.ensureSseRunning(forceRestart = true)
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
                    description = preset.description ?: "",
                    tools = emptyList(),
                    prompts = null,
                    resources = null
                )
            )
            if (result.isFailure) {
                revertPresetsOnFailure("addOrUpdatePreset", previousPresets, result.exceptionOrNull(), "Failed to save preset")
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
            val summary = preset.toUiPresetSummary(normalizedDraft.description)
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
            if (shouldRestart) proxyRuntime.ensureSseRunning(forceReloadPreset = true)
        }
    }

    override fun removePreset(id: String) {
        scope.launch {
            val previous = state.snapshot
            val updated = previous.presets.filterNot { it.id == id }
            state.updateSnapshot { withPresets(updated) }
            val result = configurationManager.deletePreset(id)
            if (result.isFailure) {
                revertPresetsOnFailure("removePreset", previous.presets, result.exceptionOrNull(), "Failed to delete preset")
            }
            publishReady()
            if (previous.selectedPresetId == id) {
                val saveResult = configurationManager.updateDefaultPresetId(state.snapshotConfig(), null)
                if (saveResult.isFailure) {
                    val msg = saveResult.exceptionOrNull()?.message ?: "Failed to clear default preset"
                    logger.info("[AppStore] removePreset failed to clear default preset: $msg")
                }
            }
            proxyRuntime.ensureSseRunning(forceReloadPreset = true)
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
            proxyRuntime.ensureSseRunning(forceReloadPreset = true)
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
                val msg = result.exceptionOrNull()?.message ?: "Failed to update SSE port"
                logger.info("[AppStore] updateInboundSsePort failed: $msg")
                state.updateSnapshot { copy(inboundSsePort = previous) }
                state.setError(msg)
                publishReady()
                return@launch
            }
            proxyRuntime.ensureSseRunning(forceRestart = true)
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
}
