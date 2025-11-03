package io.qent.broxy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.UiServer
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.viewmodels.AppState
import io.qent.broxy.ui.adapter.store.AppStore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import kotlinx.coroutines.launch

@Composable
fun ServersScreen(ui: UIState, state: AppState, store: AppStore, notify: (String) -> Unit = {}) {
    var query by rememberSaveable { mutableStateOf("") }
    var editing: UiServer? by remember { mutableStateOf<UiServer?>(null) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search servers") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        when (ui) {
            is UIState.Loading -> Text("Loading...", style = MaterialTheme.typography.bodyMedium)
            is UIState.Error -> Text("Error: ${ui.message}")
            is UIState.Ready -> {
                val servers = ui.servers
                if (servers.isEmpty()) {
                    EmptyState(
                        title = "No servers yet",
                        subtitle = "Use the + button to add your first MCP server"
                    )
                } else {
                    val filtered = servers.filter { cfg ->
                        cfg.name.contains(query, ignoreCase = true) ||
                                cfg.id.contains(query, ignoreCase = true) ||
                                cfg.transportLabel.contains(query, ignoreCase = true)
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(filtered, key = { it.id }) { cfg ->
                            ServerCard(
                                cfg = cfg,
                                onToggle = { id, enabled ->
                                    if (enabled) {
                                        // Optimistically enable to reflect "connecting" status immediately
                                        ui.intents.toggleServer(id, true)
                                        scope.launch {
                                            val draft = store.getServerDraft(id)
                                            if (draft == null) {
                                                notify("Failed to load server config for '${cfg.name}'")
                                                // Revert enable if draft missing
                                                ui.intents.toggleServer(id, false)
                                                return@launch
                                            }
                                            val result = io.qent.broxy.ui.adapter.services.validateServerConnection(draft)
                                            if (result.isFailure) {
                                                val e = result.exceptionOrNull()
                                                val isTimeout = e?.message?.contains("timed out", ignoreCase = true) == true
                                                if (isTimeout) {
                                                    notify("Connection timed out for '${cfg.name}'")
                                                } else {
                                                    val errMsg = e?.message?.takeIf { it.isNotBlank() }
                                                    val details = errMsg?.let { ": $it" } ?: ""
                                                    notify("Connection failed for '${cfg.name}'$details")
                                                }
                                                // Revert if validation fails
                                                ui.intents.toggleServer(id, false)
                                            }
                                        }
                                    } else {
                                        ui.intents.toggleServer(id, false)
                                    }
                                },
                                onEdit = { editing = cfg },
                                onDelete = { id -> ui.intents.removeServer(id) }
                            )
                        }
                    }
                }
            }
        }

        if (editing != null) {
            val draft = store.getServerDraft(editing!!.id)
            if (draft != null) {
                EditServerDialog(initial = draft, ui = ui, onClose = { editing = null }, notify = notify)
            } else {
                editing = null
            }
        }
    }
}

@Composable
private fun ServerCard(
    cfg: UiServer,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onEdit: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                Column(Modifier.weight(1f)) {
                    Text(cfg.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text("${cfg.id} • ${cfg.transportLabel}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val status = when (cfg.status.name) {
                        "Disabled" -> "disabled"
                        "Available" -> "available"
                        "Error" -> "error"
                        else -> "connecting"
                    }
                    val counts = if (cfg.enabled && cfg.toolsCount != null) {
                        val tc = cfg.toolsCount ?: 0
                        val pc = cfg.promptsCount ?: 0
                        val rc = cfg.resourcesCount ?: 0
                        " • tools $tc • prompts $pc • resources $rc"
                    } else ""
                    Text("status: $status$counts", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = cfg.enabled, onCheckedChange = { enabled -> onToggle(cfg.id, enabled) })
                IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "Edit") }
                IconButton(onClick = { onDelete(cfg.id) }) { Icon(Icons.Outlined.Delete, contentDescription = "Delete") }
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.padding(6.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
