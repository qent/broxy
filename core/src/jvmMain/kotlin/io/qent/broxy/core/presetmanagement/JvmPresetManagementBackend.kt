package io.qent.broxy.core.presetmanagement

import io.qent.broxy.core.capabilities.PersistedCapabilityArgument
import io.qent.broxy.core.capabilities.PersistedCapabilityCacheStore
import io.qent.broxy.core.capabilities.PersistedPromptSummary
import io.qent.broxy.core.capabilities.PersistedResourceSummary
import io.qent.broxy.core.capabilities.PersistedServerCapsSnapshot
import io.qent.broxy.core.capabilities.PersistedToolSummary
import io.qent.broxy.core.capabilities.toPersistedSnapshot
import io.qent.broxy.core.config.validatePathSafePresetId
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.models.BuiltInPresetResolver
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.ToolReference
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger

@Suppress(
    "LongMethod",
    "TooManyFunctions",
    "LongParameterList",
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
    "ReturnCount",
    "ThrowsCount",
)
class JvmPresetManagementBackend(
    private val configurationRepository: ConfigurationRepository,
    private val liveCapabilitiesProvider: () -> Map<String, ServerCapabilities>,
    private val capabilityCacheStore: PersistedCapabilityCacheStore,
    private val logger: Logger = ConsoleLogger,
    private val configuredServersProvider: (() -> List<McpServerConfig>)? = null,
    private val savedPresetNamesProvider: (() -> List<NamedPresetManagementItem>)? = null,
    private val onPresetCreated: (suspend () -> Unit)? = null,
    private val agenticModeProvider: (() -> Boolean)? = null,
) : PresetManagementBackend {
    override val agenticModeEnabled: Boolean
        get() = agenticModeProvider?.invoke() == true

    override suspend fun getPresetCreationAlgorithm(): PresetCreationAlgorithmResponse =
        PresetCreationAlgorithmResponse(
            prompt =
                """
                You are creating a NEW Broxy preset for one concrete user task.
                Call get_preset_creation_algorithm first.
                Then inspect candidate servers, and if multiple servers fit ask the user which one to use.
                Select only the minimum required tools for the task.
                Then call create_preset with explicit preset_id and preset_name.
                Keep prompts/resources empty and keep the preset minimal.
                Do not edit or delete presets in this flow.
                """.trimIndent(),
            steps =
                listOf(
                    "Confirm a concrete user task before creating a preset.",
                    "Call get_preset_creation_algorithm first for the creation workflow contract.",
                    "Call list_server_names, then inspect candidate servers with get_server_description.",
                    "If multiple servers can solve the task, ask the user to choose one.",
                    "Select only the tools strictly required for the task.",
                    "Call create_preset with explicit preset_id, preset_name, and a non-empty tools list.",
                    "Do not edit or delete presets in this flow.",
                ),
        )

    override suspend fun listServerNames(): ListServerNamesResponse =
        ListServerNamesResponse(
            servers =
                configuredServers().map { server ->
                    NamedPresetManagementItem(
                        id = server.id,
                        name = server.name,
                    )
                },
        )

    override suspend fun getServerDescription(request: ServerDescriptionRequest): ServerDescriptionResponse {
        val servers = configuredServers()
        val server = resolveServer(request, servers)
        val resolved = resolveServerCapabilities(servers).getValue(server.id)
        val tools = resolved.snapshot.tools.map { it.toPayload() }
        val prompts = resolved.snapshot.prompts.map { it.toPayload() }
        val resources = resolved.snapshot.resources.map { it.toPayload() }
        val description =
            "Server '${server.name}' (id='${server.id}') capabilities source is " +
                "${resolved.source.name.lowercase()}. " +
                "Tools=${tools.size}, prompts=${prompts.size}, resources=${resources.size}."
        return ServerDescriptionResponse(
            serverId = server.id,
            serverName = server.name,
            description = description,
            capabilitiesSource = resolved.source,
            tools = tools,
            prompts = prompts,
            resources = resources,
        )
    }

    override suspend fun listPresetNames(): ListPresetNamesResponse =
        ListPresetNamesResponse(
            presets = presetNamesInOrder(),
        )

    override suspend fun getPresetDescription(request: PresetDescriptionRequest): PresetDescriptionResponse {
        val servers = configuredServers()
        val preset = resolvePreset(request, presetNamesInOrder())
        val resolvedByServerId = resolveServerCapabilities(servers)
        val serverNameById = servers.associate { it.id to it.name }

        if (preset.id == Preset.PRESET_MANAGEMENT_ID) {
            val tools =
                availableToolNames().map { name ->
                    SourcedToolCapabilityPayload(
                        name = name,
                        description = "Preset-management tool '$name'.",
                        sourceServerId = Preset.PRESET_MANAGEMENT_ID,
                        sourceServerName = Preset.presetManagement().name,
                    )
                }
            val description =
                "Preset '${preset.name}' (id='${preset.id}') exposes the fixed preset-management MCP tools only."
            return PresetDescriptionResponse(
                presetId = preset.id,
                presetName = preset.name,
                description = description,
                tools = tools,
                prompts = emptyList(),
                resources = emptyList(),
                missingCapabilities = emptyList(),
            )
        }

        if (preset.id == Preset.EMPTY_PRESET_ID) {
            return PresetDescriptionResponse(
                presetId = preset.id,
                presetName = preset.name,
                description = "Preset '${preset.name}' (id='${preset.id}') exposes no tools, prompts, or resources.",
                tools = emptyList(),
                prompts = emptyList(),
                resources = emptyList(),
                missingCapabilities = emptyList(),
            )
        }

        val tools = mutableListOf<SourcedToolCapabilityPayload>()
        val prompts = mutableListOf<SourcedPromptCapabilityPayload>()
        val resources = mutableListOf<SourcedResourceCapabilityPayload>()
        val missing = mutableListOf<MissingCapabilityPayload>()

        if (preset.id == Preset.ALL_ENABLED_PRESET_ID) {
            servers
                .filter { it.enabled }
                .forEach { server ->
                    val resolved = resolvedByServerId[server.id] ?: return@forEach
                    tools += resolved.snapshot.tools.map { it.toSourcedPayload(server.id, server.name) }
                    prompts += resolved.snapshot.prompts.map { it.toSourcedPayload(server.id, server.name) }
                    resources += resolved.snapshot.resources.map { it.toSourcedPayload(server.id, server.name) }
                }
        } else {
            val enabledTools = preset.tools.filter { it.enabled }
            val enabledPrompts = preset.prompts?.filter { it.enabled }.orEmpty()
            val enabledResources = preset.resources?.filter { it.enabled }.orEmpty()
            val inScopeServerIds =
                buildSet {
                    addAll(enabledTools.map { it.serverId })
                    addAll(enabledPrompts.map { it.serverId })
                    addAll(enabledResources.map { it.serverId })
                }

            enabledTools.forEach { ref ->
                val resolved = resolvedByServerId[ref.serverId]
                val serverName = resolved?.server?.name ?: serverNameById[ref.serverId]
                val tool = resolved?.snapshot?.tools?.firstOrNull { it.name == ref.toolName }
                if (tool == null) {
                    missing +=
                        MissingCapabilityPayload(
                            type = "tool",
                            key = ref.toolName,
                            sourceServerId = ref.serverId,
                            sourceServerName = serverName,
                        )
                } else {
                    tools += tool.toSourcedPayload(ref.serverId, serverName ?: ref.serverId)
                }
            }

            if (preset.prompts == null) {
                inScopeServerIds.forEach { serverId ->
                    val resolved = resolvedByServerId[serverId] ?: return@forEach
                    val serverName = resolved.server.name
                    prompts += resolved.snapshot.prompts.map { it.toSourcedPayload(serverId, serverName) }
                }
            } else {
                enabledPrompts.forEach { ref ->
                    val resolved = resolvedByServerId[ref.serverId]
                    val serverName = resolved?.server?.name ?: serverNameById[ref.serverId]
                    val prompt = resolved?.snapshot?.prompts?.firstOrNull { it.name == ref.promptName }
                    if (prompt == null) {
                        missing +=
                            MissingCapabilityPayload(
                                type = "prompt",
                                key = ref.promptName,
                                sourceServerId = ref.serverId,
                                sourceServerName = serverName,
                            )
                    } else {
                        prompts += prompt.toSourcedPayload(ref.serverId, serverName ?: ref.serverId)
                    }
                }
            }

            if (preset.resources == null) {
                inScopeServerIds.forEach { serverId ->
                    val resolved = resolvedByServerId[serverId] ?: return@forEach
                    val serverName = resolved.server.name
                    resources += resolved.snapshot.resources.map { it.toSourcedPayload(serverId, serverName) }
                }
            } else {
                enabledResources.forEach { ref ->
                    val resolved = resolvedByServerId[ref.serverId]
                    val serverName = resolved?.server?.name ?: serverNameById[ref.serverId]
                    val resource = resolved?.snapshot?.resources?.firstOrNull { it.key == ref.resourceKey }
                    if (resource == null) {
                        missing +=
                            MissingCapabilityPayload(
                                type = "resource",
                                key = ref.resourceKey,
                                sourceServerId = ref.serverId,
                                sourceServerName = serverName,
                            )
                    } else {
                        resources += resource.toSourcedPayload(ref.serverId, serverName ?: ref.serverId)
                    }
                }
            }
        }

        val description =
            "Preset '${preset.name}' (id='${preset.id}') exposes tools=${tools.size}, " +
                "prompts=${prompts.size}, resources=${resources.size}; " +
                "missing_capabilities=${missing.size}."
        return PresetDescriptionResponse(
            presetId = preset.id,
            presetName = preset.name,
            description = description,
            tools = tools,
            prompts = prompts,
            resources = resources,
            missingCapabilities = missing,
        )
    }

    override suspend fun createPreset(request: CreatePresetRequest): CreatePresetResponse {
        val presetId = request.presetId.trim()
        val presetName = request.presetName.trim()
        if (presetId.isBlank()) {
            throw PresetManagementException("create_preset requires non-empty preset_id")
        }
        if (presetName.isBlank()) {
            throw PresetManagementException("create_preset requires non-empty preset_name")
        }
        if (request.tools.isEmpty()) {
            throw PresetManagementException("create_preset requires a non-empty tools array")
        }
        if (presetId in BuiltInPresetResolver.builtInIds) {
            throw PresetManagementException("create_preset cannot use reserved built-in id '$presetId'")
        }
        validatePathSafePresetId(presetId).getOrElse {
            throw PresetManagementException(it.message ?: "Unsafe preset_id")
        }

        val existing = configurationRepository.listPresets()
        if (existing.any { it.id == presetId }) {
            throw PresetManagementException("create_preset cannot overwrite existing preset '$presetId'")
        }

        val toolRefs =
            request.tools
                .map { selection ->
                    val serverId = selection.serverId.trim()
                    val toolName = selection.toolName.trim()
                    if (serverId.isBlank() || toolName.isBlank()) {
                        throw PresetManagementException(
                            "create_preset tools entries require non-empty server_id and tool_name",
                        )
                    }
                    ToolReference(
                        serverId = serverId,
                        toolName = toolName,
                        enabled = true,
                    )
                }.distinctBy { ref -> ref.serverId to ref.toolName }
        if (toolRefs.isEmpty()) {
            throw PresetManagementException("create_preset requires at least one enabled tool reference")
        }

        val nextOrderIndex = (existing.maxOfOrNull { it.orderIndex } ?: -1) + 1
        val preset =
            Preset(
                id = presetId,
                name = presetName,
                tools = toolRefs,
                prompts = emptyList(),
                resources = emptyList(),
                orderIndex = nextOrderIndex,
            )
        configurationRepository.savePreset(preset)
        onPresetCreated?.invoke()
        return CreatePresetResponse(
            presetId = presetId,
            presetName = presetName,
        )
    }

    private fun presetNamesInOrder(): List<NamedPresetManagementItem> {
        val builtIns =
            BuiltInPresetResolver
                .listBuiltIns()
                .map { preset -> NamedPresetManagementItem(id = preset.id, name = preset.name) }
        val saved =
            (
                savedPresetNamesProvider?.invoke()
                    ?: configurationRepository.listPresets().map { preset ->
                        NamedPresetManagementItem(id = preset.id, name = preset.name)
                    }
            ).filterNot { it.id in BuiltInPresetResolver.builtInIds }
        return builtIns + saved
    }

    private fun configuredServers(): List<McpServerConfig> =
        configuredServersProvider?.invoke()
            ?: runCatching { configurationRepository.loadMcpConfig().servers }
                .onFailure { error ->
                    logger.warn("Failed to load MCP servers for preset management: ${error.message}", error)
                }.getOrDefault(emptyList())

    private fun resolveServer(
        request: ServerDescriptionRequest,
        servers: List<McpServerConfig>,
    ): McpServerConfig {
        val name = request.serverName.trim()
        if (name.isBlank()) throw PresetManagementException("server_name must be non-empty")
        val requestedId = request.serverId?.trim()?.takeIf { it.isNotEmpty() }
        if (requestedId != null) {
            val selected =
                servers.firstOrNull { server ->
                    server.id == requestedId && server.name.equals(name, ignoreCase = true)
                } ?: throw PresetManagementNotFoundException(
                    "Server '$name' with id '$requestedId' was not found",
                )
            return selected
        }
        val matches = servers.filter { it.name.equals(name, ignoreCase = true) }
        if (matches.isEmpty()) {
            throw PresetManagementNotFoundException("Server '$name' was not found")
        }
        if (matches.size > 1) {
            val candidates = matches.map { server -> NamedPresetManagementItem(id = server.id, name = server.name) }
            val message =
                "Server name '$name' is ambiguous. Provide server_id. Candidates: " +
                    candidates.joinToString { candidate -> "${candidate.id} (${candidate.name})" }
            throw PresetManagementAmbiguityException(message, candidates)
        }
        return matches.single()
    }

    private fun resolvePreset(
        request: PresetDescriptionRequest,
        names: List<NamedPresetManagementItem>,
    ): Preset {
        val name = request.presetName.trim()
        if (name.isBlank()) throw PresetManagementException("preset_name must be non-empty")
        val requestedId = request.presetId?.trim()?.takeIf { it.isNotEmpty() }
        val selectedName =
            if (requestedId != null) {
                names.firstOrNull { item ->
                    item.id == requestedId && item.name.equals(name, ignoreCase = true)
                } ?: throw PresetManagementNotFoundException(
                    "Preset '$name' with id '$requestedId' was not found",
                )
            } else {
                val matches = names.filter { item -> item.name.equals(name, ignoreCase = true) }
                if (matches.isEmpty()) {
                    throw PresetManagementNotFoundException("Preset '$name' was not found")
                }
                if (matches.size > 1) {
                    val message =
                        "Preset name '$name' is ambiguous. Provide preset_id. Candidates: " +
                            matches.joinToString { candidate -> "${candidate.id} (${candidate.name})" }
                    throw PresetManagementAmbiguityException(message, matches)
                }
                matches.single()
            }
        return BuiltInPresetResolver.resolve(selectedName.id)
            ?: runCatching { configurationRepository.loadPreset(selectedName.id) }
                .onFailure { error ->
                    logger.warn("Failed to load preset '${selectedName.id}' for description", error)
                }.getOrElse {
                    throw PresetManagementNotFoundException(
                        "Preset '${selectedName.name}' with id '${selectedName.id}' was not found",
                    )
                }
    }

    private fun resolveServerCapabilities(servers: List<McpServerConfig>): Map<String, ResolvedServerCapabilities> {
        val liveCapabilities =
            runCatching { liveCapabilitiesProvider() }
                .onFailure { error ->
                    logger.warn("Failed to read live capabilities for preset management: ${error.message}", error)
                }.getOrDefault(emptyMap())
        val cachedByServerId =
            runCatching { capabilityCacheStore.loadAll() }
                .onFailure { error ->
                    logger.warn("Failed to load persisted capabilities cache: ${error.message}", error)
                }.getOrDefault(emptyList())
                .groupBy { entry -> entry.serverId }
                .mapValues { (_, entries) -> entries.maxByOrNull { it.timestampMillis } }
                .mapValuesNotNull { _, entry -> entry?.snapshot }

        return servers.associate { server ->
            val live = liveCapabilities[server.id]
            val resolved =
                when {
                    live != null ->
                        ResolvedServerCapabilities(
                            server = server,
                            source = CapabilitySourceStatus.Live,
                            snapshot = live.toPersistedSnapshot(serverId = server.id, serverName = server.name),
                        )

                    cachedByServerId[server.id] != null ->
                        ResolvedServerCapabilities(
                            server = server,
                            source = CapabilitySourceStatus.Cached,
                            snapshot =
                                requireNotNull(cachedByServerId[server.id]).copy(
                                    serverId = server.id,
                                    name = server.name,
                                ),
                        )

                    else ->
                        ResolvedServerCapabilities(
                            server = server,
                            source = CapabilitySourceStatus.Missing,
                            snapshot =
                                PersistedServerCapsSnapshot(
                                    serverId = server.id,
                                    name = server.name,
                                    tools = emptyList(),
                                    prompts = emptyList(),
                                    resources = emptyList(),
                                ),
                        )
                }
            server.id to resolved
        }
    }

    private fun PersistedToolSummary.toPayload(): ToolCapabilityPayload =
        ToolCapabilityPayload(
            name = name,
            description = description,
            arguments = arguments.map { it.toPayload() },
        )

    private fun PersistedPromptSummary.toPayload(): PromptCapabilityPayload =
        PromptCapabilityPayload(
            name = name,
            description = description,
            arguments = arguments.map { it.toPayload() },
        )

    private fun PersistedResourceSummary.toPayload(): ResourceCapabilityPayload =
        ResourceCapabilityPayload(
            key = key,
            name = name,
            description = description,
            arguments = arguments.map { it.toPayload() },
        )

    private fun PersistedToolSummary.toSourcedPayload(
        serverId: String,
        serverName: String,
    ): SourcedToolCapabilityPayload =
        SourcedToolCapabilityPayload(
            name = name,
            description = description,
            arguments = arguments.map { it.toPayload() },
            sourceServerId = serverId,
            sourceServerName = serverName,
        )

    private fun PersistedPromptSummary.toSourcedPayload(
        serverId: String,
        serverName: String,
    ): SourcedPromptCapabilityPayload =
        SourcedPromptCapabilityPayload(
            name = name,
            description = description,
            arguments = arguments.map { it.toPayload() },
            sourceServerId = serverId,
            sourceServerName = serverName,
        )

    private fun PersistedResourceSummary.toSourcedPayload(
        serverId: String,
        serverName: String,
    ): SourcedResourceCapabilityPayload =
        SourcedResourceCapabilityPayload(
            key = key,
            name = name,
            description = description,
            arguments = arguments.map { it.toPayload() },
            sourceServerId = serverId,
            sourceServerName = serverName,
        )

    private fun PersistedCapabilityArgument.toPayload(): CapabilityArgumentPayload =
        CapabilityArgumentPayload(
            name = name,
            type = type,
            required = required,
        )

    private data class ResolvedServerCapabilities(
        val server: McpServerConfig,
        val source: CapabilitySourceStatus,
        val snapshot: PersistedServerCapsSnapshot,
    )
}

private inline fun <K, V, R : Any> Map<K, V>.mapValuesNotNull(transform: (K, V) -> R?): Map<K, R> =
    buildMap {
        this@mapValuesNotNull.forEach { (key, value) ->
            val mapped = transform(key, value)
            if (mapped != null) {
                put(key, mapped)
            }
        }
    }
