package io.qent.broxy.ui.adapter.proxy

import io.qent.broxy.core.mcp.DefaultMcpServerConnection
import io.qent.broxy.core.mcp.McpServerConnection
import io.qent.broxy.core.mcp.ServerStatus
import io.qent.broxy.core.proxy.ProxyMcpServer
import io.qent.broxy.core.proxy.inbound.InboundServer
import io.qent.broxy.core.proxy.inbound.InboundServerFactory
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.core.utils.LogEvent
import io.qent.broxy.core.utils.StdErrLogger
import io.qent.broxy.ui.adapter.models.UiMcpServerConfig
import io.qent.broxy.ui.adapter.models.UiPresetCore
import io.qent.broxy.ui.adapter.models.UiTransportConfig
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
    @Volatile
    private var capabilitiesTimeoutMillis: Long = 30_000

    override val logs: Flow<LogEvent> get() = logger.events

    override fun start(
        servers: List<UiMcpServerConfig>,
        preset: UiPresetCore,
        inbound: UiTransportConfig,
        callTimeoutSeconds: Int,
        capabilitiesTimeoutSeconds: Int
    ): Result<Unit> = runCatching {
        runCatching { stop() }
        callTimeoutMillis = callTimeoutSeconds.toLong() * 1000L
        capabilitiesTimeoutMillis = capabilitiesTimeoutSeconds.toLong() * 1000L

        downstreams = servers.filter { it.enabled }.map { cfg ->
            DefaultMcpServerConnection(
                config = cfg,
                logger = logger,
                initialCallTimeoutMillis = callTimeoutMillis,
                initialCapabilitiesTimeoutMillis = capabilitiesTimeoutMillis
            )
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
        callTimeoutMillis = seconds.toLong() * 1000L
        downstreams.forEach { conn ->
            (conn as? DefaultMcpServerConnection)?.updateCallTimeout(callTimeoutMillis)
        }
    }

    override fun updateCapabilitiesTimeout(seconds: Int) {
        capabilitiesTimeoutMillis = seconds.toLong() * 1000L
        downstreams.forEach { conn ->
            (conn as? DefaultMcpServerConnection)?.updateCapabilitiesTimeout(capabilitiesTimeoutMillis)
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
