package io.qent.broxy.core.proxy

import io.qent.broxy.core.mcp.ServerStatus
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.TransportConfig

interface ProxyServer {
    fun start(preset: Preset, transport: TransportConfig)

    fun stop()

    fun getStatus(): ServerStatus
}
