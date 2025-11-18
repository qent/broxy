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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import io.qent.broxy.ui.adapter.models.UiServer
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.viewmodels.AppState
import io.qent.broxy.ui.adapter.store.AppStore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import io.qent.broxy.ui.components.AppDialog
import io.qent.broxy.ui.components.AppPrimaryButton
import io.qent.broxy.ui.components.AppSecondaryButton
import io.qent.broxy.ui.theme.AppTheme

@Composable
fun ServersScreen(ui: UIState, state: AppState, store: AppStore, notify: (String) -> Unit = {}) {
    var query by rememberSaveable { mutableStateOf("") }
    var editing: UiServer? by remember { mutableStateOf<UiServer?>(null) }
    var viewing: UiServer? by remember { mutableStateOf<UiServer?>(null) }
    var pendingDeletion: UiServer? by remember { mutableStateOf<UiServer?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppTheme.spacing.md)
    ) {

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search servers") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(AppTheme.spacing.md))

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
                        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md)
                    ) {
                        items(filtered, key = { it.id }) { cfg ->
                            ServerCard(
                                cfg = cfg,
                                onViewDetails = { viewing = cfg },
                                onToggle = { id, enabled ->
                                    ui.intents.toggleServer(id, enabled)
                                },
                                onEdit = { editing = cfg },
                                onDelete = { pendingDeletion = cfg }
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

        viewing?.let { server ->
            ServerDetailsDialog(server = server, store = store, onClose = { viewing = null })
        }

        val readyUi = ui as? UIState.Ready
        val toDelete = pendingDeletion
        if (readyUi != null && toDelete != null) {
            DeleteServerDialog(
                server = toDelete,
                onConfirm = {
                    readyUi.intents.removeServer(toDelete.id)
                    pendingDeletion = null
                },
                onDismiss = { pendingDeletion = null }
            )
        }
    }
}

@Composable
private fun ServerCard(
    cfg: UiServer,
    onViewDetails: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit
) {
    Card(
        onClick = onViewDetails,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(AppTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xxs)
            ) {
                Text(
                    cfg.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${cfg.id} • ${cfg.transportLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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
                Text(
                    "status: $status$counts",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(AppTheme.spacing.sm))
            Switch(checked = cfg.enabled, onCheckedChange = { enabled -> onToggle(cfg.id, enabled) })
            Spacer(Modifier.width(AppTheme.spacing.xs))
            IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "Edit") }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Delete") }
        }
    }
}

@Composable
private fun DeleteServerDialog(
    server: UiServer,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AppDialog(
        title = "Delete server",
        onDismissRequest = onDismiss,
        dismissButton = { AppSecondaryButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = { AppPrimaryButton(onClick = onConfirm) { Text("Delete") } }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
            Text(
                text = "Remove \"${server.name}\" (${server.id})?",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "This removes the server configuration and presets that referenced it will lose access to its capabilities. This action cannot be undone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
        Spacer(Modifier.height(AppTheme.spacing.sm))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
