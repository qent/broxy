package io.qent.broxy.ui.adapter.store

import io.qent.broxy.core.mcp.PromptDescriptor
import io.qent.broxy.core.mcp.ResourceDescriptor
import io.qent.broxy.core.mcp.ToolDescriptor
import io.qent.broxy.core.models.ToolReference
import io.qent.broxy.core.models.PromptReference
import io.qent.broxy.core.models.ResourceReference
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.core.utils.LogEvent
import io.qent.broxy.ui.adapter.data.provideConfigurationRepository
import io.qent.broxy.ui.adapter.models.UiHttpDraft
import io.qent.broxy.ui.adapter.models.UiHttpTransport
import io.qent.broxy.ui.adapter.models.UiCapabilityArgument
import io.qent.broxy.ui.adapter.models.UiLogEntry
import io.qent.broxy.ui.adapter.models.UiLogLevel
import io.qent.broxy.ui.adapter.models.UiMcpServerConfig
import io.qent.broxy.ui.adapter.models.UiMcpServersConfig
import io.qent.broxy.ui.adapter.models.UiPreset
import io.qent.broxy.ui.adapter.models.UiPresetCore
import io.qent.broxy.ui.adapter.models.UiPresetDraft
import io.qent.broxy.ui.adapter.models.UiProxyStatus
import io.qent.broxy.ui.adapter.models.UiPromptRef
import io.qent.broxy.ui.adapter.models.UiServerCapsSnapshot
import io.qent.broxy.ui.adapter.models.UiServer
import io.qent.broxy.ui.adapter.models.UiServerCapabilities
import io.qent.broxy.ui.adapter.models.UiServerConnStatus
import io.qent.broxy.ui.adapter.models.UiServerDraft
import io.qent.broxy.ui.adapter.models.UiStdioDraft
import io.qent.broxy.ui.adapter.models.UiStdioTransport
import io.qent.broxy.ui.adapter.models.UiStreamableHttpDraft
import io.qent.broxy.ui.adapter.models.UiStreamableHttpTransport
import io.qent.broxy.ui.adapter.models.UiToolRef
import io.qent.broxy.ui.adapter.models.UiToolSummary
import io.qent.broxy.ui.adapter.models.UiPromptSummary
import io.qent.broxy.ui.adapter.models.UiResourceRef
import io.qent.broxy.ui.adapter.models.UiResourceSummary
import io.qent.broxy.ui.adapter.models.UiTransportConfig
import io.qent.broxy.ui.adapter.models.UiTransportDraft
import io.qent.broxy.ui.adapter.models.UiWebSocketDraft
import io.qent.broxy.ui.adapter.models.UiWebSocketTransport
import io.qent.broxy.ui.adapter.proxy.ProxyController
import io.qent.broxy.ui.adapter.proxy.createProxyController
import io.qent.broxy.ui.adapter.services.fetchServerCapabilities
import io.qent.broxy.ui.adapter.store.internal.CapabilityCache
import io.qent.broxy.ui.adapter.store.internal.LogsBuffer
import io.qent.broxy.ui.adapter.store.internal.ServerStatusTracker
import io.qent.broxy.ui.adapter.store.internal.StoreSnapshot
import io.qent.broxy.ui.adapter.store.internal.toUiState
import io.qent.broxy.ui.adapter.store.internal.withPresets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val DEFAULT_CAPS_REFRESH_INTERVAL_SECONDS = 300
private const val DEFAULT_MAX_LOGS = 500

private typealias CapabilityFetcher = suspend (UiMcpServerConfig, Int) -> Result<UiServerCapabilities>

/**
 * AppStore implements UDF for the app: exposes Flow<UIState> and side-effecting intents.
 * No Compose dependencies. UI calls intents via functions inside the state.
 */
