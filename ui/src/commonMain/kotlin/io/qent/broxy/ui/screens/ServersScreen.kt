package io.qent.broxy.ui.screens

import AppPrimaryButton
import AppSecondaryButton
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.UiServer
import io.qent.broxy.ui.adapter.store.AppStore
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.components.AppDialog
import io.qent.broxy.ui.components.SettingsLikeItem
import io.qent.broxy.ui.theme.AppTheme
import io.qent.broxy.ui.viewmodels.AppState

@Composable
fun ServersScreen(ui: UIState, state: AppState, store: AppStore, notify: (String) -> Unit = {}) {
    var query by rememberSaveable { mutableStateOf("") }
    var editing: UiServer? by remember { mutableStateOf<UiServer?>(null) }
    var viewing: UiServer? by remember { mutableStateOf<UiServer?>(null) }
    var pendingDeletion: UiServer? by remember { mutableStateOf<UiServer?>(null) }

    Box(modifier = Modifier.fillMaxSize().padding(AppTheme.spacing.md)) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search servers", style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall
            )

            when (ui) {
                is UIState.Loading -> Text("Loading...", style = MaterialTheme.typography.bodyMedium)
                is UIState.Error -> Text("Error: ${ui.message}", style = MaterialTheme.typography.bodyMedium)
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
                            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
                            contentPadding = PaddingValues(bottom = ServerListBottomPadding)
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
    val statusColor = when (cfg.status.name) {
        "Available" -> AppTheme.extendedColors.success
        "Error" -> MaterialTheme.colorScheme.error
        "Disabled" -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.secondary
    }

    val isDisabled = !cfg.enabled
    val disabledAlpha = 0.55f
    val titleColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDisabled) disabledAlpha else 1f)
    val transportColor =
        MaterialTheme.colorScheme.primary.copy(alpha = if (isDisabled) disabledAlpha else 1f)
    val separatorColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isDisabled) disabledAlpha else 1f)
    val statusTextColor = if (isDisabled) MaterialTheme.colorScheme.onSurfaceVariant else statusColor
    val showErrorStatus = cfg.enabled && cfg.status.name == "Error"

    SettingsLikeItem(
        title = cfg.name,
        titleColor = titleColor,
        descriptionContent = {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = transportColor)) {
                        append(cfg.transportLabel)
                    }
                    if (showErrorStatus) {
                        withStyle(SpanStyle(color = separatorColor)) {
                            append(" • ")
                        }
                        withStyle(SpanStyle(color = statusTextColor)) {
                            append(cfg.status.name)
                        }
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        onClick = onViewDetails
    ) {
        Switch(
            checked = cfg.enabled,
            onCheckedChange = { enabled -> onToggle(cfg.id, enabled) },
            modifier = Modifier.scale(0.7f),
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline
            )
        )
        IconButton(onClick = onEdit) {
            Icon(Icons.Outlined.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.secondary)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.secondary)
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
                text = "Remove \"${server.name}\"?",
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

private val ServerListBottomPadding = 88.dp
