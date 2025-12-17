package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.proxy.runtime.ProxyLifecycle
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.ui.adapter.models.UiHttpTransport
import io.qent.broxy.ui.adapter.models.UiPresetCore
import io.qent.broxy.ui.adapter.models.UiProxyStatus
import io.qent.broxy.ui.adapter.models.UiTransportConfig
import io.qent.broxy.ui.adapter.remote.RemoteConnector

internal class ProxyRuntime(
    private val configurationRepository: ConfigurationRepository,
    private val proxyLifecycle: ProxyLifecycle,
    private val logger: CollectingLogger,
    private val state: StoreStateAccess,
    private val publishReady: () -> Unit,
    private val remoteConnector: RemoteConnector
) {
    suspend fun ensureSseRunning(forceRestart: Boolean = false) {
        val port = state.snapshot.inboundSsePort.coerceIn(1, 65535)
        val inbound = UiHttpTransport(url = sseEndpointUrl(port))
        val presetId = state.snapshot.selectedPresetId

        val shouldStartOrRestart = forceRestart ||
            state.snapshot.proxyStatus !is UiProxyStatus.Running ||
            state.snapshot.activeInbound != inbound ||
            state.snapshot.activeProxyPresetId != presetId

        if (!shouldStartOrRestart) return

        state.updateSnapshot { copy(proxyStatus = UiProxyStatus.Starting) }
        publishReady()

        val preset = loadPresetOrEmpty(presetId)
        if (preset.isFailure) {
            val msg = preset.exceptionOrNull()?.message ?: "Failed to load preset"
            logger.info("[AppStore] ensureSseRunning failed: $msg")
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
            val msg = result.exceptionOrNull()?.message ?: "Failed to start SSE server"
            logger.info("[AppStore] ensureSseRunning start failed: $msg")
            state.updateSnapshot { copy(proxyStatus = UiProxyStatus.Error(msg), activeProxyPresetId = null, activeInbound = null) }
            remoteConnector.onProxyRunningChanged(false)
        }
        publishReady()
    }

    fun stopSse(): Result<Unit> {
        state.updateSnapshot { copy(proxyStatus = UiProxyStatus.Stopping) }
        publishReady()
        val result = proxyLifecycle.stop()
        if (result.isSuccess) {
            state.updateSnapshot { copy(proxyStatus = UiProxyStatus.Stopped, activeProxyPresetId = null, activeInbound = null) }
            remoteConnector.onProxyRunningChanged(false)
        } else {
            val msg = result.exceptionOrNull()?.message ?: "Failed to stop SSE server"
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

private fun sseEndpointUrl(port: Int): String = "http://0.0.0.0:${port.coerceIn(1, 65535)}/mcp"

