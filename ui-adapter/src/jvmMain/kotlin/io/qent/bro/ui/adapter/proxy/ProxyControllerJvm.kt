package io.qent.bro.ui.adapter.proxy

import io.qent.bro.core.mcp.DefaultMcpServerConnection
import io.qent.bro.core.mcp.McpServerConnection
import io.qent.bro.core.mcp.ServerStatus
import io.qent.bro.core.proxy.ProxyMcpServer
import io.qent.bro.core.proxy.inbound.InboundServer
import io.qent.bro.core.proxy.inbound.InboundServerFactory
import io.qent.bro.core.utils.ConsoleLogger
import io.qent.bro.core.utils.Logger
import io.qent.bro.ui.adapter.models.UiMcpServerConfig
import io.qent.bro.ui.adapter.models.UiPresetCore
import io.qent.bro.ui.adapter.models.UiTransportConfig
import kotlinx.coroutines.runBlocking

private class JvmProxyController(
    private val logger: Logger = ConsoleLogger
) : ProxyController {
    private var downstreams: List<McpServerConnection> = emptyList()
    private var proxy: ProxyMcpServer? = null
    private var inbound: InboundServer? = null

    override fun start(servers: List<UiMcpServerConfig>, preset: UiPresetCore, inbound: UiTransportConfig): Result<Unit> = runCatching {
        runCatching { stop() }

        downstreams = servers.filter { it.enabled }.map { cfg ->
            DefaultMcpServerConnection(cfg, logger = logger)
        }
        val p = ProxyMcpServer(downstreams, logger = logger)
        p.start(preset, inbound)
        val inboundServer = InboundServerFactory.create(inbound, p, logger)
        val status = inboundServer.start()
        if (status is ServerStatus.Error) {
            throw IllegalStateException(status.message ?: "Failed to start inbound server")
        }
        this.proxy = p
        this.inbound = inboundServer
    }

    override fun stop(): Result<Unit> = runCatching {
        inbound?.stop()
        inbound = null
        val ds = downstreams
        downstreams = emptyList()
        proxy = null
        runBlocking { ds.forEach { runCatching { it.disconnect() } } }
    }
}

actual fun createProxyController(): ProxyController = JvmProxyController()
