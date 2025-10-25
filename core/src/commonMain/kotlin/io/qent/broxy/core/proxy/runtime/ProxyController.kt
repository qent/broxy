package io.qent.broxy.core.proxy.runtime

import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.proxy.ProxyMcpServer
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.core.utils.LogEvent
import kotlinx.coroutines.flow.Flow

/**
 * Platform abstraction that wires ProxyMcpServer to an inbound transport.
 * Implementations live per-platform (currently JVM).
 */
interface ProxyController {
    val logs: Flow<LogEvent>
    val capabilityUpdates: Flow<Map<String, ServerCapabilities>>
    val serverStatusUpdates: Flow<ServerConnectionUpdate>

    @Suppress("LongParameterList")
    fun start(
        servers: List<McpServerConfig>,
        preset: Preset,
        inbound: TransportConfig,
        callTimeoutSeconds: Int,
        capabilitiesTimeoutSeconds: Int,
        authorizationTimeoutSeconds: Int,
        connectionRetryCount: Int,
        capabilitiesRefreshIntervalSeconds: Int,
        fallbackPromptsAndResourcesToTools: Boolean,
    ): Result<Unit>

    fun stop(): Result<Unit>

    fun applyPreset(preset: Preset): Result<Unit>

    /**
     * Updates the downstream server set without restarting the inbound facade.
     * Implementations should refresh the published capabilities after applying the change.
     */
    @Suppress("LongParameterList")
    fun updateServers(
        servers: List<McpServerConfig>,
        callTimeoutSeconds: Int,
        capabilitiesTimeoutSeconds: Int,
        authorizationTimeoutSeconds: Int,
        connectionRetryCount: Int,
        capabilitiesRefreshIntervalSeconds: Int,
        fallbackPromptsAndResourcesToTools: Boolean,
    ): Result<Unit>

    fun updateCallTimeout(seconds: Int)

    fun updateCapabilitiesTimeout(seconds: Int)

    fun updateConnectionRetryCount(count: Int)

    fun updateFallbackPromptsAndResourcesToTools(enabled: Boolean)

    /**
     * Forces a downstream capability refresh for a specific server.
     */
    fun refreshServerCapabilities(serverId: String): Result<Unit>

    /**
     * Forces a full capabilities refresh and preset re-filtering.
     */
    fun refreshFilteredCapabilities(): Result<Unit>
}

internal interface ProxyControllerInternal {
    fun currentProxy(): ProxyMcpServer?
}

expect fun createProxyController(
    logger: CollectingLogger,
    configDir: String? = null,
): ProxyController

/**
 * Specialized factory for STDIO inbound where stdout must remain clean for MCP.
 */
expect fun createStdioProxyController(
    logger: CollectingLogger,
    configDir: String? = null,
): ProxyController
