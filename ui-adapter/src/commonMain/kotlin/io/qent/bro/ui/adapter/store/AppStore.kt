package io.qent.bro.ui.adapter.store

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
import io.qent.bro.ui.adapter.services.fetchServerCapabilities
import io.qent.bro.ui.adapter.proxy.createProxyController
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
    private val repo = provideConfigurationRepository()
    private val capsCache = mutableMapOf<String, UiServerCapsSnapshot>()
    private val proxy = createProxyController()

    fun start() {
        scope.launch {
            val load = runCatching {
                val cfg = repo.loadMcpConfig()
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
            transport = draftTransport
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
        enabled.map { cfg ->
            async {
                val r = fetchServerCapabilities(cfg)
                if (r.isSuccess) {
                    val caps = r.getOrNull()!!
                    UiServerCapsSnapshot(
                        serverId = cfg.id,
                        name = cfg.name,
                        tools = caps.tools.map { it.name },
                        prompts = caps.prompts.map { it.name },
                        resources = caps.resources.map { it.uri ?: it.name }
                    )
                } else null
            }
        }.awaitAll().filterNotNull()
    }

    private fun publishReady() {
        val uiServers = servers.map { s ->
                val label = when (s.transport) {
                    is UiStdioTransport -> "STDIO"
                    is UiHttpTransport -> "HTTP"
                    is UiStreamableHttpTransport -> "HTTP (Streamable)"
                    is UiWebSocketTransport -> "WebSocket"
                }
            val snap = capsCache[s.id]
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
            proxyStatus = proxyStatus,
            intents = intents
        )
    }

    private val serverStatus = mutableMapOf<String, UiServerConnStatus>()

    private suspend fun refreshServerCapsAndPublish() {
        // Set connecting for enabled servers without a known status
        servers.forEach { s ->
            serverStatus[s.id] = if (!s.enabled) UiServerConnStatus.Disabled else UiServerConnStatus.Connecting
        }
        publishReady()
        // Fetch per server to determine success/failure explicitly
        servers.filter { it.enabled }.map { cfg ->
            scope.launch {
                val r = fetchServerCapabilities(cfg)
                if (r.isSuccess) {
                    val caps = r.getOrNull()!!
                    capsCache[cfg.id] = UiServerCapsSnapshot(
                        serverId = cfg.id,
                        name = cfg.name,
                        tools = caps.tools.map { it.name },
                        prompts = caps.prompts.map { it.name },
                        resources = caps.resources.map { it.uri ?: it.name }
                    )
                    serverStatus[cfg.id] = UiServerConnStatus.Available
                } else {
                    capsCache.remove(cfg.id)
                    serverStatus[cfg.id] = UiServerConnStatus.Error
                }
                publishReady()
            }
        }
    }

    private val intents = object : Intents {
        override fun refresh() {
            scope.launch {
                val r = runCatching {
                    val cfg = repo.loadMcpConfig()
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
                val r = runCatching { repo.saveMcpConfig(UiMcpServersConfig(servers.toList())) }
                if (r.isFailure) {
                    val msg = r.exceptionOrNull()?.message ?: "Failed to save servers"
                    println("[AppStore] addOrUpdateServerUi failed: ${'$'}msg")
                    servers.clear(); servers.addAll(snapshot)
                    _state.value = UIState.Error(msg)
                }
                publishReady()
                refreshServerCapsAndPublish()
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
                    transport = transport
                )
                val idx = servers.indexOfFirst { it.id == cfg.id }
                val snapshot = servers.toList()
                if (idx >= 0) servers[idx] = cfg else servers += cfg
                val r = runCatching { repo.saveMcpConfig(UiMcpServersConfig(servers.toList())) }
                if (r.isFailure) {
                    val msg = r.exceptionOrNull()?.message ?: "Failed to save servers"
                    println("[AppStore] upsertServer failed: ${'$'}msg")
                    servers.clear(); servers.addAll(snapshot)
                    _state.value = UIState.Error(msg)
                }
                publishReady()
                refreshServerCapsAndPublish()
            }
        }

        override fun addServerBasic(id: String, name: String) {
            scope.launch {
                val cfg = UiMcpServerConfig(id = id, name = name, transport = UiStdioTransport(command = ""), enabled = true)
                val idx = servers.indexOfFirst { it.id == cfg.id }
                val snapshot = servers.toList()
                if (idx >= 0) servers[idx] = cfg else servers += cfg
                val r = runCatching { repo.saveMcpConfig(UiMcpServersConfig(servers.toList())) }
                if (r.isFailure) {
                    val msg = r.exceptionOrNull()?.message ?: "Failed to save servers"
                    println("[AppStore] addServerBasic failed: ${'$'}msg")
                    servers.clear(); servers.addAll(snapshot)
                    _state.value = UIState.Error(msg)
                }
                publishReady()
                refreshServerCapsAndPublish()
            }
        }

        override fun removeServer(id: String) {
            scope.launch {
                val snapshot = servers.toList()
                servers.removeAll { it.id == id }
                val r = runCatching { repo.saveMcpConfig(UiMcpServersConfig(servers.toList())) }
                if (r.isFailure) {
                    val msg = r.exceptionOrNull()?.message ?: "Failed to save servers"
                    println("[AppStore] removeServer failed: ${'$'}msg")
                    servers.clear(); servers.addAll(snapshot)
                    _state.value = UIState.Error(msg)
                }
                publishReady()
                refreshServerCapsAndPublish()
            }
        }

        override fun toggleServer(id: String, enabled: Boolean) {
            scope.launch {
                val idx = servers.indexOfFirst { it.id == id }
                if (idx >= 0) {
                    val snapshot = servers[idx]
                    servers[idx] = servers[idx].copy(enabled = enabled)
                    val r = runCatching { repo.saveMcpConfig(UiMcpServersConfig(servers.toList())) }
                    if (r.isFailure) {
                        val msg = r.exceptionOrNull()?.message ?: "Failed to save server state"
                        println("[AppStore] toggleServer failed: ${'$'}msg")
                        servers[idx] = snapshot
                        _state.value = UIState.Error(msg)
                    }
                }
                publishReady()
                refreshServerCapsAndPublish()
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
                val r = runCatching {
                    val p = io.qent.bro.core.models.Preset(
                        id = draft.id,
                        name = draft.name,
                        description = draft.description ?: "",
                        tools = coreTools
                    )
                    repo.savePreset(p)
                }
                if (r.isFailure) {
                    val msg = r.exceptionOrNull()?.message ?: "Failed to save preset"
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
                publishReady()
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

        override fun startProxySimple(presetId: String) {
            scope.launch {
                val presetResult = runCatching { repo.loadPreset(presetId) }
                if (presetResult.isFailure) {
                    val msg = presetResult.exceptionOrNull()?.message ?: "Failed to load preset"
                    println("[AppStore] startProxySimple failed: ${'$'}msg")
                    proxyStatus = UiProxyStatus.Error(msg)
                    publishReady()
                    return@launch
                }
                val preset = presetResult.getOrNull()!!
                // Default inbound to HTTP facade matching UI default
                val inbound = UiHttpTransport("http://0.0.0.0:3335/mcp")
                val result = proxy.start(servers.toList(), preset, inbound)
                proxyStatus = if (result.isSuccess) UiProxyStatus.Running
                else UiProxyStatus.Error(result.exceptionOrNull()?.message ?: "Failed to start proxy")
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
                    publishReady()
                    return@launch
                }
                val preset = presetResult.getOrNull()!!
                val inboundTransport = when (inbound) {
                    is UiStdioDraft -> UiStdioTransport(command = inbound.command, args = inbound.args)
                    is UiHttpDraft -> UiHttpTransport(url = inbound.url, headers = inbound.headers)
                    is UiStreamableHttpDraft -> UiStreamableHttpTransport(url = inbound.url, headers = inbound.headers)
                    is UiWebSocketDraft -> UiWebSocketTransport(url = inbound.url)
                    else -> UiHttpTransport(url = "http://0.0.0.0:3335/mcp")
                }
                val result = proxy.start(servers.toList(), preset, inboundTransport)
                proxyStatus = if (result.isSuccess) UiProxyStatus.Running
                else UiProxyStatus.Error(result.exceptionOrNull()?.message ?: "Failed to start proxy")
                publishReady()
            }
        }

        override fun stopProxy() {
            scope.launch {
                val result = proxy.stop()
                proxyStatus = if (result.isSuccess) UiProxyStatus.Stopped
                else UiProxyStatus.Error(result.exceptionOrNull()?.message ?: "Failed to stop proxy")
                publishReady()
            }
        }
    }
}

fun createAppStore(): AppStore = AppStore()