class AppStore(
    private val configurationRepository: ConfigurationRepository,
    private val proxyController: ProxyController,
    private val capabilityFetcher: CapabilityFetcher,
    private val logger: CollectingLogger,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val now: () -> Long = { System.currentTimeMillis() },
    maxLogs: Int = DEFAULT_MAX_LOGS,
    private val enableBackgroundRefresh: Boolean = true
) {
    private val capabilityCache = CapabilityCache(now)
    private val statusTracker = ServerStatusTracker()
    private val logsBuffer = LogsBuffer(maxLogs)

    private val _state = MutableStateFlow<UIState>(UIState.Loading)
    val state: StateFlow<UIState> = _state

    private var snapshot = StoreSnapshot()
    private var capsRefreshJob: Job? = null
    private val intents: Intents = StoreIntents()

    init {
        observeLogs()
    }

    fun start() {
        scope.launch {
            val loadResult = loadConfigurationSnapshot()
            if (loadResult.isFailure) {
                val msg = loadResult.exceptionOrNull()?.message ?: "Failed to load configuration"
                logger.info("[AppStore] load failed: $msg")
                setErrorState(msg)
                return@launch
            }
            publishReady()
            refreshEnabledServerCaps(force = true)
            restartCapsRefreshJob()
        }
    }

    fun getServerDraft(id: String): UiServerDraft? {
        val cfg = snapshot.servers.firstOrNull { it.id == id } ?: return null
        val draftTransport = when (val transport = cfg.transport) {
            is UiStdioTransport -> UiStdioDraft(command = transport.command, args = transport.args)
            is UiHttpTransport -> UiHttpDraft(url = transport.url, headers = transport.headers)
            is UiStreamableHttpTransport -> UiStreamableHttpDraft(url = transport.url, headers = transport.headers)
            is UiWebSocketTransport -> UiWebSocketDraft(url = transport.url)
        }
        return UiServerDraft(
            id = cfg.id,
            name = cfg.name,
            enabled = cfg.enabled,
            transport = draftTransport,
            env = cfg.env
        )
    }

    fun getPresetDraft(id: String): UiPresetDraft? {
        return runCatching { configurationRepository.loadPreset(id) }
            .map { preset ->
                UiPresetDraft(
                    id = preset.id,
                    name = preset.name,
                    description = preset.description.ifBlank { null },
                    tools = preset.tools.map { tool ->
                        UiToolRef(serverId = tool.serverId, toolName = tool.toolName, enabled = tool.enabled)
                    },
                    prompts = preset.prompts.orEmpty().map { prompt ->
                        UiPromptRef(serverId = prompt.serverId, promptName = prompt.promptName, enabled = prompt.enabled)
                    },
                    resources = preset.resources.orEmpty().map { resource ->
                        UiResourceRef(serverId = resource.serverId, resourceKey = resource.resourceKey, enabled = resource.enabled)
                    },
                    promptsConfigured = preset.prompts != null,
                    resourcesConfigured = preset.resources != null
                )
            }
            .onFailure { error ->
                logger.info("[AppStore] getPresetDraft('$id') failed: ${error.message}")
            }
            .getOrNull()
    }

    fun listServerConfigs(): List<UiMcpServerConfig> = snapshot.servers.toList()

    suspend fun listEnabledServerCaps(): List<UiServerCapsSnapshot> {
        val enabledIds = snapshot.servers.filter { it.enabled }.map { it.id }
        return capabilityCache.list(enabledIds)
    }

    suspend fun getServerCaps(serverId: String, forceRefresh: Boolean = false): UiServerCapsSnapshot? {
        val cfg = snapshot.servers.firstOrNull { it.id == serverId } ?: return null
        if (!forceRefresh) {
            val cached = capabilityCache.snapshot(serverId)
            if (cached != null) return cached
        }
        val snapshotResult = runCatching { fetchAndCacheCapabilities(cfg) }
            .onFailure { error -> logger.info("[AppStore] getServerCaps('$serverId') failed: ${error.message}") }
            .getOrNull()
        val finalSnapshot = snapshotResult ?: capabilityCache.snapshot(serverId)
        val status = if (finalSnapshot != null) UiServerConnStatus.Available else UiServerConnStatus.Error
        statusTracker.set(serverId, status)
        publishReady()
        return finalSnapshot
    }

    private fun observeLogs() {
        scope.launch {
            logger.events.collect { event ->
                logsBuffer.append(event.toUiEntry())
                publishReadyIfNotError()
            }
        }
    }

    private fun updateSnapshot(transform: StoreSnapshot.() -> StoreSnapshot) {
        snapshot = snapshot.transform()
    }

    private suspend fun loadConfigurationSnapshot(): Result<Unit> = try {
        val config = configurationRepository.loadMcpConfig()
        val loadedPresets = configurationRepository.listPresets().map { it.toUiPresetSummary() }
        capabilityCache.retain(config.servers.map { it.id }.toSet())
        statusTracker.retain(config.servers.map { it.id }.toSet())
        proxyController.updateCallTimeout(config.requestTimeoutSeconds)
        proxyController.updateCapabilitiesTimeout(config.capabilitiesTimeoutSeconds)
        updateSnapshot {
            copy(
                isLoading = false,
                servers = config.servers,
                requestTimeoutSeconds = config.requestTimeoutSeconds,
                capabilitiesTimeoutSeconds = config.capabilitiesTimeoutSeconds,
                capabilitiesRefreshIntervalSeconds = config.capabilitiesRefreshIntervalSeconds.coerceAtLeast(30),
                showTrayIcon = config.showTrayIcon
            ).withPresets(loadedPresets)
        }
        Result.success(Unit)
    } catch (t: Throwable) {
        Result.failure(t)
    }

    private fun snapshotConfig(): UiMcpServersConfig = UiMcpServersConfig(
        servers = snapshot.servers,
        requestTimeoutSeconds = snapshot.requestTimeoutSeconds,
        capabilitiesTimeoutSeconds = snapshot.capabilitiesTimeoutSeconds,
        showTrayIcon = snapshot.showTrayIcon,
        capabilitiesRefreshIntervalSeconds = snapshot.capabilitiesRefreshIntervalSeconds
    )

    private fun publishReady() {
        _state.value = snapshot.toUiState(intents, logsBuffer.snapshot(), capabilityCache, statusTracker)
    }

    private fun publishReadyIfNotError() {
        if (_state.value !is UIState.Error) {
            publishReady()
        }
    }

    private fun setErrorState(message: String) {
        _state.value = UIState.Error(message)
    }

    private fun refreshIntervalMillis(): Long = snapshot.capabilitiesRefreshIntervalSeconds.coerceAtLeast(30) * 1_000L

    private fun restartCapsRefreshJob() {
        if (!enableBackgroundRefresh) {
            capsRefreshJob?.cancel()
            capsRefreshJob = null
            return
        }
        capsRefreshJob?.cancel()
        val intervalMillis = refreshIntervalMillis()
        capsRefreshJob = scope.launch {
            while (isActive) {
                delay(intervalMillis)
                refreshEnabledServerCaps(force = false)
            }
        }
    }

    private suspend fun refreshEnabledServerCaps(force: Boolean) {
        val intervalMillis = refreshIntervalMillis()
        val targets = snapshot.servers.filter { it.enabled }
            .filter { force || capabilityCache.shouldRefresh(it.id, intervalMillis) }
        refreshServers(targets)
    }

    private suspend fun refreshServersById(targetIds: Set<String>, force: Boolean) {
        if (targetIds.isEmpty()) return
        val intervalMillis = refreshIntervalMillis()
        val targets = snapshot.servers.filter { it.id in targetIds && it.enabled }
            .filter { force || capabilityCache.shouldRefresh(it.id, intervalMillis) }
        refreshServers(targets)
    }

    private suspend fun refreshServers(targets: List<UiMcpServerConfig>) {
        if (targets.isEmpty()) return
        val targetIds = targets.map { it.id }
        statusTracker.setAll(targetIds, UiServerConnStatus.Connecting)
        publishReady()

        val results = coroutineScope {
            targets.map { cfg ->
                async {
                    val snapshot = runCatching { fetchAndCacheCapabilities(cfg) }
                        .onFailure { error ->
                            logger.info("[AppStore] refresh server '${cfg.id}' failed: ${error.message}")
                        }
                        .getOrNull()
                    if (snapshot == null && !capabilityCache.has(cfg.id)) {
                        capabilityCache.remove(cfg.id)
                    }
                    cfg.id to (snapshot ?: capabilityCache.snapshot(cfg.id))
                }
            }.awaitAll()
        }

        val currentServers = snapshot.servers.associateBy { it.id }
        results.forEach { (serverId, capsSnapshot) ->
            val enabled = currentServers[serverId]?.enabled == true
            val status = when {
                !enabled -> UiServerConnStatus.Disabled
                capsSnapshot != null -> UiServerConnStatus.Available
                else -> UiServerConnStatus.Error
            }
            statusTracker.set(serverId, status)
        }
        publishReady()
    }

    private suspend fun fetchAndCacheCapabilities(cfg: UiMcpServerConfig): UiServerCapsSnapshot? {
        val result = capabilityFetcher(cfg, snapshot.capabilitiesTimeoutSeconds)
        return if (result.isSuccess) {
            val snapshot = result.getOrThrow().toSnapshot(cfg)
            capabilityCache.put(cfg.id, snapshot)
            snapshot
        } else {
            null
        }
    }

    private fun restartProxyWithPreset(presetId: String, presetOverride: UiPresetCore? = null) {
        if (snapshot.proxyStatus !is UiProxyStatus.Running) return
        val inboundTransport = snapshot.activeInbound ?: return
        val presetResult = presetOverride?.let { Result.success(it) }
            ?: runCatching { configurationRepository.loadPreset(presetId) }
        if (presetResult.isFailure) {
            val msg = presetResult.exceptionOrNull()?.message ?: "Failed to load preset for restart"
            logger.info("[AppStore] restartProxyWithPreset failed to load preset '$presetId': $msg")
            updateSnapshot { copy(proxyStatus = UiProxyStatus.Error(msg), activeProxyPresetId = null, activeInbound = null) }
            publishReady()
            return
        }
        val preset = presetResult.getOrThrow()
        updateSnapshot { copy(proxyStatus = UiProxyStatus.Starting) }
        publishReady()
        val result = proxyController.start(
            servers = snapshot.servers,
            preset = preset,
            inbound = inboundTransport,
            callTimeoutSeconds = snapshot.requestTimeoutSeconds,
            capabilitiesTimeoutSeconds = snapshot.capabilitiesTimeoutSeconds
        )
        if (result.isSuccess) {
            updateSnapshot {
                copy(
                    activeProxyPresetId = presetId,
                    proxyStatus = UiProxyStatus.Running
                )
            }
        } else {
            val msg = result.exceptionOrNull()?.message ?: "Failed to restart proxy"
            logger.info("[AppStore] restartProxyWithPreset failed for '$presetId': $msg")
            updateSnapshot {
                copy(
                    proxyStatus = UiProxyStatus.Error(msg),
                    activeProxyPresetId = null,
                    activeInbound = null
                )
            }
        }
        publishReady()
    }

    private fun revertServersOnFailure(
        operation: String,
        previousServers: List<UiMcpServerConfig>,
        failure: Throwable?,
        defaultMessage: String
    ) {
        val message = failure?.message ?: defaultMessage
        logger.info("[AppStore] $operation failed: $message")
        updateSnapshot { copy(servers = previousServers) }
        setErrorState(message)
    }

    private fun revertPresetsOnFailure(
        operation: String,
        previousPresets: List<UiPreset>,
        failure: Throwable?,
        defaultMessage: String
    ) {
        val message = failure?.message ?: defaultMessage
        logger.info("[AppStore] $operation failed: $message")
        updateSnapshot { copy(presets = previousPresets) }
        setErrorState(message)
    }

    private inner class StoreIntents : Intents {
        override fun refresh() {
            scope.launch {
                val refreshResult = loadConfigurationSnapshot()
                if (refreshResult.isFailure) {
                    val msg = refreshResult.exceptionOrNull()?.message ?: "Failed to refresh"
                    logger.info("[AppStore] refresh failed: $msg")
                    setErrorState(msg)
                }
                publishReady()
                refreshEnabledServerCaps(force = true)
                restartCapsRefreshJob()
            }
        }

        override fun addOrUpdateServerUi(ui: UiServer) {
            scope.launch {
                val previousServers = snapshot.servers
                val updated = previousServers.toMutableList()
                val idx = updated.indexOfFirst { it.id == ui.id }
                val base = updated.getOrNull(idx) ?: UiMcpServerConfig(
                    id = ui.id,
                    name = ui.name,
                    transport = UiStdioTransport(command = ""),
                    enabled = ui.enabled
                )
                val merged = base.copy(name = ui.name, enabled = ui.enabled)
                if (idx >= 0) updated[idx] = merged else updated += merged
                updateSnapshot { copy(servers = updated) }
                capabilityCache.updateName(ui.id, ui.name)
                val result = runCatching { configurationRepository.saveMcpConfig(snapshotConfig()) }
                if (result.isFailure) {
                    revertServersOnFailure("addOrUpdateServerUi", previousServers, result.exceptionOrNull(), "Failed to save servers")
                }
                publishReady()
                scope.launch { refreshServersById(setOf(ui.id), force = true) }
            }
        }

        override fun addServerBasic(id: String, name: String) {
            scope.launch {
                val previousServers = snapshot.servers
                if (previousServers.any { it.id == id }) return@launch
                val updated = previousServers.toMutableList().apply {
                    add(UiMcpServerConfig(id = id, name = name, transport = UiStdioTransport(command = ""), enabled = true))
                }
                updateSnapshot { copy(servers = updated) }
                val result = runCatching { configurationRepository.saveMcpConfig(snapshotConfig()) }
                if (result.isFailure) {
                    revertServersOnFailure("addServerBasic", previousServers, result.exceptionOrNull(), "Failed to save servers")
                }
                publishReady()
            }
        }

        override fun upsertServer(draft: UiServerDraft) {
            scope.launch {
                val previousServers = snapshot.servers
                val updated = previousServers.toMutableList()
                val idx = updated.indexOfFirst { it.id == draft.id }
                val cfg = UiMcpServerConfig(
                    id = draft.id,
                    name = draft.name,
                    enabled = draft.enabled,
                    transport = draft.transport.toTransportConfig(),
                    env = draft.env
                )
                if (idx >= 0) updated[idx] = cfg else updated += cfg
                updateSnapshot { copy(servers = updated) }
                val result = runCatching { configurationRepository.saveMcpConfig(snapshotConfig()) }
                if (result.isFailure) {
                    revertServersOnFailure("upsertServer", previousServers, result.exceptionOrNull(), "Failed to save server")
                }
                publishReady()
                scope.launch { refreshServersById(setOf(draft.id), force = true) }
            }
        }

        override fun removeServer(id: String) {
            scope.launch {
                val previousServers = snapshot.servers
                val updated = previousServers.filterNot { it.id == id }
                updateSnapshot { copy(servers = updated) }
                capabilityCache.remove(id)
                statusTracker.remove(id)
                val result = runCatching { configurationRepository.saveMcpConfig(snapshotConfig()) }
                if (result.isFailure) {
                    revertServersOnFailure("removeServer", previousServers, result.exceptionOrNull(), "Failed to save servers")
                }
                publishReady()
            }
        }

        override fun toggleServer(id: String, enabled: Boolean) {
            scope.launch {
                val previousServers = snapshot.servers
                val idx = previousServers.indexOfFirst { it.id == id }
                if (idx < 0) return@launch
                val updated = previousServers.toMutableList()
                updated[idx] = updated[idx].copy(enabled = enabled)
                updateSnapshot { copy(servers = updated) }
                val result = runCatching { configurationRepository.saveMcpConfig(snapshotConfig()) }
                if (result.isFailure) {
                    revertServersOnFailure("toggleServer", previousServers, result.exceptionOrNull(), "Failed to save server state")
                }
                if (!enabled) {
                    capabilityCache.remove(id)
                    statusTracker.set(id, UiServerConnStatus.Disabled)
                }
                publishReady()
                if (enabled) {
                    scope.launch { refreshServersById(setOf(id), force = true) }
                }
            }
        }

        override fun addOrUpdatePreset(preset: UiPreset) {
            scope.launch {
                val previousPresets = snapshot.presets
                val updated = previousPresets.toMutableList()
                val idx = updated.indexOfFirst { it.id == preset.id }
                if (idx >= 0) updated[idx] = preset else updated += preset
                updateSnapshot { copy(presets = updated) }
                val result = runCatching {
                    val presetCore = UiPresetCore(
                        id = preset.id,
                        name = preset.name,
                        description = preset.description ?: "",
                        tools = emptyList(),
                        prompts = null,
                        resources = null
                    )
                    configurationRepository.savePreset(presetCore)
                }
                if (result.isFailure) {
                    revertPresetsOnFailure("addOrUpdatePreset", previousPresets, result.exceptionOrNull(), "Failed to save preset")
                }
                publishReady()
            }
        }

        override fun upsertPreset(draft: UiPresetDraft) {
            scope.launch {
                val preset = draft.toCorePreset()
                val saveResult = runCatching { configurationRepository.savePreset(preset) }
                if (saveResult.isFailure) {
                    val msg = saveResult.exceptionOrNull()?.message ?: "Failed to save preset"
                    logger.info("[AppStore] upsertPreset failed: $msg")
                    setErrorState(msg)
                }
                val summary = preset.toUiPresetSummary(draft.description)
                val updated = snapshot.presets.toMutableList()
                val idx = updated.indexOfFirst { it.id == summary.id }
                if (idx >= 0) updated[idx] = summary else updated += summary
                updateSnapshot { copy(presets = updated) }
                val shouldRestart = saveResult.isSuccess &&
                    snapshot.proxyStatus is UiProxyStatus.Running &&
                    snapshot.activeProxyPresetId == preset.id &&
                    snapshot.activeInbound != null
                publishReady()
                if (shouldRestart) {
                    restartProxyWithPreset(preset.id, preset)
                }
            }
        }

        override fun removePreset(id: String) {
            scope.launch {
                val previousPresets = snapshot.presets
                val updated = previousPresets.filterNot { it.id == id }
                updateSnapshot { copy(presets = updated) }
                val result = runCatching { configurationRepository.deletePreset(id) }
                if (result.isFailure) {
                    revertPresetsOnFailure("removePreset", previousPresets, result.exceptionOrNull(), "Failed to delete preset")
                }
                publishReady()
            }
        }

        override fun selectProxyPreset(presetId: String?) {
            scope.launch {
                if (snapshot.selectedPresetId == presetId) return@launch
                updateSnapshot { copy(selectedPresetId = presetId) }
                publishReady()
                val shouldRestart = presetId != null &&
                    snapshot.proxyStatus is UiProxyStatus.Running &&
                    snapshot.activeInbound != null &&
                    presetId != snapshot.activeProxyPresetId
                if (shouldRestart) {
                    restartProxyWithPreset(presetId!!)
                }
            }
        }

        override fun startProxySimple(presetId: String) {
            scope.launch {
                val presetResult = runCatching { configurationRepository.loadPreset(presetId) }
                if (presetResult.isFailure) {
                    val msg = presetResult.exceptionOrNull()?.message ?: "Failed to load preset"
                    logger.info("[AppStore] startProxySimple failed: $msg")
                    updateSnapshot {
                        copy(
                            proxyStatus = UiProxyStatus.Error(msg),
                            selectedPresetId = presetId,
                            activeProxyPresetId = null,
                            activeInbound = null
                        )
                    }
                    publishReady()
                    return@launch
                }
                val preset = presetResult.getOrThrow()
                val inbound = UiHttpTransport("http://0.0.0.0:3335/mcp")
                updateSnapshot { copy(proxyStatus = UiProxyStatus.Starting, selectedPresetId = presetId) }
                publishReady()
                val result = proxyController.start(
                    servers = snapshot.servers,
                    preset = preset,
                    inbound = inbound,
                    callTimeoutSeconds = snapshot.requestTimeoutSeconds,
                    capabilitiesTimeoutSeconds = snapshot.capabilitiesTimeoutSeconds
                )
                if (result.isSuccess) {
                    updateSnapshot {
                        copy(
                            proxyStatus = UiProxyStatus.Running,
                            activeProxyPresetId = presetId,
                            activeInbound = inbound
                        )
                    }
                } else {
                    val msg = result.exceptionOrNull()?.message ?: "Failed to start proxy"
                    logger.info("[AppStore] startProxySimple failed to start proxy: $msg")
                    updateSnapshot {
                        copy(
                            proxyStatus = UiProxyStatus.Error(msg),
                            activeProxyPresetId = null,
                            activeInbound = null
                        )
                    }
                }
                publishReady()
            }
        }

        override fun startProxy(presetId: String, inbound: UiTransportDraft) {
            scope.launch {
                val presetResult = runCatching { configurationRepository.loadPreset(presetId) }
                if (presetResult.isFailure) {
                    val msg = presetResult.exceptionOrNull()?.message ?: "Failed to load preset"
                    logger.info("[AppStore] startProxy failed: $msg")
                    updateSnapshot {
                        copy(
                            proxyStatus = UiProxyStatus.Error(msg),
                            selectedPresetId = presetId,
                            activeProxyPresetId = null,
                            activeInbound = null
                        )
                    }
                    publishReady()
                    return@launch
                }
                val preset = presetResult.getOrThrow()
                val inboundTransport = inbound.toTransportConfig()
                if (inboundTransport !is UiStdioTransport && inboundTransport !is UiHttpTransport) {
                    val msg = "Inbound transport not supported. Use Local (STDIO) or Remote (SSE)."
                    logger.info("[AppStore] startProxy unsupported inbound: ${inbound::class.simpleName}")
                    updateSnapshot {
                        copy(
                            proxyStatus = UiProxyStatus.Error(msg),
                            selectedPresetId = presetId,
                            activeProxyPresetId = null,
                            activeInbound = null
                        )
                    }
                    publishReady()
                    return@launch
                }
                updateSnapshot { copy(proxyStatus = UiProxyStatus.Starting, selectedPresetId = presetId) }
                publishReady()
                val result = proxyController.start(
                    servers = snapshot.servers,
                    preset = preset,
                    inbound = inboundTransport,
                    callTimeoutSeconds = snapshot.requestTimeoutSeconds,
                    capabilitiesTimeoutSeconds = snapshot.capabilitiesTimeoutSeconds
                )
                if (result.isSuccess) {
                    updateSnapshot {
                        copy(
                            proxyStatus = UiProxyStatus.Running,
                            activeProxyPresetId = presetId,
                            activeInbound = inboundTransport
                        )
                    }
                } else {
                    val msg = result.exceptionOrNull()?.message ?: "Failed to start proxy"
                    logger.info("[AppStore] startProxy failed to start proxy: $msg")
                    updateSnapshot {
                        copy(
                            proxyStatus = UiProxyStatus.Error(msg),
                            activeProxyPresetId = null,
                            activeInbound = null
                        )
                    }
                }
                publishReady()
            }
        }

        override fun stopProxy() {
            scope.launch {
                updateSnapshot { copy(proxyStatus = UiProxyStatus.Stopping) }
                publishReady()
                val result = proxyController.stop()
                if (result.isSuccess) {
                    updateSnapshot {
                        copy(
                            proxyStatus = UiProxyStatus.Stopped,
                            activeProxyPresetId = null,
                            activeInbound = null
                        )
                    }
                } else {
                    val msg = result.exceptionOrNull()?.message ?: "Failed to stop proxy"
                    updateSnapshot { copy(proxyStatus = UiProxyStatus.Error(msg)) }
                }
                publishReady()
            }
        }

        override fun updateRequestTimeout(seconds: Int) {
            scope.launch {
                val previous = snapshot.requestTimeoutSeconds
                updateSnapshot { copy(requestTimeoutSeconds = seconds) }
                proxyController.updateCallTimeout(snapshot.requestTimeoutSeconds)
                val result = runCatching { configurationRepository.saveMcpConfig(snapshotConfig()) }
                if (result.isFailure) {
                    val msg = result.exceptionOrNull()?.message ?: "Failed to update timeout"
                    logger.info("[AppStore] updateRequestTimeout failed: $msg")
                    updateSnapshot { copy(requestTimeoutSeconds = previous) }
                    proxyController.updateCallTimeout(previous)
                    setErrorState(msg)
                }
                publishReady()
            }
        }

        override fun updateCapabilitiesTimeout(seconds: Int) {
            scope.launch {
                val previous = snapshot.capabilitiesTimeoutSeconds
                updateSnapshot { copy(capabilitiesTimeoutSeconds = seconds) }
                proxyController.updateCapabilitiesTimeout(snapshot.capabilitiesTimeoutSeconds)
                val result = runCatching { configurationRepository.saveMcpConfig(snapshotConfig()) }
                if (result.isFailure) {
                    val msg = result.exceptionOrNull()?.message ?: "Failed to update capabilities timeout"
                    logger.info("[AppStore] updateCapabilitiesTimeout failed: $msg")
                    updateSnapshot { copy(capabilitiesTimeoutSeconds = previous) }
                    proxyController.updateCapabilitiesTimeout(previous)
                    setErrorState(msg)
                }
                publishReady()
            }
        }

        override fun updateCapabilitiesRefreshInterval(seconds: Int) {
            scope.launch {
                val clamped = seconds.coerceAtLeast(30)
                if (snapshot.capabilitiesRefreshIntervalSeconds == clamped) return@launch
                val previous = snapshot.capabilitiesRefreshIntervalSeconds
                updateSnapshot { copy(capabilitiesRefreshIntervalSeconds = clamped) }
                val result = runCatching { configurationRepository.saveMcpConfig(snapshotConfig()) }
                if (result.isFailure) {
                    val msg = result.exceptionOrNull()?.message ?: "Failed to update refresh interval"
                    logger.info("[AppStore] updateCapabilitiesRefreshInterval failed: $msg")
                    updateSnapshot { copy(capabilitiesRefreshIntervalSeconds = previous) }
                    setErrorState(msg)
                } else {
                    restartCapsRefreshJob()
                    refreshEnabledServerCaps(force = true)
                }
                publishReady()
            }
        }

        override fun updateTrayIconVisibility(visible: Boolean) {
            scope.launch {
                if (snapshot.showTrayIcon == visible) return@launch
                val previous = snapshot.showTrayIcon
                updateSnapshot { copy(showTrayIcon = visible) }
                val result = runCatching { configurationRepository.saveMcpConfig(snapshotConfig()) }
                if (result.isFailure) {
                    val msg = result.exceptionOrNull()?.message ?: "Failed to update tray preference"
                    logger.info("[AppStore] updateTrayIconVisibility failed: $msg")
                    updateSnapshot { copy(showTrayIcon = previous) }
                    setErrorState(msg)
                }
                publishReady()
            }
        }
    }
}

