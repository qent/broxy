package io.qent.broxy.core.proxy.runtime

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

    fun start(
        servers: List<McpServerConfig>,
        preset: Preset,
        inbound: TransportConfig,
        callTimeoutSeconds: Int,
        capabilitiesTimeoutSeconds: Int
    ): Result<Unit>

    fun stop(): Result<Unit>

    fun applyPreset(preset: Preset): Result<Unit>

    fun updateCallTimeout(seconds: Int)

    fun updateCapabilitiesTimeout(seconds: Int)

    fun currentProxy(): ProxyMcpServer?
}

expect fun createProxyController(logger: CollectingLogger): ProxyController

/**
 * Specialized factory for STDIO inbound where stdout must remain clean for MCP.
 */
expect fun createStdioProxyController(logger: CollectingLogger): ProxyController
