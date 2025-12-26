package io.qent.broxy.core.proxy

import io.qent.broxy.core.mcp.McpServerConnection
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.ServerStatus
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    fallbackPromptsAndResourcesToTools: Boolean = false,
) : ProxyServer {
    @Volatile
    private var status: ServerStatus = ServerStatus.Stopped

    @Volatile
    private var downstreams: List<McpServerConnection> = downstreams

    @Volatile
    private var currentPreset: Preset? = null

    private data class FilteredState(
        val capabilities: ServerCapabilities = ServerCapabilities(),
        val allowedTools: Set<String> = emptySet(),
        val promptServerByName: Map<String, String> = emptyMap(),
        val resourceServerByUri: Map<String, String> = emptyMap(),
    )

    @Volatile
    private var filteredState: FilteredState = FilteredState()

    private val refreshMutex = Mutex()
    private var allCapabilities: Map<String, ServerCapabilities> = emptyMap()

    private val namespace: NamespaceManager = DefaultNamespaceManager()
    private val presetEngine: PresetEngine = DefaultPresetEngine(toolFilter)

    @Volatile
    private var dispatcher: RequestDispatcher = buildDispatcher(downstreams)

    @Volatile
    private var fallbackPromptsAndResourcesToTools: Boolean = fallbackPromptsAndResourcesToTools

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
    fun getCapabilities(): ServerCapabilities = filteredState.capabilities

    fun shouldFallbackPromptsAndResourcesToTools(): Boolean = fallbackPromptsAndResourcesToTools

    fun updateFallbackPromptsAndResourcesToTools(enabled: Boolean) {
        fallbackPromptsAndResourcesToTools = enabled
    }

    /** Applies a new preset at runtime and refreshes the filtered view. */
    fun applyPreset(preset: Preset) {
        currentPreset = preset
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
        dispatcher = buildDispatcher(downstreams)
    }

    /** Forces re-fetch and re-filter of downstream capabilities according to the current preset. */
    suspend fun refreshFilteredCapabilities() {
        val all = fetchAllDownstreamCapabilities()
        val updated =
            refreshMutex.withLock {
                val preset = currentPreset ?: return@withLock false
                allCapabilities = all
                updateFilteredState(all, preset)
                true
            }
        if (updated) {
            notifyCapabilitiesUpdated()
        }
    }

    /** Refreshes a single server capabilities snapshot without touching other servers. */
    suspend fun refreshServerCapabilities(serverId: String) {
        val server = downstreams.firstOrNull { it.serverId == serverId } ?: return
        val result =
            runCatching { server.getCapabilities() }
                .getOrElse { Result.failure(it) }
        if (result.isFailure) {
            logger.warn("Failed to refresh capabilities for '$serverId': ${result.exceptionOrNull()?.message}")
            return
        }
        val caps = result.getOrThrow()
        val updated =
            refreshMutex.withLock {
                val next = allCapabilities + (serverId to caps)
                allCapabilities = next
                val preset = currentPreset ?: return@withLock false
                updateFilteredState(next, preset)
                true
            }
        if (updated) {
            notifyCapabilitiesUpdated()
        }
    }

    /** Removes a server capabilities snapshot and recomputes the filtered view. */
    suspend fun removeServerCapabilities(serverId: String) {
        val updated =
            refreshMutex.withLock {
                allCapabilities = allCapabilities - serverId
                val preset = currentPreset ?: return@withLock false
                updateFilteredState(allCapabilities, preset)
                true
            }
        if (updated) {
            notifyCapabilitiesUpdated()
        }
    }

    /**
     * Handles a tool call against the proxy. The [toolName] must be prefixed
     * in the form `serverId:toolName`.
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
        if (filteredState.capabilities.prompts.none { it.name == name }) {
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
    suspend fun readResource(uri: String): Result<JsonObject> {
        if (filteredState.capabilities.resources.none { (it.uri ?: it.name) == uri }) {
            return Result.failure(IllegalArgumentException("Unknown resource: $uri"))
        }
        val result = dispatcher.dispatchResource(uri)
        if (result.isSuccess) {
            logger.info("Delivered resource '$uri' back to LLM")
        } else {
            logger.error("Resource '$uri' failed", result.exceptionOrNull())
        }
        return result
    }

    private suspend fun fetchAllDownstreamCapabilities(): Map<String, ServerCapabilities> =
        coroutineScope {
            // Ensure downstream servers are connected, then fetch caps
            val servers = downstreams
            servers.map { s ->
                async {
                    val caps =
                        runCatching { s.getCapabilities() }
                            .getOrElse { Result.failure(it) }
                    if (caps.isSuccess) s.serverId to caps.getOrThrow() else null
                }
            }.awaitAll().filterNotNull().toMap()
        }

    private fun updateFilteredState(
        all: Map<String, ServerCapabilities>,
        preset: Preset,
    ) {
        val result = presetEngine.apply(all, preset)
        filteredState =
            FilteredState(
                capabilities = result.capabilities,
                allowedTools = result.allowedPrefixedTools,
                promptServerByName = result.promptServerByName,
                resourceServerByUri = result.resourceServerByUri,
            )
        result.missingTools.forEach {
            logger.warn("Missing tool in downstream: server='${it.serverId}', tool='${it.toolName}'")
        }
    }

    private fun notifyCapabilitiesUpdated() {
        val callback = onCapabilitiesUpdated ?: return
        val snapshot = allCapabilities.toMap()
        runCatching { callback(snapshot) }
            .onFailure { logger.warn("Failed to sync inbound capabilities", it) }
    }

    private fun buildDispatcher(servers: List<McpServerConnection>): RequestDispatcher =
        DefaultRequestDispatcher(
            servers = servers,
            allowedPrefixedTools = { filteredState.allowedTools },
            allowAllWhenNoAllowedTools = false,
            promptServerResolver = { name -> filteredState.promptServerByName[name] },
            resourceServerResolver = { uri -> filteredState.resourceServerByUri[uri] },
            namespace = namespace,
            logger = logger,
        )
}
