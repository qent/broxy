package io.qent.broxy.core.proxy

import io.qent.broxy.core.mcp.McpServerConnection
import io.qent.broxy.core.mcp.MultiServerClient
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.ServerStatus
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Main broxy server. Aggregates downstream MCP servers, exposes filtered
 * capabilities based on a preset, and routes tool calls to the appropriate server.
 */
class ProxyMcpServer(
    private val downstreams: List<McpServerConnection>,
    private val logger: Logger = ConsoleLogger,
    private val toolFilter: ToolFilter = DefaultToolFilter()
) : ProxyServer {
    @Volatile
    private var status: ServerStatus = ServerStatus.Stopped

    private var currentPreset: Preset? = null
    private var filteredCaps: ServerCapabilities = ServerCapabilities()
    private var allowedTools: Set<String> = emptySet()
    private var promptServerByName: Map<String, String> = emptyMap()
    private var resourceServerByUri: Map<String, String> = emptyMap()

    private val namespace: NamespaceManager = DefaultNamespaceManager()
    private val presetEngine: PresetEngine = DefaultPresetEngine(toolFilter)
    private var dispatcher: RequestDispatcher = DefaultRequestDispatcher(
        servers = downstreams,
        allowedPrefixedTools = { allowedTools },
        promptServerResolver = { name -> promptServerByName[name] },
        resourceServerResolver = { uri -> resourceServerByUri[uri] },
        namespace = namespace,
        logger = logger
    )

    private val multi = MultiServerClient(downstreams)

    override fun start(preset: Preset, transport: TransportConfig) {
        // Store preset and bootstrap filtered view; inbound server wiring will be platform-specific.
        currentPreset = preset
        status = ServerStatus.Starting

        // Fire-and-forget; block-free bootstrap since interface here is sync.
        // Callers on JVM may wrap in coroutines for actual async startup.
        // We compute filtered caps lazily on first demand via refresh call below.
        runCatching {
            // Best-effort eager refresh
            kotlinx.coroutines.runBlocking { refreshFilteredCapabilities() }
            status = ServerStatus.Running
            logger.info("broxy server started with preset '${preset.name}'")
        }.onFailure {
            status = ServerStatus.Error(it.message)
            logger.error("Failed to start broxy server", it)
        }

        // NOTE: Inbound transport (STDIO/HTTP/WS) bindings are provided by JVM-specific adapters.
        // This commonMain class focuses on filtering/routing logic.
    }

    override fun stop() {
        status = ServerStatus.Stopping
        // Inbound transport shutdown is handled by platform-specific adapters.
        status = ServerStatus.Stopped
        logger.info("broxy server stopped")
    }

    override fun getStatus(): ServerStatus = status

    /** Returns the current filtered capabilities view. */
    fun getCapabilities(): ServerCapabilities = filteredCaps

    /** Applies a new preset at runtime and refreshes the filtered view. */
    fun applyPreset(preset: Preset) {
        currentPreset = preset
        runCatching { kotlinx.coroutines.runBlocking { refreshFilteredCapabilities() } }
            .onSuccess { logger.info("Applied preset '${preset.name}'") }
            .onFailure { logger.error("Failed to apply preset '${preset.name}'", it) }
    }

    /** Forces re-fetch and re-filter of downstream capabilities according to the current preset. */
    suspend fun refreshFilteredCapabilities() {
        val preset = currentPreset ?: return
        val all = fetchAllDownstreamCapabilities()
        val result = presetEngine.apply(all, preset)
        filteredCaps = result.capabilities
        allowedTools = result.allowedPrefixedTools
        promptServerByName = result.promptServerByName
        resourceServerByUri = result.resourceServerByUri

        // Log missing tools once on refresh
        result.missingTools.forEach {
            logger.warn("Missing tool in downstream: server='${it.serverId}', tool='${it.toolName}'")
        }
    }

    /**
     * Handles a tool call against the proxy. The [toolName] must be prefixed
     * in the form `serverId:toolName`.
     */
    suspend fun callTool(toolName: String, arguments: JsonObject = JsonObject(emptyMap())): Result<JsonElement> =
        dispatcher.dispatchToolCall(ToolCallRequest(toolName, arguments)).also { result ->
            if (result.isSuccess) {
                logger.info("Forwarded tool '$toolName' response back to LLM")
            } else {
                logger.error("Tool '$toolName' failed", result.exceptionOrNull())
            }
        }

    /** Fetches a prompt from the appropriate downstream based on mapping computed during filtering. */
    suspend fun getPrompt(name: String, arguments: Map<String, String>? = null): Result<JsonObject> {
        val result = dispatcher.dispatchPrompt(name, arguments)
        if (result.isSuccess) {
            logger.info("Delivered prompt '$name' back to LLM")
        } else {
            logger.error("Prompt '$name' failed", result.exceptionOrNull())
        }
        return result
    }

    /** Reads a resource from the appropriate downstream based on mapping computed during filtering. */
    suspend fun readResource(uri: String): Result<JsonObject> {
        val result = dispatcher.dispatchResource(uri)
        if (result.isSuccess) {
            logger.info("Delivered resource '$uri' back to LLM")
        } else {
            logger.error("Resource '$uri' failed", result.exceptionOrNull())
        }
        return result
    }

    private suspend fun fetchAllDownstreamCapabilities(): Map<String, ServerCapabilities> = coroutineScope {
        // Ensure downstream servers are connected, then fetch caps
        downstreams.map { s ->
            async {
                s.connect()
                val caps = s.getCapabilities()
                if (caps.isSuccess) s.serverId to caps.getOrThrow() else null
            }
        }.awaitAll().filterNotNull().toMap()
    }
}