fun createAppStore(
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    logger: CollectingLogger = CollectingLogger(),
    repository: ConfigurationRepository = provideConfigurationRepository(),
    proxyFactory: (CollectingLogger) -> ProxyController = { createProxyController(it) },
    capabilityFetcher: CapabilityFetcher = { config, timeout -> fetchServerCapabilities(config, timeout, logger) },
    now: () -> Long = { System.currentTimeMillis() },
    maxLogs: Int = DEFAULT_MAX_LOGS,
    enableBackgroundRefresh: Boolean = true
): AppStore = AppStore(
    configurationRepository = repository,
    proxyController = proxyFactory(logger),
    capabilityFetcher = capabilityFetcher,
    logger = logger,
    scope = scope,
    now = now,
    maxLogs = maxLogs,
    enableBackgroundRefresh = enableBackgroundRefresh
)

private fun UiPresetCore.toUiPresetSummary(): UiPreset = UiPreset(
    id = id,
    name = name,
    description = description.ifBlank { null },
    toolsCount = tools.count { it.enabled },
    promptsCount = prompts?.count { it.enabled } ?: 0,
    resourcesCount = resources?.count { it.enabled } ?: 0
)

private fun UiPresetCore.toUiPresetSummary(descriptionOverride: String?): UiPreset = UiPreset(
    id = id,
    name = name,
    description = descriptionOverride ?: description.ifBlank { null },
    toolsCount = tools.count { it.enabled },
    promptsCount = prompts?.count { it.enabled } ?: 0,
    resourcesCount = resources?.count { it.enabled } ?: 0
)

