package io.qent.bro.core.proxy

import io.qent.bro.core.mcp.ServerStatus
import io.qent.bro.core.models.Preset
import io.qent.bro.core.models.TransportConfig

interface ProxyServer {
    fun start(preset: Preset, transport: TransportConfig)

    fun stop()

    fun getStatus(): ServerStatus
}
