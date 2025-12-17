package io.qent.broxy.ui.adapter.headless

import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.qent.broxy.core.config.JsonConfigurationRepository
import io.qent.broxy.core.mcp.DefaultMcpServerConnection
import io.qent.broxy.core.mcp.McpServerConnection
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.proxy.ProxyMcpServer
import io.qent.broxy.core.proxy.inbound.buildSdkServer
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.core.utils.StdErrLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.nio.file.Paths

/**
 * Starts the proxy in STDIO inbound mode and blocks until the STDIO session ends.
 * This is intended to be called from a packaged Desktop app binary with
 * no CLI args so that Claude Desktop can spawn it as an MCP STDIO server
 * without a separate CLI jar. The preset is resolved from:
 *
 * 1) [presetIdOverride] (if provided),
 * 2) `defaultPresetId` in `mcp.json`,
 * 3) the only available preset (if exactly one exists).
 *
 * @param presetIdOverride Optional preset ID override
 * @param configDir Optional configuration directory (defaults to ~/.config/broxy)
 */
fun runStdioProxy(presetIdOverride: String? = null, configDir: String? = null): Result<Unit> = runCatching {
    val repo = if (configDir.isNullOrBlank())
        JsonConfigurationRepository(logger = StdErrLogger)
    else
        JsonConfigurationRepository(baseDir = Paths.get(configDir), logger = StdErrLogger)
    val cfg = repo.loadMcpConfig()
    val effectivePresetId = presetIdOverride?.takeIf { it.isNotBlank() }
        ?: cfg.defaultPresetId?.takeIf { it.isNotBlank() }
        ?: repo.listPresets().singleOrNull()?.id
    val preset = if (effectivePresetId == null) {
        Preset.empty()
    } else {
        runCatching { repo.loadPreset(effectivePresetId) }.getOrElse { Preset.empty() }
    }

    val logger = CollectingLogger(delegate = StdErrLogger)
    val inbound = TransportConfig.StdioTransport(command = "", args = emptyList())

    val callTimeoutMillis = cfg.requestTimeoutSeconds.toLong() * 1_000L
    val capabilitiesTimeoutMillis = cfg.capabilitiesTimeoutSeconds.toLong() * 1_000L

    val downstreams: List<McpServerConnection> = cfg.servers
        .filter { it.enabled }
        .map { serverCfg ->
            DefaultMcpServerConnection(
                config = serverCfg,
                logger = logger,
                initialCallTimeoutMillis = callTimeoutMillis,
                initialCapabilitiesTimeoutMillis = capabilitiesTimeoutMillis
            )
        }

    val proxy = ProxyMcpServer(downstreams = downstreams, logger = logger)
    proxy.start(preset, inbound)

    val server = buildSdkServer(proxy, logger)
    val transport = StdioServerTransport(
        System.`in`.asSource().buffered(),
        System.out.asSink().buffered()
    )

    val shutdownSignal = CompletableDeferred<Unit>()
    transport.onClose { shutdownSignal.complete(Unit) }

    StdErrLogger.info(
        "Starting broxy STDIO proxy (presetId='${effectivePresetId ?: "none"}', configDir='${configDir ?: "~/.config/broxy"}')"
    )

    try {
        runBlocking {
            server.createSession(transport)
            shutdownSignal.await()
        }
    } finally {
        runBlocking { runCatching { transport.close() } }
        runCatching { proxy.stop() }
        runBlocking { downstreams.forEach { runCatching { it.disconnect() } } }
        StdErrLogger.info("broxy STDIO proxy stopped")
    }
}

fun logStdioInfo(message: String) {
    StdErrLogger.info(message)
}