private fun UiPresetDraft.toCorePreset(): UiPresetCore = UiPresetCore(
    id = id,
    name = name,
    description = description ?: "",
    tools = tools.map { tool ->
        ToolReference(serverId = tool.serverId, toolName = tool.toolName, enabled = tool.enabled)
    },
    prompts = if (promptsConfigured) {
        prompts.map { prompt ->
            PromptReference(serverId = prompt.serverId, promptName = prompt.promptName, enabled = prompt.enabled)
        }
    } else {
        null
    },
    resources = if (resourcesConfigured) {
        resources.map { resource ->
            ResourceReference(serverId = resource.serverId, resourceKey = resource.resourceKey, enabled = resource.enabled)
        }
    } else {
        null
    }
)

private fun UiTransportDraft.toTransportConfig(): UiTransportConfig = when (this) {
    is UiStdioDraft -> UiStdioTransport(command = command, args = args)
    is UiHttpDraft -> UiHttpTransport(url = url, headers = headers)
    is UiStreamableHttpDraft -> UiStreamableHttpTransport(url = url, headers = headers)
    is UiWebSocketDraft -> UiWebSocketTransport(url = url)
}

private fun UiServerCapabilities.toSnapshot(config: UiMcpServerConfig): UiServerCapsSnapshot = UiServerCapsSnapshot(
    serverId = config.id,
    name = config.name,
    tools = tools.map { it.toUiToolSummary() },
    prompts = prompts.map { it.toUiPromptSummary() },
    resources = resources.map { it.toUiResourceSummary() }
)

