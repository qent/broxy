package io.qent.bro.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qent.bro.ui.adapter.models.UiServerStatus as ServerStatus
import io.qent.bro.ui.adapter.models.UiRunning as Running
import io.qent.bro.ui.adapter.models.UiError as Error
import io.qent.bro.ui.adapter.models.UiStarting as Starting
import io.qent.bro.ui.adapter.models.UiStopping as Stopping
import io.qent.bro.ui.adapter.models.UiStopped as Stopped
import io.qent.bro.ui.adapter.models.UiMcpServerConfig as McpServerConfig
import io.qent.bro.ui.adapter.models.UiTransportConfig as TransportConfig
import io.qent.bro.ui.viewmodels.AppState
import io.qent.bro.ui.adapter.viewmodels.ServersViewModel
import io.qent.bro.ui.adapter.data.provideConfigurationRepository
import io.qent.bro.ui.adapter.models.UiMcpServersConfig as McpServersConfig
import io.qent.bro.ui.adapter.models.UiHttpTransport as HttpTransport
import io.qent.bro.ui.adapter.models.UiStdioTransport as StdioTransport
import io.qent.bro.ui.adapter.models.UiWebSocketTransport as WebSocketTransport
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ServersScreen(state: AppState, notify: (String) -> Unit = {}) {
    val viewModel = remember { ServersViewModel() }
    val repo = remember { provideConfigurationRepository() }
    var query by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        runCatching {
            withContext(Dispatchers.IO) { repo.loadMcpConfig() }
        }
            .onSuccess { cfg ->
                state.servers.clear()
                state.servers.addAll(cfg.servers)
            }
            .onFailure { ex ->
                notify("Failed to load servers: ${ex.message ?: "unknown error"}")
            }
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search servers") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        if (state.servers.isEmpty()) {
            EmptyState(
                title = "No servers yet",
                subtitle = "Use the + button to add your first MCP server"
            )
        } else {
            val filtered = state.servers.filter { cfg ->
                val t = transportLabel(cfg.transport)
                cfg.name.contains(query, ignoreCase = true) ||
                        cfg.id.contains(query, ignoreCase = true) ||
                        t.contains(query, ignoreCase = true)
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(filtered, key = { it.id }) { cfg ->
                    ServerCard(
                        cfg = cfg,
                        state = state,
                        vm = viewModel,
                        notify = notify,
                        onPersist = {
                            runCatching {
                                repo.saveMcpConfig(McpServersConfig(state.servers.toList()))
                            }.onFailure { notify("Failed to save servers: ${it.message}") }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerCard(
    cfg: McpServerConfig,
    state: AppState,
    vm: ServersViewModel,
    notify: (String) -> Unit,
    onPersist: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val ui = (vm.uiStates[cfg.id] ?: MutableStateFlow(io.qent.bro.ui.adapter.viewmodels.ServerUiState())).collectAsState().value
    val statusColor = when (ui.status) {
        Running -> Color(0xFF1DB954)
        is Error -> Color(0xFFD64545)
        Starting -> Color(0xFFFFB02E)
        Stopping -> Color(0xFF6C6F7D)
        Stopped -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.outline
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(cfg.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text("${cfg.id} • ${transportLabel(cfg.transport)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(Modifier.size(10.dp).clip(CircleShape).background(statusColor))
                Spacer(Modifier.width(8.dp))
                Switch(checked = cfg.enabled, onCheckedChange = { enabled ->
                    vm.applyEnabledChange(state.servers, cfg.id, enabled)
                    onPersist()
                    if (enabled) {
                        scope.launch {
                            vm.connect(cfg)
                                .onSuccess { notify("Connected to ${cfg.name}") }
                                .onFailure { ex ->
                                    notify("Failed to connect to ${cfg.name}: ${ex.message ?: "unknown error"}")
                                }
                        }
                    } else {
                        scope.launch {
                            vm.disconnect(cfg.id)
                            notify("Disconnected from ${cfg.name}")
                        }
                    }
                })
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Tools: ${ui.toolsCount?.toString() ?: "—"}")
                Spacer(Modifier.weight(1f))
                if (ui.testing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                ElevatedButton(
                    onClick = {
                        scope.launch {
                            vm.testConnection(cfg)
                                .onSuccess { caps ->
                                    notify("${cfg.name}: ${caps.tools.size} tools available")
                                }
                                .onFailure { ex ->
                                    notify("Failed to test ${cfg.name}: ${ex.message ?: "unknown error"}")
                                }
                        }
                    },
                    enabled = !ui.testing
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Test Connection")
                }
                TextButton(onClick = { vm.openDetails(cfg.id) }) {
                    Icon(Icons.Outlined.Info, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Details")
                }
                IconButton(onClick = { vm.openEdit(cfg.id) }) { Icon(Icons.Outlined.Edit, contentDescription = "Edit") }
                IconButton(onClick = {
                    scope.launch {
                        vm.removeServer(state.servers, cfg.id)
                        onPersist()
                        notify("Removed ${cfg.name}")
                    }
                }) { Icon(Icons.Outlined.Delete, contentDescription = "Delete") }
            }
        }
    }

    if (ui.showDetails) {
        ServerDetailsDialog(cfg = cfg, vm = vm, onClose = { vm.closeDetails(cfg.id) })
    }
    if (ui.showEdit) {
        EditServerDialog(
            initial = cfg,
            onSave = { newCfg ->
                vm.replaceConfig(state.servers, newCfg)
                vm.updateConnectionConfig(newCfg)
                onPersist()
                vm.closeEdit(cfg.id)
            },
            onClose = { vm.closeEdit(cfg.id) }
        )
    }
}

private fun transportLabel(t: TransportConfig): String = when (t) {
    is StdioTransport -> "STDIO"
    is HttpTransport -> "HTTP"
    is WebSocketTransport -> "WebSocket"
    else -> ""
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.padding(6.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
