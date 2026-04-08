package io.qent.broxy.core.proxy.inbound

import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.server.SseServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.qent.broxy.core.mcp.McpServerConnection
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.ServerStatus
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.proxy.ProxyMcpServer
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.net.BindException
import java.net.URI

/**
 * Abstraction for platform-specific inbound transport servers that expose
 * the proxy over STDIO / HTTP(Streamable). These classes adapt the
 * wire protocol to the [ProxyMcpServer]'s filtering and routing.
 */
interface InboundServer {
    fun start(): ServerStatus

    fun stop(): ServerStatus

    fun refreshCapabilities(): Result<Unit>
}

object InboundServerFactory {
    fun create(
        transport: TransportConfig,
        proxy: ProxyMcpServer,
        logger: Logger = ConsoleLogger,
        presetResolver: InboundPresetResolver? = null,
    ): InboundServer =
        create(
            transport = transport,
            proxy = proxy,
            logger = logger,
            presetResolver = presetResolver,
            requestTimeoutMillis = DEFAULT_REQUEST_TIMEOUT_MILLIS,
        )

    fun create(
        transport: TransportConfig,
        proxy: ProxyMcpServer,
        logger: Logger = ConsoleLogger,
        presetResolver: InboundPresetResolver? = null,
        requestTimeoutMillis: Long,
    ): InboundServer =
        when (transport) {
            is TransportConfig.StdioTransport -> StdioInboundServer(proxy, logger)
            is TransportConfig.StreamableHttpTransport ->
                KtorStreamableHttpInboundServer(
                    url = transport.url,
                    proxy = proxy,
                    logger = logger,
                    presetResolver = presetResolver,
                    requestTimeoutMillis = requestTimeoutMillis,
                )
            // Backward compatibility: historically inbound used HttpTransport (SSE).
            // We now treat it as Streamable HTTP while exposing SSE at /sse.
            is TransportConfig.HttpTransport ->
                KtorStreamableHttpInboundServer(
                    url = transport.url,
                    proxy = proxy,
                    logger = logger,
                    presetResolver = presetResolver,
                    requestTimeoutMillis = requestTimeoutMillis,
                )

            else -> error("Unsupported inbound transport: ${transport::class.simpleName}")
        }
}

internal interface RequestTimeoutConfigurableInbound {
    fun updateRequestTimeoutMillis(timeoutMillis: Long)

    fun currentRequestTimeoutMillis(): Long
}

private class StdioInboundServer(
    private val proxy: ProxyMcpServer,
    private val logger: Logger,
) : InboundServer {
    private var server: Server? = null
    private var session: ServerSession? = null

    override fun start(): ServerStatus {
        logger.info("Starting STDIO inbound server (MCP SDK)")
        val input = System.`in`.asSource().buffered()
        val output = System.out.asSink().buffered()
        val transport = StdioServerTransport(input, output)
        val server = buildSdkServer(proxy, logger)
        this.server = server
        val startResult = runCatching { runBlocking { server.createSession(transport) } }
        return startResult.fold(
            onSuccess = { created ->
                session = created
                ServerStatus.Running
            },
            onFailure = { failure ->
                this.server = null
                this.session = null
                logger.error("Failed to start STDIO MCP server", failure)
                ServerStatus.Error(failure.message)
            },
        )
    }

    override fun stop(): ServerStatus {
        logger.info("Stopping STDIO inbound server")
        val server = this.server
        val session = this.session
        this.server = null
        this.session = null
        runCatching { runBlocking { session?.close() } }
        runCatching { runBlocking { server?.close() } }
        return ServerStatus.Stopped
    }

    override fun refreshCapabilities(): Result<Unit> {
        val server = server ?: return Result.failure(IllegalStateException("STDIO inbound server is not running"))
        return runCatching { syncSdkServer(server, proxy, logger) }
    }
}