private fun String?.orNullIfBlank(): String? = this?.takeIf { it.isNotBlank() }

private fun ToolDescriptor.toUiToolSummary(): UiToolSummary {
    val descriptionText = description.orNullIfBlank() ?: title.orNullIfBlank()
    val arguments = extractToolArguments()
    return UiToolSummary(
        name = name,
        description = descriptionText,
        arguments = arguments
    )
}

private fun PromptDescriptor.toUiPromptSummary(): UiPromptSummary {
    val argumentSummaries = arguments.orEmpty().map { promptArg ->
        UiCapabilityArgument(
            name = promptArg.name,
            type = "string",
            required = promptArg.required == true
        )
    }
    return UiPromptSummary(
        name = name,
        description = description.orNullIfBlank(),
        arguments = argumentSummaries
    )
}

private fun ResourceDescriptor.toUiResourceSummary(): UiResourceSummary {
    val argumentSummaries = inferResourceArguments(uri)
    return UiResourceSummary(
        key = uri.orNullIfBlank() ?: name,
        name = name,
        description = description.orNullIfBlank()
            ?: title.orNullIfBlank()
            ?: uri.orNullIfBlank(),
        arguments = argumentSummaries
    )
}

private fun ToolDescriptor.extractToolArguments(): List<UiCapabilityArgument> {
    val schema = inputSchema ?: return emptyList()
    if (schema.properties.isEmpty()) return emptyList()
    val requiredKeys = schema.required?.toSet().orEmpty()
    return schema.properties.mapNotNull { (propertyName, schemaElement) ->
        val typeLabel = schemaElement.schemaTypeLabel() ?: "unspecified"
        UiCapabilityArgument(
            name = propertyName,
            type = typeLabel,
            required = propertyName in requiredKeys
        )
    }
}

