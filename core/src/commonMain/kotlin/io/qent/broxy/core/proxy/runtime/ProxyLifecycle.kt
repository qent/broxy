package io.qent.broxy.core.proxy.runtime

import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.proxy.ProxyMcpServer
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.flow.Flow

/**
 * Coordinates ProxyController start/stop/update operations and keeps track of the
 * currently active configuration so that callers (UI/CLI) don't have to duplicate
 * restart logic.
 */
@Suppress("TooManyFunctions")
class ProxyLifecycle(
    private val controller: ProxyController,
    private val logger: Logger,
) : ProxyRuntimeFacade {
    override val capabilityUpdates: Flow<Map<String, ServerCapabilities>> get() = controller.capabilityUpdates
    override val serverStatusUpdates: Flow<ServerConnectionUpdate> get() = controller.serverStatusUpdates
    private var currentConfig: McpServersConfig? = null
    private var currentPreset: Preset? = null
    private var currentInbound: TransportConfig? = null

    override fun start(
        config: McpServersConfig,
        preset: Preset,
        inbound: TransportConfig,
    ): Result<Unit> {
        val result =
            controller.start(
                servers = config.servers,
                preset = preset,
                inbound = inbound,
                callTimeoutSeconds = config.requestTimeoutSeconds,
                capabilitiesTimeoutSeconds = config.capabilitiesTimeoutSeconds,
                authorizationTimeoutSeconds = config.authorizationTimeoutSeconds,
                connectionRetryCount = config.connectionRetryCount,
                capabilitiesRefreshIntervalSeconds = config.capabilitiesRefreshIntervalSeconds,
                fallbackPromptsAndResourcesToTools = config.fallbackPromptsAndResourcesToTools,
                adapterMode = config.adapterMode,
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

    override fun stop(): Result<Unit> {
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

    fun restart(
        config: McpServersConfig?,
        preset: Preset?,
        inbound: TransportConfig?,
    ): Result<Unit> {
        if (config == null || preset == null || inbound == null) {
            return Result.failure(IllegalStateException("Proxy is not running"))
        }
        return start(config, preset, inbound)
    }

    override fun applyPreset(preset: Preset): Result<Unit> {
        val result = controller.applyPreset(preset)
        if (result.isSuccess) {
            currentPreset = preset
        } else {
            logger.warn("ProxyLifecycle applyPreset failed: ${result.exceptionOrNull()?.message}")
        }
        return result
    }

    override fun updateServers(config: McpServersConfig): Result<Unit> {
        val result =
            controller.updateServers(
                servers = config.servers,
                callTimeoutSeconds = config.requestTimeoutSeconds,
                capabilitiesTimeoutSeconds = config.capabilitiesTimeoutSeconds,
                authorizationTimeoutSeconds = config.authorizationTimeoutSeconds,
                connectionRetryCount = config.connectionRetryCount,
                capabilitiesRefreshIntervalSeconds = config.capabilitiesRefreshIntervalSeconds,
                fallbackPromptsAndResourcesToTools = config.fallbackPromptsAndResourcesToTools,
                adapterMode = config.adapterMode,
            )
        if (result.isSuccess) {
            currentConfig = config
        } else {
            logger.warn("ProxyLifecycle updateServers failed: ${result.exceptionOrNull()?.message}")
        }
        return result
    }

    override fun updateCallTimeout(seconds: Int) {
        controller.updateCallTimeout(seconds)
        currentConfig = currentConfig?.copy(requestTimeoutSeconds = seconds)
    }

    override fun updateCapabilitiesTimeout(seconds: Int) {
        controller.updateCapabilitiesTimeout(seconds)
        currentConfig = currentConfig?.copy(capabilitiesTimeoutSeconds = seconds)
    }

    override fun updateConnectionRetryCount(count: Int) {
        controller.updateConnectionRetryCount(count)
        currentConfig = currentConfig?.copy(connectionRetryCount = count)
    }

    override fun updateFallbackPromptsAndResourcesToTools(enabled: Boolean) {
        controller.updateFallbackPromptsAndResourcesToTools(enabled)
        currentConfig = currentConfig?.copy(fallbackPromptsAndResourcesToTools = enabled)
    }

    override fun updateAdapterMode(enabled: Boolean) {
        controller.updateAdapterMode(enabled)
        currentConfig = currentConfig?.copy(adapterMode = enabled)
    }

    override fun refreshServerCapabilities(serverId: String): Result<Unit> =
        controller.refreshServerCapabilities(
            serverId,
        )

    override fun refreshFilteredCapabilities(): Result<Unit> = controller.refreshFilteredCapabilities()

    override val isRunning: Boolean
        get() = currentPreset != null && currentInbound != null

    internal fun currentProxy(): ProxyMcpServer? = (controller as? ProxyControllerInternal)?.currentProxy()
}
