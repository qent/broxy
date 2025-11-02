package io.qent.bro.ui.adapter.store

import io.qent.bro.core.utils.CollectingLogger
import io.qent.bro.ui.adapter.data.provideConfigurationRepository
import io.qent.bro.ui.adapter.models.UiHttpDraft
import io.qent.bro.ui.adapter.models.UiMcpServerConfig
import io.qent.bro.ui.adapter.models.UiMcpServersConfig
import io.qent.bro.ui.adapter.models.UiPreset
import io.qent.bro.ui.adapter.models.UiPresetDraft
import io.qent.bro.ui.adapter.models.UiProxyStatus
import io.qent.bro.ui.adapter.models.UiServer
import io.qent.bro.ui.adapter.models.UiServerDraft
import io.qent.bro.ui.adapter.models.UiTransportConfig
import io.qent.bro.ui.adapter.models.UiStdioDraft
import io.qent.bro.ui.adapter.models.UiStdioTransport
import io.qent.bro.ui.adapter.models.UiHttpTransport
import io.qent.bro.ui.adapter.models.UiStreamableHttpTransport
import io.qent.bro.ui.adapter.models.UiStreamableHttpDraft
import io.qent.bro.ui.adapter.models.UiToolRef
import io.qent.bro.ui.adapter.models.UiWebSocketDraft
import io.qent.bro.ui.adapter.models.UiWebSocketTransport
import io.qent.bro.ui.adapter.models.UiServerCapsSnapshot
import io.qent.bro.ui.adapter.models.UiServerConnStatus
import io.qent.bro.ui.adapter.models.UiLogEntry
import io.qent.bro.ui.adapter.models.UiLogLevel
import io.qent.bro.ui.adapter.services.fetchServerCapabilities
import io.qent.bro.ui.adapter.proxy.createProxyController
import io.qent.bro.ui.adapter.models.UiPresetCore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * AppStore implements UDF for the app: exposes Flow<UIState> and side-effecting intents.
 * No Compose dependencies. UI calls intents via functions inside the state.
 */