private fun JsonElement.schemaTypeLabel(): String? = when (this) {
    is JsonObject -> this.schemaTypeLabel()
    is JsonArray -> mapNotNull { it.schemaTypeLabel() }
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(" | ")
        .ifBlank { null }
    else -> null
}

private fun JsonObject.schemaTypeLabel(): String? {
    (this["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { baseType ->
        return baseType.withFormatSuffix(this)
    }
    (this["type"] as? JsonArray)?.let { array ->
        val combined = array
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" | ")
        if (combined.isNotBlank()) return combined.withFormatSuffix(this)
    }
    val items = this["items"]
    if (items != null) {
        val itemType = items.schemaTypeLabel()
        val label = if (itemType != null) "array<$itemType>" else "array"
        return label.withFormatSuffix(this)
    }
    val anyOf = this["anyOf"] as? JsonArray
    if (anyOf != null) {
        val combined = anyOf.mapNotNull { it.schemaTypeLabel() }.filter { it.isNotBlank() }
        if (combined.isNotEmpty()) return combined.joinToString(" | ").withFormatSuffix(this)
    }
    val oneOf = this["oneOf"] as? JsonArray
    if (oneOf != null) {
        val combined = oneOf.mapNotNull { it.schemaTypeLabel() }.filter { it.isNotBlank() }
        if (combined.isNotEmpty()) return combined.joinToString(" | ").withFormatSuffix(this)
    }
    val allOf = this["allOf"] as? JsonArray
    if (allOf != null) {
        val combined = allOf.mapNotNull { it.schemaTypeLabel() }.filter { it.isNotBlank() }
        if (combined.isNotEmpty()) return combined.joinToString(" & ").withFormatSuffix(this)
    }
    if (this["enum"] is JsonArray) {
        return "enum".withFormatSuffix(this)
    }
    if (this["const"] != null) {
        return "const".withFormatSuffix(this)
    }
    if (this["properties"] is JsonObject) {
        return "object".withFormatSuffix(this)
    }
    return formatSuffix(this)
}

