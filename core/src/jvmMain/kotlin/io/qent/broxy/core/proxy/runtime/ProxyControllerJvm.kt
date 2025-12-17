package io.qent.broxy.core.proxy.runtime

import io.qent.broxy.core.mcp.DefaultMcpServerConnection
import io.qent.broxy.core.mcp.McpServerConnection
import io.qent.broxy.core.mcp.ServerStatus
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.proxy.ProxyMcpServer
import io.qent.broxy.core.proxy.inbound.InboundServer
import io.qent.broxy.core.proxy.inbound.InboundServerFactory
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.core.utils.LogEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking

private class JvmProxyController(
    private val logger: CollectingLogger
) : ProxyController {
    private var downstreams: List<McpServerConnection> = emptyList()
    private var proxy: ProxyMcpServer? = null
    private var inboundServer: InboundServer? = null
    @Volatile
    private var callTimeoutMillis: Long = 60_000
    @Volatile
    private var capabilitiesTimeoutMillis: Long = 30_000

    override val logs: Flow<LogEvent> get() = logger.events

    override fun start(
        servers: List<McpServerConfig>,
        preset: Preset,
        inbound: TransportConfig,
        callTimeoutSeconds: Int,
        capabilitiesTimeoutSeconds: Int
    ): Result<Unit> = runCatching {
        runCatching { stop() }
        callTimeoutMillis = callTimeoutSeconds.toLong() * 1_000L
        capabilitiesTimeoutMillis = capabilitiesTimeoutSeconds.toLong() * 1_000L

        downstreams = servers.filter { it.enabled }.map { cfg ->
            DefaultMcpServerConnection(
                config = cfg,
                logger = logger,
                initialCallTimeoutMillis = callTimeoutMillis,
                initialCapabilitiesTimeoutMillis = capabilitiesTimeoutMillis
            )
        }
        val proxy = ProxyMcpServer(downstreams, logger = logger)
        proxy.start(preset, inbound)
        val inboundServer = InboundServerFactory.create(inbound, proxy, logger)
        val status = inboundServer.start()
        if (status is ServerStatus.Error) {
            throw IllegalStateException(status.message ?: "Failed to start inbound server")
        }
        this.proxy = proxy
        this.inboundServer = inboundServer
    }

    override fun stop(): Result<Unit> = runCatching {
        inboundServer?.stop()
        inboundServer = null
        val ds = downstreams
        downstreams = emptyList()
        proxy = null
        runBlocking { ds.forEach { runCatching { it.disconnect() } } }
    }

    override fun applyPreset(preset: Preset): Result<Unit> = runCatching {
        val proxy = this.proxy ?: error("Proxy is not running")
        proxy.applyPreset(preset)
        inboundServer?.refreshCapabilities()?.getOrThrow()
    }

    override fun updateServers(
        servers: List<McpServerConfig>,
        callTimeoutSeconds: Int,
        capabilitiesTimeoutSeconds: Int
    ): Result<Unit> = runCatching {
        val proxy = this.proxy ?: error("Proxy is not running")

        callTimeoutMillis = callTimeoutSeconds.toLong() * 1_000L
        capabilitiesTimeoutMillis = capabilitiesTimeoutSeconds.toLong() * 1_000L

        val previous = downstreams
        downstreams = servers.filter { it.enabled }.map { cfg ->
            DefaultMcpServerConnection(
                config = cfg,
                logger = logger,
                initialCallTimeoutMillis = callTimeoutMillis,
                initialCapabilitiesTimeoutMillis = capabilitiesTimeoutMillis
            )
        }

        proxy.updateDownstreams(downstreams)
        runBlocking { proxy.refreshFilteredCapabilities() }
        inboundServer?.refreshCapabilities()?.getOrThrow()

        val currentIds = downstreams.map { it.serverId }.toSet()
        runBlocking {
            previous
                .filterNot { it.serverId in currentIds }
                .forEach { runCatching { it.disconnect() } }
        }
    }

    override fun updateCallTimeout(seconds: Int) {
        callTimeoutMillis = seconds.toLong() * 1_000L
        downstreams.forEach { conn ->
            (conn as? DefaultMcpServerConnection)?.updateCallTimeout(callTimeoutMillis)
        }
    }

    override fun updateCapabilitiesTimeout(seconds: Int) {
        capabilitiesTimeoutMillis = seconds.toLong() * 1_000L
        downstreams.forEach { conn ->
            (conn as? DefaultMcpServerConnection)?.updateCapabilitiesTimeout(capabilitiesTimeoutMillis)
        }
    }

    override fun currentProxy(): ProxyMcpServer? = proxy
}

actual fun createProxyController(logger: CollectingLogger): ProxyController = JvmProxyController(logger)

actual fun createStdioProxyController(logger: CollectingLogger): ProxyController =
    JvmProxyController(logger = logger)
