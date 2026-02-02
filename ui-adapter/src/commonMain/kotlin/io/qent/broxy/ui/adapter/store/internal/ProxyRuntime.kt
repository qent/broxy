package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.core.proxy.runtime.ProxyRuntimeFacade
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.ui.adapter.models.UiPresetCore
import io.qent.broxy.ui.adapter.models.UiProxyStatus
import io.qent.broxy.ui.adapter.models.UiStreamableHttpTransport
import io.qent.broxy.ui.adapter.models.toCore
import io.qent.broxy.ui.adapter.models.toUi
import io.qent.broxy.ui.adapter.remote.RemoteConnector
import io.qent.broxy.ui.adapter.remote.RemotePresetChange

internal class ProxyRuntime(
    private val configurationRepository: ConfigurationRepository,
    private val proxyRuntime: ProxyRuntimeFacade,
    private val logger: CollectingLogger,
    private val state: StoreStateAccess,
    private val publishReady: () -> Unit,
    private val remoteConnector: RemoteConnector,
    private val onProxyStatusChanged: () -> Unit,
) {
    suspend fun ensureInboundRunning(
        forceRestart: Boolean = false,
        forceReloadPreset: Boolean = false,
    ) {
        val inbound = UiStreamableHttpTransport(url = httpEndpointFor(state.snapshot.inboundHttpPort))
        val presetId = state.snapshot.selectedPresetId

        val isRunning = state.snapshot.proxyStatus is UiProxyStatus.Running
        val inboundChanged = state.snapshot.activeInbound != inbound
        val presetChanged = state.snapshot.activeProxyPresetId != presetId

        val shouldStartOrRestart = forceRestart || !isRunning || inboundChanged

        if (shouldStartOrRestart) {
            state.updateSnapshot { copy(proxyStatus = UiProxyStatus.Starting) }
            onProxyStatusChanged()
            publishReady()

            val preset = loadPresetOrEmpty(presetId)
            if (preset.isFailure) {
                val msg =
                    logFailure(
                        logger,
                        "ensureInboundRunning(loadPreset,id=$presetId)",
                        preset.exceptionOrNull(),
                        "Failed to load preset",
                    )
                state.updateSnapshot {
                    copy(proxyStatus = UiProxyStatus.Error(msg), activeProxyPresetId = null, activeInbound = null)
                }
                onProxyStatusChanged()
                remoteConnector.onProxyRunningChanged(false)
                publishReady()
                return
            }

            val result = proxyRuntime.start(state.snapshotConfig().toCore(), preset.getOrThrow().toCore(), inbound.toCore())
            if (result.isSuccess) {
                state.updateSnapshot {
                    copy(
                        proxyStatus = UiProxyStatus.Running,
                        activeProxyPresetId = presetId,
                        activeInbound = inbound,
                    )
                }
                onProxyStatusChanged()
                remoteConnector.onProxyRunningChanged(true)
            } else {
                val msg = logFailure(logger, "ensureInboundRunning(start)", result.exceptionOrNull(), "Failed to start HTTP server")
                state.updateSnapshot {
                    copy(
                        proxyStatus = UiProxyStatus.Error(msg),
                        activeProxyPresetId = null,
                        activeInbound = null,
                    )
                }
                onProxyStatusChanged()
                remoteConnector.onProxyRunningChanged(false)
            }
            publishReady()
            return
        }

        if (!isRunning || (!presetChanged && !forceReloadPreset)) return

        val preset = loadPresetOrEmpty(presetId)
        if (preset.isFailure) {
            val msg =
                logFailure(
                    logger,
                    "ensureInboundRunning(applyPreset,id=$presetId)",
                    preset.exceptionOrNull(),
                    "Failed to load preset",
                )
            state.setError(msg)
            publishReady()
            return
        }

        val applyResult = proxyRuntime.applyPreset(preset.getOrThrow().toCore())
        if (applyResult.isSuccess) {
            state.updateSnapshot { copy(activeProxyPresetId = presetId) }
            if (!state.snapshot.adapterMode) {
                val changeType =
                    if (presetChanged) {
                        RemotePresetChange.SELECTION
                    } else {
                        RemotePresetChange.COMPOSITION
                    }
                remoteConnector.notifyPresetChanged(presetId, changeType)
            }
        } else {
            val msg =
                logFailure(
                    logger,
                    "ensureInboundRunning(applyPreset,id=$presetId)",
                    applyResult.exceptionOrNull(),
                    "Failed to apply preset",
                )
            state.setError(msg)
        }
        publishReady()
    }

    fun stopInbound(): Result<Unit> {
        state.updateSnapshot { copy(proxyStatus = UiProxyStatus.Stopping) }
        onProxyStatusChanged()
        publishReady()
        val result = proxyRuntime.stop()
        if (result.isSuccess) {
            state.updateSnapshot {
                copy(
                    proxyStatus = UiProxyStatus.Stopped,
                    activeProxyPresetId = null,
                    activeInbound = null,
                )
            }
            onProxyStatusChanged()
            remoteConnector.onProxyRunningChanged(false)
        } else {
            val msg = logFailure(logger, "stopInbound", result.exceptionOrNull(), "Failed to stop HTTP server")
            state.updateSnapshot { copy(proxyStatus = UiProxyStatus.Error(msg)) }
            onProxyStatusChanged()
        }
        publishReady()
        return result
    }

    private suspend fun loadPresetOrEmpty(presetId: String?): Result<UiPresetCore> {
        if (presetId.isNullOrBlank() || presetId == UiPresetCore.EMPTY_PRESET_ID) {
            return Result.success(UiPresetCore.empty())
        }
        if (presetId == UiPresetCore.ALL_ENABLED_PRESET_ID) {
            return Result.success(UiPresetCore.allEnabled())
        }
        return runCatching { configurationRepository.loadPreset(presetId) }
            .map { it.toUi() }
            .recoverCatching {
                logInfo(logger, "loadPresetOrEmpty(id=$presetId)", "preset not found; starting with empty capabilities")
                UiPresetCore.empty()
            }
    }
}
