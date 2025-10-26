package io.qent.bro.ui.data

import io.qent.bro.core.models.McpServerConfig
import io.qent.bro.core.models.Preset
import io.qent.bro.core.models.TransportConfig

/**
 * Platform abstraction to start/stop the MCP proxy with a given preset
 * and inbound transport. Implemented on desktop via Ktor + MCP SDK.
 */
interface ProxyController {
    fun start(servers: List<McpServerConfig>, preset: Preset, inbound: TransportConfig): Result<Unit>
    fun stop(): Result<Unit>
}

expect fun createProxyController(): ProxyController

