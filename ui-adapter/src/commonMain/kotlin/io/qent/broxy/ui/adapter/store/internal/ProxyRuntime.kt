package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.proxy.runtime.ProxyLifecycle
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.ui.adapter.models.UiPresetCore
import io.qent.broxy.ui.adapter.models.UiProxyStatus
import io.qent.broxy.ui.adapter.models.UiStreamableHttpTransport
import io.qent.broxy.ui.adapter.remote.RemoteConnector

internal class ProxyRuntime(
    private val configurationRepository: ConfigurationRepository,
    private val proxyLifecycle: ProxyLifecycle,
    private val logger: CollectingLogger,
    private val state: StoreStateAccess,
    private val publishReady: () -> Unit,
    private val remoteConnector: RemoteConnector
) {
    suspend fun ensureInboundRunning(forceRestart: Boolean = false, forceReloadPreset: Boolean = false) {
        val port = state.snapshot.inboundSsePort.coerceIn(1, 65535)
        val inbound = UiStreamableHttpTransport(url = inboundEndpointUrl(port))
        val presetId = state.snapshot.selectedPresetId

        val isRunning = state.snapshot.proxyStatus is UiProxyStatus.Running
        val inboundChanged = state.snapshot.activeInbound != inbound
        val presetChanged = state.snapshot.activeProxyPresetId != presetId

        val shouldStartOrRestart = forceRestart || !isRunning || inboundChanged

        if (shouldStartOrRestart) {
            state.updateSnapshot { copy(proxyStatus = UiProxyStatus.Starting) }
            publishReady()

            val preset = loadPresetOrEmpty(presetId)
            if (preset.isFailure) {
                val msg = preset.exceptionOrNull()?.message ?: "Failed to load preset"
                logger.info("[AppStore] ensureInboundRunning failed: $msg")
                state.updateSnapshot {
                    copy(proxyStatus = UiProxyStatus.Error(msg), activeProxyPresetId = null, activeInbound = null)
                }
                remoteConnector.onProxyRunningChanged(false)
                publishReady()
                return
            }

            val result = proxyLifecycle.start(state.snapshotConfig(), preset.getOrThrow(), inbound)
            if (result.isSuccess) {
                state.updateSnapshot {
                    copy(
                        proxyStatus = UiProxyStatus.Running,
                        activeProxyPresetId = presetId,
                        activeInbound = inbound
                    )
                }
                remoteConnector.onProxyRunningChanged(true)
            } else {
                val msg = result.exceptionOrNull()?.message ?: "Failed to start HTTP server"
                logger.info("[AppStore] ensureInboundRunning start failed: $msg")
                state.updateSnapshot { copy(proxyStatus = UiProxyStatus.Error(msg), activeProxyPresetId = null, activeInbound = null) }
                remoteConnector.onProxyRunningChanged(false)
            }
            publishReady()
            return
        }

        if (!isRunning || (!presetChanged && !forceReloadPreset)) return

        val preset = loadPresetOrEmpty(presetId)
        if (preset.isFailure) {
            val msg = preset.exceptionOrNull()?.message ?: "Failed to load preset"
            logger.info("[AppStore] ensureInboundRunning applyPreset failed: $msg")
            state.setError(msg)
            publishReady()
            return
        }

        val applyResult = proxyLifecycle.applyPreset(preset.getOrThrow())
        if (applyResult.isSuccess) {
            state.updateSnapshot { copy(activeProxyPresetId = presetId) }
        } else {
            val msg = applyResult.exceptionOrNull()?.message ?: "Failed to apply preset"
            logger.info("[AppStore] ensureInboundRunning applyPreset failed: $msg")
            state.setError(msg)
        }
        publishReady()
    }

    fun stopInbound(): Result<Unit> {
        state.updateSnapshot { copy(proxyStatus = UiProxyStatus.Stopping) }
        publishReady()
        val result = proxyLifecycle.stop()
        if (result.isSuccess) {
            state.updateSnapshot { copy(proxyStatus = UiProxyStatus.Stopped, activeProxyPresetId = null, activeInbound = null) }
            remoteConnector.onProxyRunningChanged(false)
        } else {
            val msg = result.exceptionOrNull()?.message ?: "Failed to stop HTTP server"
            state.updateSnapshot { copy(proxyStatus = UiProxyStatus.Error(msg)) }
        }
        publishReady()
        return result
    }

    private suspend fun loadPresetOrEmpty(presetId: String?): Result<UiPresetCore> {
        if (presetId.isNullOrBlank()) {
            return Result.success(Preset.empty())
        }
        return runCatching { configurationRepository.loadPreset(presetId) }
            .recoverCatching {
                logger.info("[AppStore] preset '$presetId' not found; starting with empty capabilities")
                Preset.empty()
            }
    }
}

private fun inboundEndpointUrl(port: Int): String = "http://localhost:${port.coerceIn(1, 65535)}/mcp"