@Suppress("TooManyFunctions")
private class KtorStreamableHttpInboundServer(
    private val url: String,
    private val proxy: ProxyMcpServer,
    private val logger: Logger,
    private val presetResolver: InboundPresetResolver?,
    requestTimeoutMillis: Long,
) : InboundServer,
    RequestTimeoutConfigurableInbound {
    private var engine: EmbeddedServer<*, *>? = null
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var streamableSessions: InboundStreamableHttpRegistry? = null
    private var sseSessions: InboundSseRegistry? = null
    private var sessionCleanup: InboundSessionCleanup? = null

    @Volatile
    private var requestTimeoutMillis: Long = requestTimeoutMillis.coerceAtLeast(MIN_REQUEST_TIMEOUT_MILLIS)

    override fun updateRequestTimeoutMillis(timeoutMillis: Long) {
        requestTimeoutMillis = timeoutMillis.coerceAtLeast(MIN_REQUEST_TIMEOUT_MILLIS)
        logger.info("Updated inbound request timeout to ${requestTimeoutMillis}ms")
    }

    override fun currentRequestTimeoutMillis(): Long = requestTimeoutMillis

    override fun start(): ServerStatus {
        val startConfig = buildStartConfig()
        logStart(startConfig)
        val startResult = runCatching { startEngine(startConfig) }
        return startResult.fold(
            onSuccess = { started ->
                engine = started.engine
                streamableSessions = started.streamableSessions
                sseSessions = started.sseSessions
                sessionCleanup =
                    InboundSessionCleanup(
                        logger = logger,
                        scope = cleanupScope,
                        registries =
                            listOf(
                                InboundSessionCleanup.RegistryEntry("streamable", started.streamableSessions),
                                InboundSessionCleanup.RegistryEntry("sse", started.sseSessions),
                            ),
                    ).also { it.start() }
                ServerStatus.Running
            },
            onFailure = { failure ->
                val message = resolveStartFailure(failure, startConfig.port)
                cleanupAfterFailure()
                logger.error(message, failure)
                ServerStatus.Error(message)
            },
        )
    }

    override fun stop(): ServerStatus {
        val srv = engine
        engine = null
        sessionCleanup?.stop()
        sessionCleanup = null
        streamableSessions = null
        sseSessions = null
        srv?.stop()
        return ServerStatus.Stopped
    }

    override fun refreshCapabilities(): Result<Unit> {
        if (engine == null) {
            return Result.failure(IllegalStateException("HTTP inbound server is not running"))
        }
        return runCatching { refreshAllSessionServers() }
    }

    private data class StartConfig(
        val host: String,
        val port: Int,
        val normalizedPath: NormalizedPath,
        val scheme: String,
    )

    private data class StartedInbound(
        val engine: EmbeddedServer<*, *>,
        val streamableSessions: InboundStreamableHttpRegistry,
        val sseSessions: InboundSseRegistry,
    )

    private fun buildStartConfig(): StartConfig {
        val (host, port, rawPath) = parse(url)
        val scheme = runCatching { URI(url).scheme }.getOrNull()?.takeIf { it.isNotBlank() } ?: "http"
        val normalizedPath = normalizePath(rawPath)
        return StartConfig(host = host, port = port, normalizedPath = normalizedPath, scheme = scheme)
    }

    private fun logStart(config: StartConfig) {
        val sseDisplayPath = SSE_ENDPOINT_PATH
        val displayPath = if (config.normalizedPath.display.isBlank()) "/" else config.normalizedPath.display
        logger.info(
            "Starting HTTP Streamable inbound at ${config.scheme}://${config.host}:${config.port}$displayPath " +
                "(SSE at ${config.scheme}://${config.host}:${config.port}$sseDisplayPath)",
        )
        logger.debug("HTTP inbound route segments='${config.normalizedPath.routeSegments.ifBlank { "/" }}'")
    }

    private fun startEngine(config: StartConfig): StartedInbound {
        val sessions = InboundStreamableHttpRegistry(logger)
        val sseSessions = InboundSseRegistry(logger)
        val engine =
            embeddedServer(Netty, host = config.host, port = config.port, module = {
                install(CallLogging) {
                    // Avoid ANSI/Jansi native initialization in packaged apps.
                    disableDefaultColors()
                }
                install(SSE)
                routing {
                    route(SSE_ENDPOINT_PATH) {
                        mountSseRoute(
                            sessions = sseSessions,
                            bindingProvider = { InboundSessionBinding(routePath = SSE_ENDPOINT_PATH) },
                            sessionFactory = { binding, transport -> createSseSession(binding, transport) },
                        )
                        route("/{presetId}") {
                            mountSseRoute(
                                sessions = sseSessions,
                                bindingProvider = { call ->
                                    val presetId =
                                        call.parameters["presetId"]
                                            ?.trim()
                                            ?.takeIf { it.isNotEmpty() }
                                            ?: throw InboundPresetNotFoundException("unknown")
                                    resolvePreset(presetId)
                                    InboundSessionBinding(
                                        routePath = joinRoutePath(SSE_ENDPOINT_PATH, presetId),
                                        presetId = presetId,
                                    )
                                },
                                sessionFactory = { binding, transport -> createSseSession(binding, transport) },
                            )
                        }
                    }
                    if (config.normalizedPath.routeSegments.isBlank()) {
                        mountStreamableRoutes(
                            route = this,
                            routePath = config.normalizedPath.display,
                            sessions = sessions,
                        )
                    } else {
                        route("/${config.normalizedPath.routeSegments}") {
                            mountStreamableRoutes(
                                route = this,
                                routePath = config.normalizedPath.display,
                                sessions = sessions,
                            )
                        }
                    }
                }
            }).start(wait = false)
        return StartedInbound(engine, sessions, sseSessions)
    }

    private fun cleanupAfterFailure() {
        engine = null
        sessionCleanup?.stop()
        sessionCleanup = null
        streamableSessions = null
        sseSessions = null
    }

    private fun mountStreamableRoutes(
        route: Route,
        routePath: String,
        sessions: InboundStreamableHttpRegistry,
    ) {
        route.mountStreamableHttpRoute(
            sessions = sessions,
            bindingProvider = { InboundSessionBinding(routePath = routePath) },
            sessionFactory = { binding -> createStreamableSession(binding) },
            requestTimeoutMillisProvider = { requestTimeoutMillis },
        )
        route.route("/{presetId}") {
            mountStreamableHttpRoute(
                sessions = sessions,
                bindingProvider = { call ->
                    val presetId =
                        call.parameters["presetId"]
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?: throw InboundPresetNotFoundException("unknown")
                    InboundSessionBinding(
                        routePath = joinRoutePath(routePath, presetId),
                        presetId = presetId,
                    )
                },
                sessionFactory = { binding -> createStreamableSession(binding) },
                requestTimeoutMillisProvider = { requestTimeoutMillis },
            )
        }
    }

    private fun refreshAllSessionServers() {
        val rawCapabilities = proxy.snapshotDownstreamCapabilities()
        val downstreams = proxy.downstreamsSnapshot()
        streamableSessions.orEmpty().forEach { session -> refreshSession(session, downstreams, rawCapabilities) }
        sseSessions.orEmpty().forEach { session -> refreshSession(session, downstreams, rawCapabilities) }
    }

    private fun refreshSession(
        session: InboundManagedSession,
        downstreams: List<McpServerConnection>,
        rawCapabilities: Map<String, ServerCapabilities>,
    ) {
        val sessionProxy = session.proxy
        if (sessionProxy !== proxy) {
            sessionProxy.updateDownstreams(downstreams)
            sessionProxy.fallbackPromptsAndResourcesToTools = proxy.fallbackPromptsAndResourcesToTools
            sessionProxy.adapterMode = proxy.adapterMode
            sessionProxy.setCapabilitiesSnapshot(rawCapabilities)
        }
        runCatching { syncSdkServer(session.sdkServer, sessionProxy, logger) }
            .onFailure { logger.warn("Failed to refresh inbound session '${session.binding.routePath}'", it) }
    }

    private suspend fun createStreamableSession(binding: InboundSessionBinding): StreamableHttpSession {
        val (sessionProxy, ownsProxy) = createSessionProxy(binding)
        val sdkServer = buildSdkServer(sessionProxy, logger)
        val transport = StreamableHttpServerTransport(logger = logger)
        val serverSession = sdkServer.createSession(transport)
        return StreamableHttpSession(
            binding = binding,
            proxy = sessionProxy,
            sdkServer = sdkServer,
            transport = transport,
            serverSession = serverSession,
            ownsProxy = ownsProxy,
        )
    }

    private suspend fun createSseSession(
        binding: InboundSessionBinding,
        transport: SseServerTransport,
    ): SseSession {
        val (sessionProxy, ownsProxy) = createSessionProxy(binding)
        val sdkServer = buildSdkServer(sessionProxy, logger)
        val serverSession = sdkServer.createSession(transport)
        return SseSession(
            binding = binding,
            proxy = sessionProxy,
            sdkServer = sdkServer,
            transport = transport,
            serverSession = serverSession,
            ownsProxy = ownsProxy,
        )
    }

    private fun createSessionProxy(binding: InboundSessionBinding): Pair<ProxyMcpServer, Boolean> {
        if (!binding.isForced) {
            return proxy to false
        }
        val presetId = requireNotNull(binding.presetId)
        val preset = resolvePreset(presetId)
        val sessionProxy =
            ProxyMcpServer(
                downstreams = proxy.downstreamsSnapshot(),
                logger = logger,
                fallbackPromptsAndResourcesToToolsEnabled = proxy.fallbackPromptsAndResourcesToTools,
                adapterModeEnabled = proxy.adapterMode,
            )
        sessionProxy.start(preset, TransportConfig.StreamableHttpTransport(url))
        sessionProxy.setCapabilitiesSnapshot(proxy.snapshotDownstreamCapabilities())
        return sessionProxy to true
    }

    private fun resolvePreset(presetId: String): Preset =
        when (presetId) {
            Preset.EMPTY_PRESET_ID -> Preset.empty()
            Preset.ALL_ENABLED_PRESET_ID -> Preset.allEnabled()
            else ->
                presetResolver
                    ?.invoke(presetId)
                    ?.getOrElse { throw InboundPresetNotFoundException(presetId) }
                    ?: throw InboundPresetNotFoundException(presetId)
        }
}

private fun joinRoutePath(
    routePath: String,
    presetId: String,
): String =
    when {
        routePath == "/" -> "/$presetId"
        routePath.endsWith("/") -> "$routePath$presetId"
        else -> "$routePath/$presetId"
    }

private fun InboundStreamableHttpRegistry?.orEmpty(): List<StreamableHttpSession> = this?.allSessions().orEmpty()

private fun InboundSseRegistry?.orEmpty(): List<SseSession> = this?.allSessions().orEmpty()

private fun resolveStartFailure(
    failure: Throwable,
    port: Int,
): String {
    val bind = failure.findCause<BindException>()
    return if (bind != null) {
        "Port $port is already in use"
    } else {
        failure.message ?: "Failed to start HTTP Streamable inbound server"
    }
}

private inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var cur: Throwable? = this
    while (cur != null) {
        if (cur is T) return cur
        cur = cur.cause
    }
    return null
}
