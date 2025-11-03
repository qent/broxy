package io.qent.broxy.ui.adapter.proxy

import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.core.utils.LogEvent
import io.qent.broxy.ui.adapter.models.UiMcpServerConfig
import io.qent.broxy.ui.adapter.models.UiPresetCore
import io.qent.broxy.ui.adapter.models.UiTransportConfig
import kotlinx.coroutines.flow.Flow

/**
 * Platform abstraction to start/stop broxy with a given preset
 * and inbound transport. Implemented on JVM via Ktor + MCP SDK.
 */
interface ProxyController {
    val logs: Flow<LogEvent>
    fun start(servers: List<UiMcpServerConfig>, preset: UiPresetCore, inbound: UiTransportConfig, callTimeoutSeconds: Int): Result<Unit>
    fun stop(): Result<Unit>
    fun updateCallTimeout(seconds: Int)
}

expect fun createProxyController(logger: CollectingLogger): ProxyController