class AppStore(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private val _state = MutableStateFlow<UIState>(UIState.Loading)
    val state: StateFlow<UIState> = _state

    // Backing store
    private val servers = mutableListOf<UiMcpServerConfig>()
    private val presets = mutableListOf<UiPreset>()
    private var proxyStatus: UiProxyStatus = UiProxyStatus.Stopped
    private var selectedProxyPresetId: String? = null
    private var activeProxyPresetId: String? = null
    private var activeInbound: UiTransportConfig? = null
    private val repo = provideConfigurationRepository()
    private val logger = CollectingLogger()
    private data class CachedCaps(val snapshot: UiServerCapsSnapshot, val timestampMillis: Long)
    private val capsCache = mutableMapOf<String, CachedCaps>()
    private val capsCacheTtlMillis: Long = 5 * 60 * 1000L
    private val proxy = createProxyController(logger)
    private var requestTimeoutSeconds: Int = 60
    private var showTrayIcon: Boolean = true
    private val logs = mutableListOf<UiLogEntry>()
    private val maxLogs = 500

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
                publishReady()
            }
        }
    }

    fun start() {
        scope.launch {
            val load = runCatching {
                val cfg = repo.loadMcpConfig()
                requestTimeoutSeconds = cfg.requestTimeoutSeconds
                showTrayIcon = cfg.showTrayIcon
                proxy.updateCallTimeout(requestTimeoutSeconds)
                val loadedPresets = repo.listPresets()
                servers.clear(); servers.addAll(cfg.servers)
                presets.clear(); presets.addAll(loadedPresets.map { p ->
                    UiPreset(
                        id = p.id,
                        name = p.name,
                        description = p.description.ifBlank { null },
                        toolsCount = p.tools.count { it.enabled }
                    )
                })
            }
            if (load.isFailure) {
                val msg = load.exceptionOrNull()?.message ?: "Failed to load configuration"
                println("[AppStore] load failed: ${'$'}msg")
                _state.value = UIState.Error(msg)
                return@launch
            }
            publishReady()
            // Kick off capability scan to populate counts
            scope.launch { refreshServerCapsAndPublish() }
        }
    }

    // Exposed helpers (pure UI drafts) — no core leak to UI
    fun getServerDraft(id: String): UiServerDraft? {
        val cfg = servers.firstOrNull { it.id == id } ?: return null
        val draftTransport = when (val t = cfg.transport) {
            is UiStdioTransport -> UiStdioDraft(command = t.command, args = t.args)
            is UiHttpTransport -> UiHttpDraft(url = t.url, headers = t.headers)
            is UiStreamableHttpTransport -> UiStreamableHttpDraft(url = t.url, headers = t.headers)
            is UiWebSocketTransport -> UiWebSocketDraft(url = t.url)
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
        return runCatching {
            val p = repo.loadPreset(id)
            UiPresetDraft(
                id = p.id,
                name = p.name,
                description = p.description.ifBlank { null },
                tools = p.tools.map { t -> UiToolRef(serverId = t.serverId, toolName = t.toolName, enabled = t.enabled) }
            )
        }.onFailure { e ->
            println("[AppStore] getPresetDraft('$id') failed: ${'$'}{e.message}")
        }.getOrNull()
    }

    // Expose full server configs for UI consumers (e.g., capability selectors)
    fun listServerConfigs(): List<UiMcpServerConfig> = servers.toList()

    /** Fetch capabilities for all currently enabled servers; failures are skipped. */
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
                async {
                    val r = fetchServerCapabilities(cfg, logger)
                    if (r.isSuccess) {
                        val caps = r.getOrNull()!!
                        val snapshot = UiServerCapsSnapshot(
                            serverId = cfg.id,
                            name = cfg.name,
                            tools = caps.tools.map { it.name },
                            prompts = caps.prompts.map { it.name },
                            resources = caps.resources.map { it.uri ?: it.name }
                        )
                        putCachedCaps(cfg.id, snapshot)
                        serverStatus[cfg.id] = UiServerConnStatus.Available
                        snapshot
                    } else {
                        removeCachedCaps(cfg.id)
                        serverStatus[cfg.id] = UiServerConnStatus.Error
                        null
                    }
                }
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
        val uiServers = servers.map { s ->
                val label = when (s.transport) {
                    is UiStdioTransport -> "STDIO"
                    is UiHttpTransport -> "HTTP"
                    is UiStreamableHttpTransport -> "HTTP (Streamable)"
                    is UiWebSocketTransport -> "WebSocket"
                }
            val snap = getCachedCaps(s.id)
            // Ensure newly enabled servers render as Connecting immediately,
            // regardless of any stale Disabled status in serverStatus map.
            val status = when {
                !s.enabled -> UiServerConnStatus.Disabled
                snap != null -> UiServerConnStatus.Available
                serverStatus[s.id] == UiServerConnStatus.Error -> UiServerConnStatus.Error
                else -> UiServerConnStatus.Connecting
            }
            UiServer(
                id = s.id,
                name = s.name,
                transportLabel = label,
                enabled = s.enabled,
                status = status,
                toolsCount = snap?.tools?.size,
                promptsCount = snap?.prompts?.size,
                resourcesCount = snap?.resources?.size
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

    private val serverStatus = mutableMapOf<String, UiServerConnStatus>()

    private fun getCachedCaps(serverId: String): UiServerCapsSnapshot? = synchronized(capsCache) {
        val entry = capsCache[serverId] ?: return@synchronized null
        if (System.currentTimeMillis() - entry.timestampMillis <= capsCacheTtlMillis) {
            entry.snapshot
        } else {
            capsCache.remove(serverId)
            null
        }
    }

    private fun putCachedCaps(serverId: String, snapshot: UiServerCapsSnapshot) {
        val entry = CachedCaps(snapshot = snapshot, timestampMillis = System.currentTimeMillis())
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
        // Fetch per server to determine success/failure explicitly
        targets.filter { it.enabled }.forEach { cfg ->
            scope.launch {
                val r = fetchServerCapabilities(cfg, logger)
                if (r.isSuccess) {
                    val caps = r.getOrNull()!!
                    val snapshot = UiServerCapsSnapshot(
                        serverId = cfg.id,
                        name = cfg.name,
                        tools = caps.tools.map { it.name },
                        prompts = caps.prompts.map { it.name },
                        resources = caps.resources.map { it.uri ?: it.name }
                    )
                    putCachedCaps(cfg.id, snapshot)
                    serverStatus[cfg.id] = UiServerConnStatus.Available
                } else {
                    removeCachedCaps(cfg.id)
                    serverStatus[cfg.id] = UiServerConnStatus.Error
                }
                publishReady()
            }
        }
    }

    private fun resolveInboundTransport(inbound: io.qent.bro.ui.adapter.models.UiTransportDraft): UiTransportConfig =
        when (inbound) {
            is UiStdioDraft -> UiStdioTransport(command = inbound.command, args = inbound.args)
            is UiHttpDraft -> UiHttpTransport(url = inbound.url, headers = inbound.headers)
            is UiStreamableHttpDraft -> UiStreamableHttpTransport(url = inbound.url, headers = inbound.headers)
            is UiWebSocketDraft -> UiWebSocketTransport(url = inbound.url)
        }

    private fun restartProxyWithPreset(presetId: String, presetOverride: UiPresetCore? = null) {
        if (proxyStatus !is UiProxyStatus.Running) return
        val inboundTransport = activeInbound ?: return
        val presetResult = presetOverride?.let { Result.success(it) } ?: runCatching { repo.loadPreset(presetId) }
        if (presetResult.isFailure) {
            val msg = presetResult.exceptionOrNull()?.message ?: "Failed to load preset for restart"
            println("[AppStore] restartProxyWithPreset failed to load preset '$presetId': $msg")
            proxyStatus = UiProxyStatus.Error(msg)
            activeProxyPresetId = null
            activeInbound = null
            publishReady()
            return
        }
        val preset = presetResult.getOrNull()!!
        val result = proxy.start(servers.toList(), preset, inboundTransport, requestTimeoutSeconds)
        if (result.isSuccess) {
            activeProxyPresetId = presetId
            proxyStatus = UiProxyStatus.Running
        } else {
            val msg = result.exceptionOrNull()?.message ?: "Failed to restart proxy"
            println("[AppStore] restartProxyWithPreset failed for '$presetId': $msg")
            proxyStatus = UiProxyStatus.Error(msg)
            activeProxyPresetId = null
            activeInbound = null
        }
        publishReady()
    }

    private val intents = object : Intents {
        override fun refresh() {
            scope.launch {
                val r = runCatching {
                    val cfg = repo.loadMcpConfig()
                    requestTimeoutSeconds = cfg.requestTimeoutSeconds
                    showTrayIcon = cfg.showTrayIcon
                    proxy.updateCallTimeout(requestTimeoutSeconds)
                    val loadedPresets = repo.listPresets()
                    servers.clear(); servers.addAll(cfg.servers)
                    presets.clear(); presets.addAll(loadedPresets.map { p ->
                        UiPreset(
                            id = p.id,
                            name = p.name,
                            description = p.description.ifBlank { null },
                            toolsCount = p.tools.count { it.enabled }
                        )
                    })
                }
                if (r.isFailure) {
                    val msg = r.exceptionOrNull()?.message ?: "Failed to refresh"
                    println("[AppStore] refresh failed: ${'$'}msg")
                    _state.value = UIState.Error(msg)
                }
                publishReady()
                // Update capability counts and statuses after refresh
                refreshServerCapsAndPublish()
            }
        }

        override fun addOrUpdateServerUi(ui: UiServer) {
            scope.launch {
                val idx = servers.indexOfFirst { it.id == ui.id }
                val current = servers.getOrNull(idx)
                val updated = (current ?: UiMcpServerConfig(
                    id = ui.id,
                    name = ui.name,
                    transport = UiStdioTransport(command = ""),
                    enabled = ui.enabled
                )).copy(name = ui.name, enabled = ui.enabled)
                val snapshot = servers.toList()
                if (idx >= 0) servers[idx] = updated else servers += updated
                updateCachedCapsName(ui.id, ui.name)
                val r = runCatching { repo.saveMcpConfig(snapshotConfig()) }
                if (r.isFailure) {
                    val msg = r.exceptionOrNull()?.message ?: "Failed to save servers"
                    println("[AppStore] addOrUpdateServerUi failed: ${'$'}msg")
                    servers.clear(); servers.addAll(snapshot)
                    _state.value = UIState.Error(msg)
                }
                publishReady()
                refreshServerCapsAndPublish(setOf(ui.id))
            }
        }

        override fun upsertServer(draft: UiServerDraft) {
            scope.launch {
                val transport = when (val t = draft.transport) {
                    is UiStdioDraft -> UiStdioTransport(command = t.command, args = t.args)
                    is UiHttpDraft -> UiHttpTransport(url = t.url, headers = t.headers)
                    is UiStreamableHttpDraft -> UiStreamableHttpTransport(url = t.url, headers = t.headers)
                    is UiWebSocketDraft -> UiWebSocketTransport(url = t.url)
                }
                val cfg = UiMcpServerConfig(
                    id = draft.id,
                    name = draft.name,
                    enabled = draft.enabled,
                    transport = transport,
                    env = draft.env
                )
                val idx = servers.indexOfFirst { it.id == cfg.id }
                val snapshot = servers.toList()
                if (idx >= 0) servers[idx] = cfg else servers += cfg
                removeCachedCaps(cfg.id)
                val r = runCatching { repo.saveMcpConfig(snapshotConfig()) }
                if (r.isFailure) {
                    val msg = r.exceptionOrNull()?.message ?: "Failed to save servers"
                    println("[AppStore] upsertServer failed: ${'$'}msg")
                    servers.clear(); servers.addAll(snapshot)
                    _state.value = UIState.Error(msg)
                }
                publishReady()
                refreshServerCapsAndPublish(setOf(cfg.id))
            }
        }

        override fun addServerBasic(id: String, name: String) {
            scope.launch {
                val cfg = UiMcpServerConfig(id = id, name = name, transport = UiStdioTransport(command = ""), enabled = true)
                val idx = servers.indexOfFirst { it.id == cfg.id }
                val snapshot = servers.toList()
                if (idx >= 0) servers[idx] = cfg else servers += cfg
                val r = runCatching { repo.saveMcpConfig(snapshotConfig()) }
                if (r.isFailure) {
                    val msg = r.exceptionOrNull()?.message ?: "Failed to save servers"
                    println("[AppStore] addServerBasic failed: ${'$'}msg")
                    servers.clear(); servers.addAll(snapshot)
                    _state.value = UIState.Error(msg)
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
                val r = runCatching { repo.saveMcpConfig(snapshotConfig()) }
                if (r.isFailure) {
                    val msg = r.exceptionOrNull()?.message ?: "Failed to save servers"
                    println("[AppStore] removeServer failed: ${'$'}msg")
                    servers.clear(); servers.addAll(snapshot)
                    _state.value = UIState.Error(msg)
                }
                publishReady()
                refreshServerCapsAndPublish(setOf(id))
            }
        }

        override fun toggleServer(id: String, enabled: Boolean) {
            scope.launch {
                val idx = servers.indexOfFirst { it.id == id }
                if (idx >= 0) {
                    val snapshot = servers[idx]
                    servers[idx] = servers[idx].copy(enabled = enabled)
                    val r = runCatching { repo.saveMcpConfig(snapshotConfig()) }
                    if (r.isFailure) {
                        val msg = r.exceptionOrNull()?.message ?: "Failed to save server state"
                        println("[AppStore] toggleServer failed: ${'$'}msg")
                        servers[idx] = snapshot
                        _state.value = UIState.Error(msg)
                    }
                }
                publishReady()
                refreshServerCapsAndPublish(setOf(id))
            }
        }

        override fun addOrUpdatePreset(preset: UiPreset) {
            scope.launch {
                val idx = presets.indexOfFirst { it.id == preset.id }
                if (idx >= 0) presets[idx] = preset else presets += preset
                val r = runCatching {
                    val p = io.qent.bro.core.models.Preset(
                        id = preset.id,
                        name = preset.name,
                        description = preset.description ?: "",
                        tools = emptyList()
                    )
                    repo.savePreset(p)
                }
                if (r.isFailure) {
                    val msg = r.exceptionOrNull()?.message ?: "Failed to save preset"
                    println("[AppStore] savePreset failed: ${'$'}msg")
                    _state.value = UIState.Error(msg)
                }
                publishReady()
            }
        }

        override fun upsertPreset(draft: UiPresetDraft) {
            scope.launch {
                val coreTools = draft.tools.map { t ->
                    io.qent.bro.core.models.ToolReference(serverId = t.serverId, toolName = t.toolName, enabled = t.enabled)
                }
                val preset = io.qent.bro.core.models.Preset(
                    id = draft.id,
                    name = draft.name,
                    description = draft.description ?: "",
                    tools = coreTools
                )
                val saveResult = runCatching { repo.savePreset(preset) }
                if (saveResult.isFailure) {
                    val msg = saveResult.exceptionOrNull()?.message ?: "Failed to save preset"
                    println("[AppStore] upsertPreset failed: ${'$'}msg")
                    _state.value = UIState.Error(msg)
                }
                val summary = UiPreset(
                    id = draft.id,
                    name = draft.name,
                    description = draft.description,
                    toolsCount = draft.tools.count { it.enabled }
                )
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
                val r = runCatching { repo.deletePreset(id) }
                if (r.isFailure) {
                    val msg = r.exceptionOrNull()?.message ?: "Failed to delete preset"
                    println("[AppStore] deletePreset failed: ${'$'}msg")
                    presets.clear(); presets.addAll(snapshot)
                    _state.value = UIState.Error(msg)
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
                val presetResult = runCatching { repo.loadPreset(presetId) }
                if (presetResult.isFailure) {
                    val msg = presetResult.exceptionOrNull()?.message ?: "Failed to load preset"
                    println("[AppStore] startProxySimple failed: ${'$'}msg")
                    proxyStatus = UiProxyStatus.Error(msg)
                    selectedProxyPresetId = presetId
                    activeProxyPresetId = null
                    activeInbound = null
                    publishReady()
                    return@launch
                }
                val preset = presetResult.getOrNull()!!
                // Default inbound to HTTP facade matching UI default
                val inbound = UiHttpTransport("http://0.0.0.0:3335/mcp")
                val result = proxy.start(servers.toList(), preset, inbound, requestTimeoutSeconds)
                selectedProxyPresetId = presetId
                if (result.isSuccess) {
                    proxyStatus = UiProxyStatus.Running
                    activeProxyPresetId = presetId
                    activeInbound = inbound
                } else {
                    val msg = result.exceptionOrNull()?.message ?: "Failed to start proxy"
                    println("[AppStore] startProxySimple failed to start proxy: $msg")
                    proxyStatus = UiProxyStatus.Error(msg)
                    activeProxyPresetId = null
                    activeInbound = null
                }
                publishReady()
            }
        }

        override fun startProxy(presetId: String, inbound: io.qent.bro.ui.adapter.models.UiTransportDraft) {
            scope.launch {
                val presetResult = runCatching { repo.loadPreset(presetId) }
                if (presetResult.isFailure) {
                    val msg = presetResult.exceptionOrNull()?.message ?: "Failed to load preset"
                    println("[AppStore] startProxy failed: ${'$'}msg")
                    proxyStatus = UiProxyStatus.Error(msg)
                    selectedProxyPresetId = presetId
                    activeProxyPresetId = null
                    activeInbound = null
                    publishReady()
                    return@launch
                }
                val preset = presetResult.getOrNull()!!
                val inboundTransport = resolveInboundTransport(inbound)
                val result = proxy.start(servers.toList(), preset, inboundTransport, requestTimeoutSeconds)
                selectedProxyPresetId = presetId
                if (result.isSuccess) {
                    proxyStatus = UiProxyStatus.Running
                    activeProxyPresetId = presetId
                    activeInbound = inboundTransport
                } else {
                    val msg = result.exceptionOrNull()?.message ?: "Failed to start proxy"
                    println("[AppStore] startProxy failed to start proxy: $msg")
                    proxyStatus = UiProxyStatus.Error(msg)
                    activeProxyPresetId = null
                    activeInbound = null
                }
                publishReady()
            }
        }

        override fun stopProxy() {
            scope.launch {
                val result = proxy.stop()
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
                proxy.updateCallTimeout(requestTimeoutSeconds)
                val r = runCatching { repo.saveMcpConfig(snapshotConfig()) }
                if (r.isFailure) {
                    val msg = r.exceptionOrNull()?.message ?: "Failed to update timeout"
                    println("[AppStore] updateRequestTimeout failed: ${'$'}msg")
                    requestTimeoutSeconds = previous
                    proxy.updateCallTimeout(requestTimeoutSeconds)
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
                val r = runCatching { repo.saveMcpConfig(snapshotConfig()) }
                if (r.isFailure) {
                    val msg = r.exceptionOrNull()?.message ?: "Failed to update tray preference"
                    println("[AppStore] updateTrayIconVisibility failed: ${'$'}msg")
                    showTrayIcon = previous
                    _state.value = UIState.Error(msg)
                }
                publishReady()
            }
        }
    }
}

fun createAppStore(): AppStore = AppStore()
