package io.qent.bro.ui.adapter.proxy

import io.qent.bro.core.mcp.DefaultMcpServerConnection
import io.qent.bro.core.mcp.McpServerConnection
import io.qent.bro.core.mcp.ServerStatus
import io.qent.bro.core.proxy.ProxyMcpServer
import io.qent.bro.core.proxy.inbound.InboundServer
import io.qent.bro.core.proxy.inbound.InboundServerFactory
import io.qent.bro.core.utils.CollectingLogger
import io.qent.bro.core.utils.LogEvent
import io.qent.bro.core.utils.StdErrLogger
import io.qent.bro.ui.adapter.models.UiMcpServerConfig
import io.qent.bro.ui.adapter.models.UiPresetCore
import io.qent.bro.ui.adapter.models.UiTransportConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow

private class JvmProxyController(
    private val logger: CollectingLogger
) : ProxyController {
    private var downstreams: List<McpServerConnection> = emptyList()
    private var proxy: ProxyMcpServer? = null
    private var inbound: InboundServer? = null
    @Volatile
    private var callTimeoutMillis: Long = 60_000

    override val logs: Flow<LogEvent> get() = logger.events

    override fun start(
        servers: List<UiMcpServerConfig>,
        preset: UiPresetCore,
        inbound: UiTransportConfig,
        callTimeoutSeconds: Int
    ): Result<Unit> = runCatching {
        runCatching { stop() }
        callTimeoutMillis = callTimeoutSeconds.coerceIn(5, 600) * 1000L

        downstreams = servers.filter { it.enabled }.map { cfg ->
            DefaultMcpServerConnection(cfg, logger = logger, callTimeoutMillis = callTimeoutMillis)
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

    override fun updateCallTimeout(seconds: Int) {
        callTimeoutMillis = seconds.coerceIn(5, 600) * 1000L
        downstreams.forEach { conn ->
            (conn as? DefaultMcpServerConnection)?.updateCallTimeout(callTimeoutMillis)
        }
    }
}

actual fun createProxyController(logger: CollectingLogger): ProxyController = JvmProxyController(logger)

/**
 * Specialized factory for STDIO inbound where stdout must remain clean for MCP
 * and all logs go to stderr.
 */
fun createStdioProxyController(logger: CollectingLogger = CollectingLogger(delegate = StdErrLogger)): ProxyController =
    JvmProxyController(logger = logger)
