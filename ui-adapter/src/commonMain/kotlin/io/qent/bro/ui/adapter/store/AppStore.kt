package io.qent.bro.ui.adapter.store

import io.qent.bro.ui.adapter.models.*
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

    // In-memory backing store for demo purposes. Replace with repositories later.
    private val servers = mutableListOf<UiMcpServerConfig>()
    private val presets = mutableListOf<UiPreset>()
    private var proxyStatus: UiProxyStatus = UiProxyStatus.Stopped

    fun start() {
        scope.launch {
            // Initial demo data; real impl should load from ConfigurationRepository
            if (servers.isEmpty()) {
                servers += UiMcpServerConfig(
                    id = "example-stdio",
                    name = "Example STDIO",
                    transport = UiStdioTransport(command = "my-mcp"),
                    enabled = false
                )
            }
            if (presets.isEmpty()) {
                presets += UiPreset(id = "default", name = "Default", description = null, toolsCount = 0)
            }
            publishReady()
        }
    }

    private fun publishReady() {
        val uiServers = servers.map { s ->
            val label = when (s.transport) {
                is UiStdioTransport -> "STDIO"
                is UiHttpTransport -> "HTTP"
                is UiWebSocketTransport -> "WebSocket"
            }
            UiServer(id = s.id, name = s.name, transportLabel = label, enabled = s.enabled)
        }
        _state.value = UIState.Ready(
            servers = uiServers,
            presets = presets.toList(),
            proxyStatus = proxyStatus,
            intents = intents
        )
    }

    private val intents = object : Intents {
        override fun refresh() {
            // In-memory impl: nothing to fetch; just re-emit state
            publishReady()
        }

        override fun addOrUpdateServerUi(ui: UiServer) {
            val idx = servers.indexOfFirst { it.id == ui.id }
            val current = servers.getOrNull(idx)
            val updated = (current ?: UiMcpServerConfig(
                id = ui.id,
                name = ui.name,
                transport = UiStdioTransport(command = ""),
                enabled = ui.enabled
            )).copy(name = ui.name, enabled = ui.enabled)
            if (idx >= 0) servers[idx] = updated else servers += updated
            publishReady()
        }

        override fun addServerBasic(id: String, name: String) {
            val cfg = UiMcpServerConfig(id = id, name = name, transport = UiStdioTransport(command = ""), enabled = true)
            val idx = servers.indexOfFirst { it.id == cfg.id }
            if (idx >= 0) servers[idx] = cfg else servers += cfg
            publishReady()
        }

        override fun removeServer(id: String) {
            servers.removeAll { it.id == id }
            publishReady()
        }

        override fun toggleServer(id: String, enabled: Boolean) {
            val idx = servers.indexOfFirst { it.id == id }
            if (idx >= 0) servers[idx] = servers[idx].copy(enabled = enabled)
            publishReady()
        }

        override fun addOrUpdatePreset(preset: UiPreset) {
            val idx = presets.indexOfFirst { it.id == preset.id }
            if (idx >= 0) presets[idx] = preset else presets += preset
            publishReady()
        }

        override fun removePreset(id: String) {
            presets.removeAll { it.id == id }
            publishReady()
        }

        override fun startProxySimple(presetId: String) {
            proxyStatus = UiProxyStatus.Running
            publishReady()
        }

        override fun stopProxy() {
            proxyStatus = UiProxyStatus.Stopped
            publishReady()
        }
    }
}

fun createAppStore(): AppStore = AppStore()
