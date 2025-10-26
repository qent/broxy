package io.qent.bro.ui.viewmodels

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import io.qent.bro.core.models.McpServerConfig
import io.qent.bro.core.models.Preset
import io.qent.bro.core.models.TransportConfig
import io.qent.bro.core.repository.ConfigurationRepository
import io.qent.bro.ui.data.ProxyController
import io.qent.bro.ui.data.createProxyController
import io.qent.bro.ui.data.provideConfigurationRepository

class ProxyViewModel(
    private val controller: ProxyController = createProxyController(),
    private val repo: ConfigurationRepository = provideConfigurationRepository()
) {
    val status: MutableState<ProxyStatus> = mutableStateOf(ProxyStatus.Stopped)
    val lastError: MutableState<String?> = mutableStateOf(null)

    fun start(servers: List<McpServerConfig>, presetId: String, inbound: TransportConfig): Result<Unit> {
        val preset: Preset = runCatching { repo.loadPreset(presetId) }
            .getOrElse { ex ->
                val e = IllegalStateException("Failed to load preset '$presetId': ${ex.message}", ex)
                lastError.value = e.message
                status.value = ProxyStatus.Error(e.message ?: "Error")
                return Result.failure(e)
            }
        val res = controller.start(servers, preset, inbound)
        if (res.isSuccess) {
            status.value = ProxyStatus.Running
            lastError.value = null
        } else {
            status.value = ProxyStatus.Error(res.exceptionOrNull()?.message ?: "Failed to start")
        }
        return res
    }

    fun stop(): Result<Unit> {
        val res = controller.stop()
        status.value = ProxyStatus.Stopped
        return res
    }
}

