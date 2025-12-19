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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.UiServer
import io.qent.broxy.ui.adapter.models.UiServerConnStatus
import io.qent.broxy.ui.adapter.store.AppStore
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.components.AppDialog
import io.qent.broxy.ui.components.CapabilitiesInlineSummary
import io.qent.broxy.ui.components.SettingsLikeItem
import io.qent.broxy.ui.theme.AppTheme
import io.qent.broxy.ui.viewmodels.AppState
import io.qent.broxy.ui.viewmodels.ServerEditorState
import kotlinx.coroutines.delay

@Composable
fun ServersScreen(ui: UIState, state: AppState, store: AppStore, notify: (String) -> Unit = {}) {
    var query by rememberSaveable { mutableStateOf("") }
    var viewing: UiServer? by remember { mutableStateOf<UiServer?>(null) }
    var pendingDeletion: UiServer? by remember { mutableStateOf<UiServer?>(null) }
    val editor = state.serverEditor.value

    Box(modifier = Modifier.fillMaxSize().padding(horizontal = AppTheme.spacing.md)) {
        if (editor != null) {
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(Modifier.height(1.dp))
                ServerEditorScreen(
                    ui = ui,
                    store = store,
                    editor = editor,
                    onClose = { state.serverEditor.value = null },
                    notify = notify
                )
                Spacer(Modifier.height(AppTheme.spacing.md))
            }
            return@Box
        }

        if (viewing != null) {
             Column(modifier = Modifier.fillMaxSize()) {
                Spacer(Modifier.height(1.dp))
                ServerCapabilitiesScreen(
                    store = store,
                    serverId = viewing!!.id,
                    onClose = { viewing = null }
                )
                Spacer(Modifier.height(AppTheme.spacing.md))
            }
            return@Box
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md)
        ) {
            Spacer(Modifier.height(1.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search servers") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
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
                            modifier = Modifier.weight(1f, fill = true),
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
                                    onEdit = {
                                        pendingDeletion = null
                                        state.serverEditor.value = ServerEditorState.Edit(cfg.id)
                                    },
                                    onDelete = { pendingDeletion = cfg }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(AppTheme.spacing.md))
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
    val statusColor = when (cfg.status) {
        UiServerConnStatus.Available -> AppTheme.extendedColors.success
        UiServerConnStatus.Error -> MaterialTheme.colorScheme.error
        UiServerConnStatus.Disabled -> MaterialTheme.colorScheme.outline
        UiServerConnStatus.Connecting -> MaterialTheme.colorScheme.secondary
    }

    val isDisabled = !cfg.enabled
    val isConnecting = cfg.enabled && cfg.status == UiServerConnStatus.Connecting
    val disabledAlpha = 0.55f
    val titleColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDisabled) disabledAlpha else 1f)
    val transportColor =
        MaterialTheme.colorScheme.primary.copy(alpha = if (isDisabled) disabledAlpha else 1f)
    val separatorColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isDisabled) disabledAlpha else 1f)
    val statusTextColor = if (isDisabled) MaterialTheme.colorScheme.onSurfaceVariant else statusColor
    val connectingSeconds by rememberConnectingSeconds(isConnecting, cfg.connectingSinceEpochMillis)
    val showErrorStatus = cfg.enabled && cfg.status == UiServerConnStatus.Error
    val showCapabilitiesSummary =
        cfg.enabled &&
                cfg.status == UiServerConnStatus.Available &&
                cfg.toolsCount != null &&
                cfg.promptsCount != null &&
                cfg.resourcesCount != null
    val showStatusText = isConnecting || showErrorStatus
    val statusText = when {
        isConnecting -> "Connecting: ${connectingSeconds} s"
        showErrorStatus -> UiServerConnStatus.Error.name
        else -> null
    }

    SettingsLikeItem(
        title = cfg.name,
        titleColor = titleColor,
        descriptionContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(
                    text = cfg.transportLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = transportColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                if (showCapabilitiesSummary) {
                    Text(" • ", style = MaterialTheme.typography.bodySmall, color = separatorColor)
                    CapabilitiesInlineSummary(
                        toolsCount = cfg.toolsCount ?: 0,
                        promptsCount = cfg.promptsCount ?: 0,
                        resourcesCount = cfg.resourcesCount ?: 0,
                        tint = separatorColor,
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                } else if (showStatusText && statusText != null) {
                    Text(" • ", style = MaterialTheme.typography.bodySmall, color = separatorColor)
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End
                    )
                }
            }
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
private fun rememberConnectingSeconds(
    isConnecting: Boolean,
    connectingSinceEpochMillis: Long?
): State<Long> = produceState(initialValue = 0L, key1 = isConnecting, key2 = connectingSinceEpochMillis) {
    if (!isConnecting) {
        value = 0L
        return@produceState
    }
    val startMillis = connectingSinceEpochMillis ?: System.currentTimeMillis()
    while (true) {
        val elapsedSeconds = ((System.currentTimeMillis() - startMillis) / 1_000L).coerceAtLeast(0)
        value = elapsedSeconds
        delay(1_000L)
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