private fun formatSuffix(node: JsonObject): String? =
    (node["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun String.withFormatSuffix(node: JsonObject): String {
    val format = formatSuffix(node)
    return if (format != null && format.isNotBlank()) "$this<$format>" else this
}

private val URI_TEMPLATE_PARAM_REGEX = Regex("\\{([^{}]+)}")

private fun inferResourceArguments(uri: String?): List<UiCapabilityArgument> {
    if (uri.isNullOrBlank()) return emptyList()
    val seen = mutableSetOf<String>()
    return URI_TEMPLATE_PARAM_REGEX.findAll(uri).mapNotNull { match ->
        val raw = match.groupValues.getOrNull(1)?.trim().orEmpty()
        if (raw.isEmpty()) return@mapNotNull null
        val breakIndex = raw.indexOfAny(charArrayOf(',', ':', '*', '?'))
        val extracted = if (breakIndex >= 0) raw.substring(0, breakIndex) else raw
        val name = extracted.trim().trimStart('+', '#', '/', '.', ';')
        if (name.isEmpty() || !seen.add(name)) return@mapNotNull null
        UiCapabilityArgument(
            name = name,
            type = "string",
            required = true
        )
    }
        .toList()
}

private fun LogEvent.toUiEntry(): UiLogEntry = UiLogEntry(
    timestampMillis = timestampMillis,
    level = UiLogLevel.valueOf(level.name),
    message = message,
    throwableMessage = throwableMessage
)
