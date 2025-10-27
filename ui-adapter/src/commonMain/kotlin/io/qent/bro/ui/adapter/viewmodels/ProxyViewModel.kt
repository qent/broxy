package io.qent.bro.ui.adapter.viewmodels

import io.qent.bro.ui.adapter.data.provideConfigurationRepository
import io.qent.bro.ui.adapter.models.UiMcpServerConfig
import io.qent.bro.ui.adapter.models.UiPresetCore
import io.qent.bro.ui.adapter.models.UiTransportConfig
import io.qent.bro.ui.adapter.proxy.ProxyController
import io.qent.bro.ui.adapter.proxy.createProxyController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed class ProxyStatus {
    data object Running : ProxyStatus()
    data object Stopped : ProxyStatus()
    data class Error(val message: String) : ProxyStatus()
}

class ProxyViewModel(
    private val controller: ProxyController = createProxyController()
) {
    private val repo = provideConfigurationRepository()

    private val _status: MutableStateFlow<ProxyStatus> = MutableStateFlow(ProxyStatus.Stopped)
    val status: StateFlow<ProxyStatus> = _status
    private val _lastError: MutableStateFlow<String?> = MutableStateFlow(null)
    val lastError: StateFlow<String?> = _lastError

    fun start(servers: List<UiMcpServerConfig>, presetId: String, inbound: UiTransportConfig): Result<Unit> {
        val preset: UiPresetCore = runCatching { repo.loadPreset(presetId) }
            .getOrElse { ex ->
                val e = IllegalStateException("Failed to load preset '$presetId': ${ex.message}", ex)
                _lastError.value = e.message
                _status.value = ProxyStatus.Error(e.message ?: "Error")
                return Result.failure(e)
            }
        val res = controller.start(servers, preset, inbound)
        if (res.isSuccess) {
            _status.value = ProxyStatus.Running
            _lastError.value = null
        } else {
            _status.value = ProxyStatus.Error(res.exceptionOrNull()?.message ?: "Failed to start")
        }
        return res
    }

    fun stop(): Result<Unit> {
        val res = controller.stop()
        _status.value = ProxyStatus.Stopped
        return res
    }
}
