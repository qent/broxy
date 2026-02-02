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
@Suppress("TooManyFunctions")
class ProxyMcpServer(
    downstreams: List<McpServerConnection>,
    private val logger: Logger = ConsoleLogger,
    private val toolFilter: ToolFilter = DefaultToolFilter(),
    private val onCapabilitiesUpdated: ((Map<String, ServerCapabilities>) -> Unit)? = null,
    fallbackPromptsAndResourcesToToolsEnabled: Boolean = false,
    adapterModeEnabled: Boolean = false,
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
                resourceServerResolver = { uri -> resolveResourceServerId(uri) },
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

    @Volatile
    var adapterMode: Boolean = adapterModeEnabled

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
            if (!isResourceAvailable(uri)) {
                return@readResource Result.failure(IllegalArgumentException("Unknown resource: $uri"))
            }
            val templateKey = findMatchingResourceTemplate(uri)
            val result = dispatcher.dispatchResource(uri)
            val finalResult =
                if (result.isFailure && templateKey != null && templateKey != uri) {
                    val message = result.exceptionOrNull()?.message.orEmpty()
                    if (message.contains("Resource not found", ignoreCase = true)) {
                        dispatcher.dispatchResource(templateKey)
                    } else {
                        result
                    }
                } else {
                    result
                }
            if (finalResult.isSuccess) {
                logger.info("Delivered resource '$uri' back to LLM")
            } else {
                logger.error("Resource '$uri' failed", finalResult.exceptionOrNull())
            }
            finalResult
        }

    private fun isResourceAvailable(uri: String): Boolean {
        val resources = stateStore.currentCapabilities().resources
        if (resources.any { (it.uri ?: it.name) == uri }) return true
        return resources.any { descriptor ->
            val template = descriptor.uri ?: return@any false
            isResourceTemplate(template) && templateMatchesUri(template, uri)
        }
    }

    private fun resolveResourceServerId(uri: String): String? {
        val direct = stateStore.resourceServerId(uri)
        val templateKey = if (direct == null) findMatchingResourceTemplate(uri) else null
        return direct ?: templateKey?.let { stateStore.resourceServerId(it) }
    }

    private fun findMatchingResourceTemplate(uri: String): String? =
        stateStore
            .currentCapabilities()
            .resources
            .firstOrNull { descriptor ->
                val template = descriptor.uri ?: return@firstOrNull false
                isResourceTemplate(template) && templateMatchesUri(template, uri)
            }?.uri

    private fun isResourceTemplate(uri: String): Boolean = uri.contains('{') && uri.contains('}')

    private fun templateMatchesUri(
        template: String,
        uri: String,
    ): Boolean {
        val matches =
            RESOURCE_TEMPLATE_REGEX.findAll(template).toList()
        if (matches.isEmpty()) return false
        val regex =
            buildString {
                append("^")
                var index = 0
                matches.forEach { match ->
                    val start = match.range.first
                    val end = match.range.last + 1
                    append(Regex.escape(template.substring(index, start)))
                    append("(.+?)")
                    index = end
                }
                append(Regex.escape(template.substring(index)))
                append("$")
            }.toRegex()
        return regex.matches(uri)
    }
}

private val RESOURCE_TEMPLATE_REGEX = "\\{([^}]+)}".toRegex()
