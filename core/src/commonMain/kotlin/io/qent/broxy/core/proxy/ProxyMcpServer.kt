package io.qent.broxy.core.proxy

import io.qent.broxy.core.mcp.McpServerConnection
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.ServerStatus
import io.qent.broxy.core.mcp.collectCapabilities
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Main broxy server. Aggregates downstream MCP servers, exposes filtered
 * capabilities based on a preset, and routes tool calls to the appropriate server.
 */
class ProxyMcpServer(
    downstreams: List<McpServerConnection>,
    private val logger: Logger = ConsoleLogger,
    private val toolFilter: ToolFilter = DefaultToolFilter(),
    private val onCapabilitiesUpdated: ((Map<String, ServerCapabilities>) -> Unit)? = null,
    fallbackPromptsAndResourcesToToolsEnabled: Boolean = false,
) : ProxyServer {
    @Volatile
    private var status: ServerStatus = ServerStatus.Stopped

    @Volatile
    private var downstreams: List<McpServerConnection> = downstreams

    @Volatile
    private var currentPreset: Preset? = null

    private val namespace: NamespaceManager = DefaultNamespaceManager()
    private val presetEngine: PresetEngine = DefaultPresetEngine(toolFilter)
    private val stateStore = CapabilitiesStateStore(presetEngine)
    private val dispatcherFactory: (List<McpServerConnection>) -> RequestDispatcher =
        { servers ->
            DefaultRequestDispatcher(
                servers = servers,
                allowedPrefixedTools = { stateStore.allowedTools() },
                allowAllWhenNoAllowedTools = false,
                promptServerResolver = { name -> stateStore.promptServerId(name) },
                resourceServerResolver = { uri -> stateStore.resourceServerId(uri) },
                namespace = namespace,
                logger = logger,
            )
        }
    private val notifyCapabilitiesUpdated: () -> Unit =
        {
            val callback = onCapabilitiesUpdated
            if (callback != null) {
                val snapshot = stateStore.snapshotCapabilities()
                runCatching { callback(snapshot) }
                    .onFailure { logger.warn("Failed to sync inbound capabilities", it) }
            }
        }

    @Volatile
    private var dispatcher: RequestDispatcher = dispatcherFactory(downstreams)

    @Volatile
    var fallbackPromptsAndResourcesToTools: Boolean = fallbackPromptsAndResourcesToToolsEnabled

    override fun start(
        preset: Preset,
        transport: TransportConfig,
    ) {
        // Store preset and bootstrap filtered view; inbound server wiring will be platform-specific.
        currentPreset = preset
        status = ServerStatus.Starting
        status = ServerStatus.Running
        logger.info("Broxy server started with preset '${preset.name}'")

        // NOTE: Inbound transport (STDIO/HTTP/WS) bindings are provided by JVM-specific adapters.
        // This commonMain class focuses on filtering/routing logic.
    }

    override fun stop() {
        status = ServerStatus.Stopping
        // Inbound transport shutdown is handled by platform-specific adapters.
        status = ServerStatus.Stopped
        logger.info("Broxy server stopped")
    }

    override fun getStatus(): ServerStatus = status

    /** Returns the current filtered capabilities view. */
    val capabilities: ServerCapabilities
        get() = stateStore.currentCapabilities()

    /** Applies a new preset at runtime and refreshes the filtered view. */
    fun applyPreset(preset: Preset) {
        currentPreset = preset
        // runBlocking keeps applyPreset synchronous for callers that expect immediate filtered updates.
        runCatching { kotlinx.coroutines.runBlocking { refreshFilteredCapabilities() } }
            .onSuccess { logger.info("Applied preset '${preset.name}'") }
            .onFailure { logger.error("Failed to apply preset '${preset.name}'", it) }
    }

    /**
     * Updates the set of downstream servers at runtime. Intended for scenarios like
     * enabling/disabling servers without restarting the inbound facade.
     *
     * Callers should invoke [refreshFilteredCapabilities] afterwards to recompute the
     * filtered view for the current preset.
     */
    fun updateDownstreams(downstreams: List<McpServerConnection>) {
        this.downstreams = downstreams
        dispatcher = dispatcherFactory(downstreams)
    }

    /** Forces re-fetch and re-filter of downstream capabilities according to the current preset. */
    suspend fun refreshFilteredCapabilities() {
        val refreshId = stateStore.nextRefreshSequence()
        val all =
            collectCapabilities(downstreams) { serverId, error ->
                logger.warn(
                    "Failed to fetch capabilities for '$serverId': ${error?.message}",
                    error,
                )
            }
        val updated = stateStore.applyFullRefresh(refreshId, all, currentPreset)
        if (updated) notifyCapabilitiesUpdated()
    }

    /** Refreshes a single server capabilities snapshot without touching other servers. */
    suspend fun refreshServerCapabilities(serverId: String) {
        val server = downstreams.firstOrNull { it.serverId == serverId } ?: return
        val refreshId = stateStore.nextRefreshSequence()
        val result =
            runCatching { server.getCapabilities(forceRefresh = true) }
                .getOrElse { Result.failure(it) }
        if (result.isFailure) {
            logger.warn("Failed to refresh capabilities for '$serverId': ${result.exceptionOrNull()?.message}")
            return
        }
        val caps = result.getOrThrow()
        val updated = stateStore.applyServerRefresh(refreshId, serverId, caps, currentPreset)
        if (updated) notifyCapabilitiesUpdated()
    }

    /** Removes a server capabilities snapshot and recomputes the filtered view. */
    suspend fun removeServerCapabilities(serverId: String) {
        val refreshId = stateStore.nextRefreshSequence()
        val updated = stateStore.removeServer(refreshId, serverId, currentPreset)
        if (updated) notifyCapabilitiesUpdated()
    }

    /**
     * Handles a tool call against the proxy. The [toolName] must be prefixed
     * in the form `serverId_toolName`.
     */
    suspend fun callTool(
        toolName: String,
        arguments: JsonObject = JsonObject(emptyMap()),
    ): Result<JsonElement> =
        dispatcher.dispatchToolCall(ToolCallRequest(toolName, arguments)).also { result ->
            if (result.isSuccess) {
                logger.info("Forwarded tool '$toolName' response back to LLM")
            } else {
                logger.error("Tool '$toolName' failed", result.exceptionOrNull())
            }
        }

    /** Fetches a prompt from the appropriate downstream based on mapping computed during filtering. */
    suspend fun getPrompt(
        name: String,
        arguments: Map<String, String>? = null,
    ): Result<JsonObject> {
        if (stateStore.currentCapabilities().prompts.none { it.name == name }) {
            return Result.failure(IllegalArgumentException("Unknown prompt: $name"))
        }
        val result = dispatcher.dispatchPrompt(name, arguments)
        if (result.isSuccess) {
            logger.info("Delivered prompt '$name' back to LLM")
        } else {
            logger.error("Prompt '$name' failed", result.exceptionOrNull())
        }
        return result
    }

    /** Reads a resource from the appropriate downstream based on mapping computed during filtering. */
    val readResource: suspend (String) -> Result<JsonObject> =
        readResource@{ uri ->
            if (stateStore.currentCapabilities().resources.none { (it.uri ?: it.name) == uri }) {
                return@readResource Result.failure(IllegalArgumentException("Unknown resource: $uri"))
            }
            val result = dispatcher.dispatchResource(uri)
            if (result.isSuccess) {
                logger.info("Delivered resource '$uri' back to LLM")
            } else {
                logger.error("Resource '$uri' failed", result.exceptionOrNull())
            }
            result
        }
}
