package io.qent.broxy.core.proxy.runtime

import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.utils.Logger

/**
 * Coordinates ProxyController start/stop/update operations and keeps track of the
 * currently active configuration so that callers (UI/CLI) don't have to duplicate
 * restart logic.
 */
class ProxyLifecycle(
    private val controller: ProxyController,
    private val logger: Logger
) {
    private var currentConfig: McpServersConfig? = null
    private var currentPreset: Preset? = null
    private var currentInbound: TransportConfig? = null

    fun start(
        config: McpServersConfig,
        preset: Preset,
        inbound: TransportConfig
    ): Result<Unit> {
        val result = controller.start(
            servers = config.servers,
            preset = preset,
            inbound = inbound,
            callTimeoutSeconds = config.requestTimeoutSeconds,
            capabilitiesTimeoutSeconds = config.capabilitiesTimeoutSeconds
        )
        if (result.isSuccess) {
            currentConfig = config
            currentPreset = preset
            currentInbound = inbound
        } else {
            logger.warn("ProxyLifecycle start failed: ${result.exceptionOrNull()?.message}")
        }
        return result
    }

    fun stop(): Result<Unit> {
        val result = controller.stop()
        if (result.isSuccess) {
            currentConfig = null
            currentPreset = null
            currentInbound = null
        } else {
            logger.warn("ProxyLifecycle stop failed: ${result.exceptionOrNull()?.message}")
        }
        return result
    }

    fun restartWithConfig(config: McpServersConfig): Result<Unit> =
        restart(config = config, preset = currentPreset, inbound = currentInbound)

    fun restartWithPreset(preset: Preset): Result<Unit> =
        restart(config = currentConfig, preset = preset, inbound = currentInbound)

    private fun restart(
        config: McpServersConfig?,
        preset: Preset?,
        inbound: TransportConfig?
    ): Result<Unit> {
        if (config == null || preset == null || inbound == null) {
            return Result.failure(IllegalStateException("Proxy is not running"))
        }
        return start(config, preset, inbound)
    }

    fun applyPreset(preset: Preset): Result<Unit> {
        val result = controller.applyPreset(preset)
        if (result.isSuccess) {
            currentPreset = preset
        } else {
            logger.warn("ProxyLifecycle applyPreset failed: ${result.exceptionOrNull()?.message}")
        }
        return result
    }

    fun updateServers(config: McpServersConfig): Result<Unit> {
        val result = controller.updateServers(
            servers = config.servers,
            callTimeoutSeconds = config.requestTimeoutSeconds,
            capabilitiesTimeoutSeconds = config.capabilitiesTimeoutSeconds
        )
        if (result.isSuccess) {
            currentConfig = config
        } else {
            logger.warn("ProxyLifecycle updateServers failed: ${result.exceptionOrNull()?.message}")
        }
        return result
    }

    fun updateCallTimeout(seconds: Int) {
        controller.updateCallTimeout(seconds)
        currentConfig = currentConfig?.copy(requestTimeoutSeconds = seconds)
    }

    fun updateCapabilitiesTimeout(seconds: Int) {
        controller.updateCapabilitiesTimeout(seconds)
        currentConfig = currentConfig?.copy(capabilitiesTimeoutSeconds = seconds)
    }

    fun isRunning(): Boolean = currentPreset != null && currentInbound != null

    fun currentProxy(): io.qent.broxy.core.proxy.ProxyMcpServer? = controller.currentProxy()
}
