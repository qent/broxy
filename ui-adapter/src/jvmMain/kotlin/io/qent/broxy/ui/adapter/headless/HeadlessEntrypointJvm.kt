package io.qent.broxy.ui.adapter.headless

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.qent.broxy.core.config.JsonConfigurationRepository
import io.qent.broxy.core.mcp.DefaultMcpServerConnection
import io.qent.broxy.core.mcp.IsolatedMcpServerConnection
import io.qent.broxy.core.mcp.auth.OAuthState
import io.qent.broxy.core.mcp.auth.OAuthStateStore
import io.qent.broxy.core.mcp.auth.resolveOAuthResourceUrl
import io.qent.broxy.core.mcp.auth.restoreFrom
import io.qent.broxy.core.mcp.auth.toSnapshot
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.proxy.ProxyMcpServer
import io.qent.broxy.core.proxy.inbound.buildSdkServer
import io.qent.broxy.core.proxy.inbound.syncSdkServer
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.core.utils.CompositeLogger
import io.qent.broxy.core.utils.DailyFileLogger
import io.qent.broxy.core.utils.StdErrLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
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
fun runStdioProxy(
    presetIdOverride: String? = null,
    configDir: String? = null,
): Result<Unit> =
    runCatching {
        val baseDir =
            if (configDir.isNullOrBlank()) {
                Paths.get(System.getProperty("user.home"), ".config", "broxy")
            } else {
                Paths.get(configDir)
            }
        val sink =
            CompositeLogger(
                StdErrLogger,
                DailyFileLogger(baseDir),
            )
        val repo = JsonConfigurationRepository(baseDir = baseDir, logger = sink)
        val cfg = repo.loadMcpConfig()
        val effectivePresetId =
            presetIdOverride?.takeIf { it.isNotBlank() }
                ?: cfg.defaultPresetId?.takeIf { it.isNotBlank() }
                ?: repo.listPresets().singleOrNull()?.id
        val preset =
            if (effectivePresetId == null) {
                Preset.empty()
            } else {
                runCatching { repo.loadPreset(effectivePresetId) }.getOrElse { Preset.empty() }
            }

        val logger = CollectingLogger(delegate = sink)
        val inbound = TransportConfig.StdioTransport(command = "", args = emptyList())

        val callTimeoutMillis = cfg.requestTimeoutSeconds.toLong() * 1_000L
        val capabilitiesTimeoutMillis = cfg.capabilitiesTimeoutSeconds.toLong() * 1_000L
        val authStateStore = OAuthStateStore(baseDir = baseDir, logger = sink)

        val downstreams: List<IsolatedMcpServerConnection> =
            cfg.servers
                .filter { it.enabled }
                .map { serverCfg ->
                    val resourceUrl = resolveAuthResourceUrl(serverCfg)
                    val authState =
                        resourceUrl?.let {
                            OAuthState().also { state ->
                                authStateStore.load(serverCfg.id, it)?.let(state::restoreFrom)
                            }
                        }
                    val connection =
                        DefaultMcpServerConnection(
                            config = serverCfg,
                            logger = logger,
                            authState = authState,
                            authStateObserver = { state ->
                                if (resourceUrl != null) {
                                    authStateStore.save(serverCfg.id, state.toSnapshot(resourceUrl))
                                }
                            },
                            initialCallTimeoutMillis = callTimeoutMillis,
                            initialCapabilitiesTimeoutMillis = capabilitiesTimeoutMillis,
                        )
                    IsolatedMcpServerConnection(connection)
                }

        var sdkServer: Server? = null
        var proxyRef: ProxyMcpServer? = null
        val proxy =
            ProxyMcpServer(
                downstreams = downstreams,
                logger = logger,
                onCapabilitiesUpdated = { _ ->
                    val server = sdkServer
                    val activeProxy = proxyRef
                    if (server != null && activeProxy != null) {
                        runCatching { syncSdkServer(server, activeProxy, logger) }
                    }
                },
            )
        proxyRef = proxy
        proxy.start(preset, inbound)

        val server = buildSdkServer(proxy, logger)
        sdkServer = server
        val transport =
            StdioServerTransport(
                System.`in`.asSource().buffered(),
                System.out.asSink().buffered(),
            )

        val shutdownSignal = CompletableDeferred<Unit>()
        transport.onClose { shutdownSignal.complete(Unit) }

        sink.info(
            "Starting broxy STDIO proxy (presetId='${effectivePresetId ?: "none"}', configDir='${configDir ?: "~/.config/broxy"}')",
        )

        val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val refreshJob =
            refreshScope.launch {
                supervisorScope {
                    downstreams.map { serverConn ->
                        launch {
                            try {
                                proxy.refreshServerCapabilities(serverConn.serverId)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (t: Throwable) {
                                logger.warn(
                                    "Initial capabilities refresh failed for '${serverConn.serverId}'",
                                    t,
                                )
                            }
                        }
                    }.joinAll()
                }
            }

        try {
            runBlocking {
                server.createSession(transport)
                shutdownSignal.await()
            }
        } finally {
            refreshJob.cancel()
            refreshScope.cancel()
            runBlocking { runCatching { transport.close() } }
            runCatching { proxy.stop() }
            runBlocking { downstreams.forEach { runCatching { it.disconnect() } } }
            downstreams.forEach { runCatching { it.close() } }
            sink.info("broxy STDIO proxy stopped")
        }
    }

fun logStdioInfo(message: String) {
    StdErrLogger.info(message)
}

private fun resolveAuthResourceUrl(config: io.qent.broxy.core.models.McpServerConfig): String? =
    when (val transport = config.transport) {
        is TransportConfig.HttpTransport -> resolveOAuthResourceUrl(transport.url)
        is TransportConfig.StreamableHttpTransport -> resolveOAuthResourceUrl(transport.url)
        is TransportConfig.WebSocketTransport -> resolveOAuthResourceUrl(transport.url)
        else -> null
    }
