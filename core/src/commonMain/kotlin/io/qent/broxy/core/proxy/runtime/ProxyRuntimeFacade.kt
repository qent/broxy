package io.qent.broxy.core.proxy.runtime

import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.TransportConfig
import kotlinx.coroutines.flow.Flow

/**
 * Stable runtime facade for UI/CLI callers. Hides ProxyMcpServer internals.
 */
interface ProxyRuntimeFacade {
    val capabilityUpdates: Flow<Map<String, ServerCapabilities>>
    val serverStatusUpdates: Flow<ServerConnectionUpdate>
    val isRunning: Boolean

    fun start(
        config: McpServersConfig,
        preset: Preset,
        inbound: TransportConfig,
    ): Result<Unit>

    fun stop(): Result<Unit>

    fun applyPreset(preset: Preset): Result<Unit>

    fun updateServers(config: McpServersConfig): Result<Unit>

    fun refreshServerCapabilities(serverId: String): Result<Unit>

    fun refreshFilteredCapabilities(): Result<Unit>

    fun updateCallTimeout(seconds: Int)

    fun updateCapabilitiesTimeout(seconds: Int)

    fun updateConnectionRetryCount(count: Int)

    fun updateFallbackPromptsAndResourcesToTools(enabled: Boolean)
}
