package io.qent.broxy.ui.adapter.store

import io.qent.broxy.core.models.ToolReference
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.ui.adapter.data.provideConfigurationRepository
import io.qent.broxy.ui.adapter.models.UiHttpDraft
import io.qent.broxy.ui.adapter.models.UiHttpTransport
import io.qent.broxy.ui.adapter.models.UiLogEntry
import io.qent.broxy.ui.adapter.models.UiLogLevel
import io.qent.broxy.ui.adapter.models.UiMcpServerConfig
import io.qent.broxy.ui.adapter.models.UiMcpServersConfig
import io.qent.broxy.ui.adapter.models.UiPreset
import io.qent.broxy.ui.adapter.models.UiPresetCore
import io.qent.broxy.ui.adapter.models.UiPresetDraft
import io.qent.broxy.ui.adapter.models.UiProxyStatus
import io.qent.broxy.ui.adapter.models.UiServer
import io.qent.broxy.ui.adapter.models.UiServerCapsSnapshot
import io.qent.broxy.ui.adapter.models.UiServerCapabilities
import io.qent.broxy.ui.adapter.models.UiServerConnStatus
import io.qent.broxy.ui.adapter.models.UiServerDraft
import io.qent.broxy.ui.adapter.models.UiStdioDraft
import io.qent.broxy.ui.adapter.models.UiStdioTransport
import io.qent.broxy.ui.adapter.models.UiStreamableHttpDraft
import io.qent.broxy.ui.adapter.models.UiStreamableHttpTransport
import io.qent.broxy.ui.adapter.models.UiToolRef
import io.qent.broxy.ui.adapter.models.UiTransportConfig
import io.qent.broxy.ui.adapter.models.UiTransportDraft
import io.qent.broxy.ui.adapter.models.UiWebSocketDraft
import io.qent.broxy.ui.adapter.models.UiWebSocketTransport
import io.qent.broxy.ui.adapter.proxy.ProxyController
import io.qent.broxy.ui.adapter.proxy.createProxyController
import io.qent.broxy.ui.adapter.services.fetchServerCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private const val DEFAULT_CAPS_CACHE_TTL_MILLIS = 5 * 60 * 1000L
private const val DEFAULT_MAX_LOGS = 500

