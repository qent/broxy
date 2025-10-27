package io.qent.bro.ui.adapter.proxy

import io.qent.bro.ui.adapter.models.UiMcpServerConfig
import io.qent.bro.ui.adapter.models.UiPresetCore
import io.qent.bro.ui.adapter.models.UiTransportConfig

/**
 * Platform abstraction to start/stop bro with a given preset
 * and inbound transport. Implemented on JVM via Ktor + MCP SDK.
 */
interface ProxyController {
    fun start(servers: List<UiMcpServerConfig>, preset: UiPresetCore, inbound: UiTransportConfig): Result<Unit>
    fun stop(): Result<Unit>
}

expect fun createProxyController(): ProxyController
