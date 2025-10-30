package io.qent.bro.ui.adapter.proxy

import io.qent.bro.core.utils.CollectingLogger
import io.qent.bro.core.utils.LogEvent
import io.qent.bro.ui.adapter.models.UiMcpServerConfig
import io.qent.bro.ui.adapter.models.UiPresetCore
import io.qent.bro.ui.adapter.models.UiTransportConfig
import kotlinx.coroutines.flow.Flow

/**
 * Platform abstraction to start/stop bro with a given preset
 * and inbound transport. Implemented on JVM via Ktor + MCP SDK.
 */
interface ProxyController {
    val logs: Flow<LogEvent>
    fun start(servers: List<UiMcpServerConfig>, preset: UiPresetCore, inbound: UiTransportConfig, callTimeoutSeconds: Int): Result<Unit>
    fun stop(): Result<Unit>
    fun updateCallTimeout(seconds: Int)
}

expect fun createProxyController(logger: CollectingLogger): ProxyController
