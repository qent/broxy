package io.qent.broxy.ui.screens

import AppPrimaryButton
import AppSecondaryButton
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.UiServer
import io.qent.broxy.ui.adapter.store.AppStore
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.components.AppDialog
import io.qent.broxy.ui.theme.AppTheme
import io.qent.broxy.ui.viewmodels.AppState

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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = AppTheme.shapes.item
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(AppTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
                modifier = Modifier.weight(1f)
            ) {
                // Logo Placeholder
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                        )
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    // If we had a logo URL, we'd load it here. For now, maybe an icon?
                    Icon(
                        imageVector = Icons.Outlined.Storage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        cfg.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    // Status and Transport
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                         Text(
                            cfg.transportLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        
                        val statusColor = when (cfg.status.name) {
                            "Available" -> AppTheme.extendedColors.success
                            "Error" -> MaterialTheme.colorScheme.error
                            "Disabled" -> MaterialTheme.colorScheme.outline
                            else -> MaterialTheme.colorScheme.secondary
                        }
                        
                        Text(
                            cfg.status.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = statusColor
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = cfg.enabled, 
                    onCheckedChange = { enabled -> onToggle(cfg.id, enabled) },
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
                Spacer(Modifier.width(AppTheme.spacing.sm))
                IconButton(onClick = onEdit) { 
                    Icon(Icons.Outlined.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.secondary) 
                }
                IconButton(onClick = onDelete) { 
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.secondary) 
                }
            }
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