private typealias CapabilityFetcher = suspend (UiMcpServerConfig) -> Result<UiServerCapabilities>

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
    private val capsCacheTtlMillis: Long = DEFAULT_CAPS_CACHE_TTL_MILLIS,
    private val maxLogs: Int = DEFAULT_MAX_LOGS
) {
    private data class CachedCaps(val snapshot: UiServerCapsSnapshot, val timestampMillis: Long)

    private val _state = MutableStateFlow<UIState>(UIState.Loading)
    val state: StateFlow<UIState> = _state

    private val servers = mutableListOf<UiMcpServerConfig>()
    private val presets = mutableListOf<UiPreset>()
    private var proxyStatus: UiProxyStatus = UiProxyStatus.Stopped
    private var selectedProxyPresetId: String? = null
    private var activeProxyPresetId: String? = null
    private var activeInbound: UiTransportConfig? = null
    private var requestTimeoutSeconds: Int = 60
    private var showTrayIcon: Boolean = true
    private val logs = mutableListOf<UiLogEntry>()
    private val capsCache = mutableMapOf<String, CachedCaps>()
    private val serverStatus = mutableMapOf<String, UiServerConnStatus>()

    init {
        scope.launch {
            logger.events.collect { event ->
                val uiEntry = UiLogEntry(
                    timestampMillis = event.timestampMillis,
                    level = UiLogLevel.valueOf(event.level.name),
                    message = event.message,
                    throwableMessage = event.throwableMessage
                )
                synchronized(logs) {
                    logs += uiEntry
                    if (logs.size > maxLogs) logs.removeAt(0)
                }
                if (_state.value !is UIState.Error) {
                    publishReady()
                }
            }
        }
    }

    fun start() {
        scope.launch {
            val loadResult = loadConfigurationSnapshot()
            if (loadResult.isFailure) {
                val msg = loadResult.exceptionOrNull()?.message ?: "Failed to load configuration"
                logger.info("[AppStore] load failed: $msg")
                _state.value = UIState.Error(msg)
                return@launch
            }
            publishReady()
            scope.launch { refreshServerCapsAndPublish() }
        }
    }

    fun getServerDraft(id: String): UiServerDraft? {
        val cfg = servers.firstOrNull { it.id == id } ?: return null
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
                    }
                )
            }
            .onFailure { error ->
                logger.info("[AppStore] getPresetDraft('$id') failed: ${error.message}")
            }
            .getOrNull()
    }

    fun listServerConfigs(): List<UiMcpServerConfig> = servers.toList()

    suspend fun listEnabledServerCaps(): List<UiServerCapsSnapshot> = coroutineScope {
        val enabled = servers.filter { it.enabled }
        if (enabled.isEmpty()) return@coroutineScope emptyList()

        val resolved = mutableMapOf<String, UiServerCapsSnapshot>()
        val missing = mutableListOf<UiMcpServerConfig>()

        enabled.forEach { cfg ->
            val cached = getCachedCaps(cfg.id)
            if (cached != null) {
                resolved[cfg.id] = cached
            } else {
                missing += cfg
            }
        }

        if (missing.isNotEmpty()) {
            val fetched = missing.map { cfg ->
                async { fetchAndCacheCapabilities(cfg) }
            }.awaitAll()

            fetched.forEachIndexed { index, snapshot ->
                val cfg = missing[index]
                if (snapshot != null) {
                    resolved[cfg.id] = snapshot
                }
            }

            publishReady()
        }

        enabled.mapNotNull { resolved[it.id] }
    }

    private fun loadConfigurationSnapshot(): Result<Unit> = runCatching {
        val config = configurationRepository.loadMcpConfig()
        requestTimeoutSeconds = config.requestTimeoutSeconds
        showTrayIcon = config.showTrayIcon
        proxyController.updateCallTimeout(requestTimeoutSeconds)

        val loadedPresets = configurationRepository.listPresets()
        servers.apply {
            clear()
            addAll(config.servers)
        }
        presets.apply {
            clear()
            addAll(loadedPresets.map { it.toUiPresetSummary() })
        }

        val validIds = servers.mapTo(mutableSetOf()) { it.id }
        serverStatus.keys.retainAll(validIds)
        synchronized(capsCache) {
            capsCache.keys.retainAll(validIds)
        }
    }

    private suspend fun fetchAndCacheCapabilities(cfg: UiMcpServerConfig): UiServerCapsSnapshot? {
        val result = capabilityFetcher(cfg)
        return if (result.isSuccess) {
            val snapshot = result.getOrThrow().toSnapshot(cfg)
            putCachedCaps(cfg.id, snapshot)
            serverStatus[cfg.id] = UiServerConnStatus.Available
            snapshot
        } else {
            removeCachedCaps(cfg.id)
            serverStatus[cfg.id] = UiServerConnStatus.Error
            null
        }
    }

    private fun reconcilePresetSelection() {
        val selected = selectedProxyPresetId
        if (selected != null && presets.none { it.id == selected }) {
            selectedProxyPresetId = null
        }
        val active = activeProxyPresetId
        if (active != null && presets.none { it.id == active }) {
            activeProxyPresetId = null
        }
    }

    private fun publishReady() {
        reconcilePresetSelection()
        val uiServers = servers.map { server ->
            val label = when (server.transport) {
                is UiStdioTransport -> "STDIO"
                is UiHttpTransport -> "HTTP"
                is UiStreamableHttpTransport -> "HTTP (Streamable)"
                is UiWebSocketTransport -> "WebSocket"
            }
            val snapshot = getCachedCaps(server.id)
            val status = when {
                !server.enabled -> UiServerConnStatus.Disabled
                snapshot != null -> UiServerConnStatus.Available
                serverStatus[server.id] == UiServerConnStatus.Error -> UiServerConnStatus.Error
                else -> UiServerConnStatus.Connecting
            }
            UiServer(
                id = server.id,
                name = server.name,
                transportLabel = label,
                enabled = server.enabled,
                status = status,
                toolsCount = snapshot?.tools?.size,
                promptsCount = snapshot?.prompts?.size,
                resourcesCount = snapshot?.resources?.size
            )
        }
        _state.value = UIState.Ready(
            servers = uiServers,
            presets = presets.toList(),
            selectedPresetId = selectedProxyPresetId,
            proxyStatus = proxyStatus,
            requestTimeoutSeconds = requestTimeoutSeconds,
            showTrayIcon = showTrayIcon,
            logs = synchronized(logs) { logs.asReversed().toList() },
            intents = intents
        )
    }

    private fun snapshotConfig(): UiMcpServersConfig = UiMcpServersConfig(
        servers = servers.toList(),
        requestTimeoutSeconds = requestTimeoutSeconds,
        showTrayIcon = showTrayIcon
    )

    private fun getCachedCaps(serverId: String): UiServerCapsSnapshot? = synchronized(capsCache) {
        val entry = capsCache[serverId] ?: return@synchronized null
        return if (now() - entry.timestampMillis <= capsCacheTtlMillis) {
            entry.snapshot
        } else {
            capsCache.remove(serverId)
            null
        }
    }

    private fun putCachedCaps(serverId: String, snapshot: UiServerCapsSnapshot) {
        val entry = CachedCaps(snapshot = snapshot, timestampMillis = now())
        synchronized(capsCache) { capsCache[serverId] = entry }
    }

    private fun removeCachedCaps(serverId: String) {
        synchronized(capsCache) { capsCache.remove(serverId) }
    }

    private fun updateCachedCapsName(serverId: String, name: String) {
        synchronized(capsCache) {
            val entry = capsCache[serverId] ?: return@synchronized
            capsCache[serverId] = entry.copy(snapshot = entry.snapshot.copy(name = name))
        }
    }

    private suspend fun refreshServerCapsAndPublish(targetServerIds: Collection<String>? = null) {
        val targetSet = targetServerIds?.toSet()
        if (targetSet != null) {
            val existingIds = servers.mapTo(mutableSetOf()) { it.id }
            val removedIds = targetSet - existingIds
            removedIds.forEach { id ->
                serverStatus.remove(id)
                removeCachedCaps(id)
            }
        }
        val targets = if (targetSet == null) servers.toList() else servers.filter { it.id in targetSet }
        if (targets.isEmpty()) {
            publishReady()
            return
        }
        targets.forEach { cfg ->
            val status = if (!cfg.enabled) UiServerConnStatus.Disabled else UiServerConnStatus.Connecting
            serverStatus[cfg.id] = status
        }
        publishReady()
        targets.filter { it.enabled }.forEach { cfg ->
            scope.launch {
                fetchAndCacheCapabilities(cfg)
                publishReady()
            }
        }
    }

    private fun restartProxyWithPreset(presetId: String, presetOverride: UiPresetCore? = null) {
        if (proxyStatus !is UiProxyStatus.Running) return
        val inboundTransport = activeInbound ?: return
        val presetResult = presetOverride?.let { Result.success(it) } ?: runCatching { configurationRepository.loadPreset(presetId) }
        if (presetResult.isFailure) {
            val msg = presetResult.exceptionOrNull()?.message ?: "Failed to load preset for restart"
            logger.info("[AppStore] restartProxyWithPreset failed to load preset '$presetId': $msg")
            proxyStatus = UiProxyStatus.Error(msg)
            activeProxyPresetId = null
            activeInbound = null
            publishReady()
            return
        }
        val preset = presetResult.getOrThrow()
        val result = proxyController.start(servers.toList(), preset, inboundTransport, requestTimeoutSeconds)
        if (result.isSuccess) {
            activeProxyPresetId = presetId
            proxyStatus = UiProxyStatus.Running
        } else {
            val msg = result.exceptionOrNull()?.message ?: "Failed to restart proxy"
            logger.info("[AppStore] restartProxyWithPreset failed for '$presetId': $msg")
            proxyStatus = UiProxyStatus.Error(msg)
            activeProxyPresetId = null
            activeInbound = null
        }
        publishReady()
    }

    private fun revertServersOnFailure(
        operation: String,
        snapshot: List<UiMcpServerConfig>,
        failure: Throwable?,
        defaultMessage: String
    ) {
        val message = failure?.message ?: defaultMessage
        logger.info("[AppStore] $operation failed: $message")
        servers.apply {
            clear()
            addAll(snapshot)
        }
        _state.value = UIState.Error(message)
    }

    private fun revertPresetsOnFailure(
        operation: String,
        snapshot: List<UiPreset>,
        failure: Throwable?,
        defaultMessage: String
    ) {
        val message = failure?.message ?: defaultMessage
        logger.info("[AppStore] $operation failed: $message")
        presets.apply {
            clear()
            addAll(snapshot)
        }
        _state.value = UIState.Error(message)
    }

    private val intents = object : Intents {
        override fun refresh() {
            scope.launch {
                val refreshResult = loadConfigurationSnapshot()
                if (refreshResult.isFailure) {
                    val msg = refreshResult.exceptionOrNull()?.message ?: "Failed to refresh"
                    logger.info("[AppStore] refresh failed: $msg")
                    _state.value = UIState.Error(msg)
                }
                publishReady()
                refreshServerCapsAndPublish()
            }
        }

        override fun addOrUpdateServerUi(ui: UiServer) {
            scope.launch {
                val snapshot = servers.toList()
                val currentIndex = servers.indexOfFirst { it.id == ui.id }
                val current = servers.getOrNull(currentIndex)
                val updated = (current ?: UiMcpServerConfig(
                    id = ui.id,
                    name = ui.name,
                    transport = UiStdioTransport(command = ""),
                    enabled = ui.enabled
                )).copy(name = ui.name, enabled = ui.enabled)
                if (currentIndex >= 0) servers[currentIndex] = updated else servers += updated
                updateCachedCapsName(ui.id, ui.name)
                val result = runCatching { configurationRepository.saveMcpConfig(snapshotConfig()) }
                if (result.isFailure) {
                    revertServersOnFailure("addOrUpdateServerUi", snapshot, result.exceptionOrNull(), "Failed to save servers")
                }
                publishReady()
                refreshServerCapsAndPublish(setOf(ui.id))
            }
        }

        override fun upsertServer(draft: UiServerDraft) {
            scope.launch {
                val cfg = UiMcpServerConfig(
                    id = draft.id,
                    name = draft.name,
                    enabled = draft.enabled,
                    transport = draft.transport.toTransportConfig(),
                    env = draft.env
                )
                val snapshot = servers.toList()
                val idx = servers.indexOfFirst { it.id == cfg.id }
                if (idx >= 0) servers[idx] = cfg else servers += cfg
                removeCachedCaps(cfg.id)
                val result = runCatching { configurationRepository.saveMcpConfig(snapshotConfig()) }
                if (result.isFailure) {
                    revertServersOnFailure("upsertServer", snapshot, result.exceptionOrNull(), "Failed to save servers")
                }
                publishReady()
                refreshServerCapsAndPublish(setOf(cfg.id))
            }
        }

        override fun addServerBasic(id: String, name: String) {
            scope.launch {
                val cfg = UiMcpServerConfig(id = id, name = name, transport = UiStdioTransport(command = ""), enabled = true)
                val snapshot = servers.toList()
                val idx = servers.indexOfFirst { it.id == cfg.id }
                if (idx >= 0) servers[idx] = cfg else servers += cfg
                val result = runCatching { configurationRepository.saveMcpConfig(snapshotConfig()) }
                if (result.isFailure) {
                    revertServersOnFailure("addServerBasic", snapshot, result.exceptionOrNull(), "Failed to save servers")
                }
                publishReady()
                refreshServerCapsAndPublish(setOf(cfg.id))
            }
        }

        override fun removeServer(id: String) {
            scope.launch {
                val snapshot = servers.toList()
                servers.removeAll { it.id == id }
                removeCachedCaps(id)
                val result = runCatching { configurationRepository.saveMcpConfig(snapshotConfig()) }
                if (result.isFailure) {
                    revertServersOnFailure("removeServer", snapshot, result.exceptionOrNull(), "Failed to save servers")
                }
                publishReady()
                refreshServerCapsAndPublish(setOf(id))
            }
        }

        override fun toggleServer(id: String, enabled: Boolean) {
            scope.launch {
                val snapshot = servers.toList()
                val idx = servers.indexOfFirst { it.id == id }
                if (idx >= 0) {
                    servers[idx] = servers[idx].copy(enabled = enabled)
                    val result = runCatching { configurationRepository.saveMcpConfig(snapshotConfig()) }
                    if (result.isFailure) {
                        revertServersOnFailure("toggleServer", snapshot, result.exceptionOrNull(), "Failed to save server state")
                    }
                }
                publishReady()
                refreshServerCapsAndPublish(setOf(id))
            }
        }

        override fun addOrUpdatePreset(preset: UiPreset) {
            scope.launch {
                val snapshot = presets.toList()
                val idx = presets.indexOfFirst { it.id == preset.id }
                if (idx >= 0) presets[idx] = preset else presets += preset
                val result = runCatching {
                    val presetCore = UiPresetCore(
                        id = preset.id,
                        name = preset.name,
                        description = preset.description ?: "",
                        tools = emptyList()
                    )
                    configurationRepository.savePreset(presetCore)
                }
                if (result.isFailure) {
                    revertPresetsOnFailure("addOrUpdatePreset", snapshot, result.exceptionOrNull(), "Failed to save preset")
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
                    _state.value = UIState.Error(msg)
                }
                val summary = preset.toUiPresetSummary(draft.description)
                val idx = presets.indexOfFirst { it.id == summary.id }
                if (idx >= 0) presets[idx] = summary else presets += summary
                val shouldRestart = saveResult.isSuccess &&
                    proxyStatus is UiProxyStatus.Running &&
                    activeProxyPresetId == preset.id &&
                    activeInbound != null
                publishReady()
                if (shouldRestart) {
                    restartProxyWithPreset(preset.id, preset)
                }
            }
        }

        override fun removePreset(id: String) {
            scope.launch {
                val snapshot = presets.toList()
                presets.removeAll { it.id == id }
                val result = runCatching { configurationRepository.deletePreset(id) }
                if (result.isFailure) {
                    revertPresetsOnFailure("removePreset", snapshot, result.exceptionOrNull(), "Failed to delete preset")
                }
                publishReady()
            }
        }

        override fun selectProxyPreset(presetId: String?) {
            scope.launch {
                if (selectedProxyPresetId == presetId) return@launch
                selectedProxyPresetId = presetId
                publishReady()
                val shouldRestart = presetId != null &&
                    proxyStatus is UiProxyStatus.Running &&
                    activeInbound != null &&
                    presetId != activeProxyPresetId
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
                    proxyStatus = UiProxyStatus.Error(msg)
                    selectedProxyPresetId = presetId
                    activeProxyPresetId = null
                    activeInbound = null
                    publishReady()
                    return@launch
                }
                val preset = presetResult.getOrThrow()
                val inbound = UiHttpTransport("http://0.0.0.0:3335/mcp")
                val result = proxyController.start(servers.toList(), preset, inbound, requestTimeoutSeconds)
                selectedProxyPresetId = presetId
                if (result.isSuccess) {
                    proxyStatus = UiProxyStatus.Running
                    activeProxyPresetId = presetId
                    activeInbound = inbound
                } else {
                    val msg = result.exceptionOrNull()?.message ?: "Failed to start proxy"
                    logger.info("[AppStore] startProxySimple failed to start proxy: $msg")
                    proxyStatus = UiProxyStatus.Error(msg)
                    activeProxyPresetId = null
                    activeInbound = null
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
                    proxyStatus = UiProxyStatus.Error(msg)
                    selectedProxyPresetId = presetId
                    activeProxyPresetId = null
                    activeInbound = null
                    publishReady()
                    return@launch
                }
                val preset = presetResult.getOrThrow()
                val inboundTransport = inbound.toTransportConfig()
                val result = proxyController.start(servers.toList(), preset, inboundTransport, requestTimeoutSeconds)
                selectedProxyPresetId = presetId
                if (result.isSuccess) {
                    proxyStatus = UiProxyStatus.Running
                    activeProxyPresetId = presetId
                    activeInbound = inboundTransport
                } else {
                    val msg = result.exceptionOrNull()?.message ?: "Failed to start proxy"
                    logger.info("[AppStore] startProxy failed to start proxy: $msg")
                    proxyStatus = UiProxyStatus.Error(msg)
                    activeProxyPresetId = null
                    activeInbound = null
                }
                publishReady()
            }
        }

        override fun stopProxy() {
            scope.launch {
                val result = proxyController.stop()
                proxyStatus = if (result.isSuccess) UiProxyStatus.Stopped
                else UiProxyStatus.Error(result.exceptionOrNull()?.message ?: "Failed to stop proxy")
                if (result.isSuccess) {
                    activeProxyPresetId = null
                    activeInbound = null
                }
                publishReady()
            }
        }

        override fun updateRequestTimeout(seconds: Int) {
            scope.launch {
                val previous = requestTimeoutSeconds
                requestTimeoutSeconds = seconds
                proxyController.updateCallTimeout(requestTimeoutSeconds)
                val result = runCatching { configurationRepository.saveMcpConfig(snapshotConfig()) }
                if (result.isFailure) {
                    val msg = result.exceptionOrNull()?.message ?: "Failed to update timeout"
                    logger.info("[AppStore] updateRequestTimeout failed: $msg")
                    requestTimeoutSeconds = previous
                    proxyController.updateCallTimeout(requestTimeoutSeconds)
                    _state.value = UIState.Error(msg)
                }
                publishReady()
            }
        }

        override fun updateTrayIconVisibility(visible: Boolean) {
            scope.launch {
                if (showTrayIcon == visible) return@launch
                val previous = showTrayIcon
                showTrayIcon = visible
                val result = runCatching { configurationRepository.saveMcpConfig(snapshotConfig()) }
                if (result.isFailure) {
                    val msg = result.exceptionOrNull()?.message ?: "Failed to update tray preference"
                    logger.info("[AppStore] updateTrayIconVisibility failed: $msg")
                    showTrayIcon = previous
                    _state.value = UIState.Error(msg)
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
    capabilityFetcher: CapabilityFetcher = { config -> fetchServerCapabilities(config, logger) },
    now: () -> Long = { System.currentTimeMillis() },
    capsCacheTtlMillis: Long = DEFAULT_CAPS_CACHE_TTL_MILLIS,
    maxLogs: Int = DEFAULT_MAX_LOGS
): AppStore = AppStore(
    configurationRepository = repository,
    proxyController = proxyFactory(logger),
    capabilityFetcher = capabilityFetcher,
    logger = logger,
    scope = scope,
    now = now,
    capsCacheTtlMillis = capsCacheTtlMillis,
    maxLogs = maxLogs
)

private fun UiPresetCore.toUiPresetSummary(): UiPreset = UiPreset(
    id = id,
    name = name,
    description = description.ifBlank { null },
    toolsCount = tools.count { it.enabled }
)

private fun UiPresetCore.toUiPresetSummary(descriptionOverride: String?): UiPreset = UiPreset(
    id = id,
    name = name,
    description = descriptionOverride ?: description.ifBlank { null },
    toolsCount = tools.count { it.enabled }
)

private fun UiPresetDraft.toCorePreset(): UiPresetCore = UiPresetCore(
    id = id,
    name = name,
    description = description ?: "",
    tools = tools.map { tool -> ToolReference(serverId = tool.serverId, toolName = tool.toolName, enabled = tool.enabled) }
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
    tools = tools.map { it.name },
    prompts = prompts.map { it.name },
    resources = resources.map { it.uri ?: it.name }
)
