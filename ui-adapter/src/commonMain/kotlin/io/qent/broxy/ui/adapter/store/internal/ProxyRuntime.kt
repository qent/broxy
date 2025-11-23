package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.core.proxy.runtime.ProxyLifecycle
import io.qent.broxy.ui.adapter.models.UiHttpTransport
import io.qent.broxy.ui.adapter.models.UiPresetCore
import io.qent.broxy.ui.adapter.models.UiProxyStatus
import io.qent.broxy.ui.adapter.models.UiTransportConfig
import io.qent.broxy.ui.adapter.remote.RemoteConnector

private const val DEFAULT_REMOTE_ENDPOINT = "http://0.0.0.0:3335/mcp"

internal class ProxyRuntime(
    private val configurationRepository: ConfigurationRepository,
    private val proxyLifecycle: ProxyLifecycle,
    private val logger: CollectingLogger,
    private val state: StoreStateAccess,
    private val publishReady: () -> Unit,
    private val remoteConnector: RemoteConnector
) {
    suspend fun restartProxyWithPreset(presetId: String, presetOverride: UiPresetCore? = null) {
        if (state.snapshot.proxyStatus !is UiProxyStatus.Running) return
        val inbound = state.snapshot.activeInbound ?: return
        val presetResult = presetOverride?.let { Result.success(it) }
            ?: runCatching { configurationRepository.loadPreset(presetId) }
        if (presetResult.isFailure) {
            val msg = presetResult.exceptionOrNull()?.message ?: "Failed to load preset for restart"
            logger.info("[AppStore] restartProxyWithPreset failed to load preset '$presetId': $msg")
            state.updateSnapshot {
                copy(proxyStatus = UiProxyStatus.Error(msg), activeProxyPresetId = null, activeInbound = null)
            }
            publishReady()
            return
        }
        state.updateSnapshot { copy(proxyStatus = UiProxyStatus.Starting) }
        publishReady()
        val result = proxyLifecycle.start(snapshotConfig(), presetResult.getOrThrow(), inbound)
        if (result.isSuccess) {
            state.updateSnapshot { copy(activeProxyPresetId = presetId, proxyStatus = UiProxyStatus.Running) }
            remoteConnector.onProxyRunningChanged(true)
        } else {
            val msg = result.exceptionOrNull()?.message ?: "Failed to restart proxy"
            logger.info("[AppStore] restartProxyWithPreset failed for '$presetId': $msg")
            state.updateSnapshot {
                copy(proxyStatus = UiProxyStatus.Error(msg), activeProxyPresetId = null, activeInbound = null)
            }
        }
        publishReady()
    }

    suspend fun startProxySimple(presetId: String) {
        startProxy(presetId, UiHttpTransport(DEFAULT_REMOTE_ENDPOINT))
    }

    suspend fun startProxy(presetId: String, inbound: UiTransportConfig) {
        val presetResult = runCatching { configurationRepository.loadPreset(presetId) }
        if (presetResult.isFailure) {
            val msg = presetResult.exceptionOrNull()?.message ?: "Failed to load preset"
            logger.info("[AppStore] startProxy failed: $msg")
            state.updateSnapshot {
                copy(
                    proxyStatus = UiProxyStatus.Error(msg),
                    selectedPresetId = presetId,
                    activeProxyPresetId = null,
                    activeInbound = null
                )
            }
            publishReady()
            return
        }
        state.updateSnapshot { copy(proxyStatus = UiProxyStatus.Starting, selectedPresetId = presetId) }
        publishReady()
        val result = proxyLifecycle.start(snapshotConfig(), presetResult.getOrThrow(), inbound)
        if (result.isSuccess) {
            state.updateSnapshot {
                copy(proxyStatus = UiProxyStatus.Running, activeProxyPresetId = presetId, activeInbound = inbound)
            }
            remoteConnector.onProxyRunningChanged(true)
        } else {
            val msg = result.exceptionOrNull()?.message ?: "Failed to start proxy"
            logger.info("[AppStore] startProxy failed to start proxy: $msg")
            state.updateSnapshot {
                copy(proxyStatus = UiProxyStatus.Error(msg), activeProxyPresetId = null, activeInbound = null)
            }
        }
        publishReady()
    }

    suspend fun stopProxy() {
        state.updateSnapshot { copy(proxyStatus = UiProxyStatus.Stopping) }
        publishReady()
        val result = proxyLifecycle.stop()
        if (result.isSuccess) {
            state.updateSnapshot {
                copy(proxyStatus = UiProxyStatus.Stopped, activeProxyPresetId = null, activeInbound = null)
            }
            remoteConnector.onProxyRunningChanged(false)
        } else {
            val msg = result.exceptionOrNull()?.message ?: "Failed to stop proxy"
            state.updateSnapshot { copy(proxyStatus = UiProxyStatus.Error(msg)) }
        }
        publishReady()
    }
    private fun snapshotConfig(): McpServersConfig = state.snapshotConfig()
}
