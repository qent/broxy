package io.qent.broxy.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.UiServer
import io.qent.broxy.ui.adapter.models.UiServerConnStatus
import io.qent.broxy.ui.adapter.store.AppStore
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.components.CapabilitiesInlineSummary
import io.qent.broxy.ui.components.DeleteConfirmationDialog
import io.qent.broxy.ui.components.HighlightedText
import io.qent.broxy.ui.components.SearchField
import io.qent.broxy.ui.components.SearchFieldFabAlignedBottomPadding
import io.qent.broxy.ui.components.SettingsLikeItem
import io.qent.broxy.ui.strings.LocalStrings
import io.qent.broxy.ui.theme.AppTheme
import io.qent.broxy.ui.viewmodels.AppState
import io.qent.broxy.ui.viewmodels.ServerEditorState
import kotlinx.coroutines.delay

@Composable
fun ServersScreen(
    ui: UIState,
    state: AppState,
    store: AppStore,
    notify: (String) -> Unit = {},
) {
    val strings = LocalStrings.current
    var query by rememberSaveable { mutableStateOf("") }
    var pendingDeletion: UiServer? by remember { mutableStateOf<UiServer?>(null) }
    val editor = state.serverEditor.value
    val viewingId = state.serverDetailsId.value

    Box(modifier = Modifier.fillMaxSize().padding(horizontal = AppTheme.spacing.md)) {
        if (editor != null) {
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(Modifier.height(1.dp))
                ServerEditorScreen(
                    ui = ui,
                    store = store,
                    editor = editor,
                    onClose = { state.serverEditor.value = null },
                    notify = notify,
                )
            }
            return@Box
        }

        if (viewingId != null) {
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(Modifier.height(1.dp))
                ServerCapabilitiesScreen(
                    store = store,
                    serverId = viewingId,
                    onClose = { state.serverDetailsId.value = null },
                )
            }
            return@Box
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
        ) {
            Spacer(Modifier.height(AppTheme.spacing.sm))

            when (ui) {
                is UIState.Loading -> Text(strings.loading, style = MaterialTheme.typography.bodyMedium)
                is UIState.Error -> Text(strings.errorMessage(ui.message), style = MaterialTheme.typography.bodyMedium)
                is UIState.Ready -> {
                    val servers = ui.servers
                    if (servers.isEmpty()) {
                        EmptyState(
                            title = strings.serversEmptyTitle,
                            subtitle = strings.serversEmptySubtitle,
                        )
                    } else {
                        val trimmedQuery = query.trim()
                        val filtered =
                            servers.filter { cfg ->
                                trimmedQuery.isBlank() || cfg.name.contains(trimmedQuery, ignoreCase = true)
                            }
                        LazyColumn(
                            modifier = Modifier.weight(1f, fill = true),
                            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
                            contentPadding = PaddingValues(bottom = AppTheme.spacing.fab),
                        ) {
                            items(filtered, key = { it.id }) { cfg ->
                                ServerCard(
                                    cfg = cfg,
                                    searchQuery = trimmedQuery,
                                    onViewDetails = {
                                        state.serverEditor.value = null
                                        state.serverDetailsId.value = cfg.id
                                    },
                                    onToggle = { id, enabled ->
                                        ui.intents.toggleServer(id, enabled)
                                    },
                                    onRefresh = {
                                        ui.intents.refreshServerCapabilities(cfg.id)
                                    },
                                    onEdit = {
                                        pendingDeletion = null
                                        state.serverDetailsId.value = null
                                        state.serverEditor.value = ServerEditorState.Edit(cfg.id)
                                    },
                                    onDelete = { pendingDeletion = cfg },
                                )
                            }
                        }
                    }
                }
            }
        }

        SearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = strings.searchServers,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = SearchFieldFabAlignedBottomPadding),
        )

        val readyUi = ui as? UIState.Ready
        val toDelete = pendingDeletion
        if (readyUi != null && toDelete != null) {
            DeleteConfirmationDialog(
                title = strings.deleteServerTitle,
                prompt = strings.deleteServerPrompt(toDelete.name),
                description = strings.deleteServerDescription,
                onConfirm = {
                    readyUi.intents.removeServer(toDelete.id)
                    pendingDeletion = null
                },
                onDismiss = { pendingDeletion = null },
                confirmLabel = strings.delete,
                dismissLabel = strings.cancel,
            )
        }
    }
}

@Composable
private fun ServerCard(
    cfg: UiServer,
    searchQuery: String,
    onViewDetails: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    val strings = LocalStrings.current
    val statusColor =
        when (cfg.status) {
            UiServerConnStatus.Available -> AppTheme.extendedColors.success
            UiServerConnStatus.Error -> MaterialTheme.colorScheme.error
            UiServerConnStatus.Disabled -> MaterialTheme.colorScheme.outline
            UiServerConnStatus.Authorization -> MaterialTheme.colorScheme.secondary
            UiServerConnStatus.Connecting -> MaterialTheme.colorScheme.secondary
        }

    val isDisabled = !cfg.enabled
    val isConnecting =
        cfg.enabled &&
            (cfg.status == UiServerConnStatus.Authorization || cfg.status == UiServerConnStatus.Connecting)
    val disabledAlpha = 0.55f
    val titleColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDisabled) disabledAlpha else 1f)
    val transportColor =
        MaterialTheme.colorScheme.primary.copy(alpha = if (isDisabled) disabledAlpha else 1f)
    val separatorColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isDisabled) disabledAlpha else 1f)
    val statusTextColor = if (isDisabled) MaterialTheme.colorScheme.onSurfaceVariant else statusColor
    val connectingSeconds by rememberConnectingSeconds(isConnecting, cfg.connectingSinceEpochMillis)
    val showErrorStatus = cfg.enabled && cfg.status == UiServerConnStatus.Error
    val errorMessage = cfg.errorMessage?.takeIf { it.isNotBlank() }
    val showCapabilitiesSummary =
        cfg.enabled &&
            cfg.status == UiServerConnStatus.Available &&
            cfg.toolsCount != null &&
            cfg.promptsCount != null &&
            cfg.resourcesCount != null
    val showStatusText = isConnecting || showErrorStatus
    val canRefresh = cfg.enabled && !isConnecting
    val statusText =
        when {
            isConnecting ->
                if (cfg.status == UiServerConnStatus.Authorization) {
                    strings.authorization(connectingSeconds)
                } else {
                    strings.connecting(connectingSeconds)
                }
            showErrorStatus -> errorMessage?.let { strings.errorMessage(it) } ?: strings.errorLabel
            else -> null
        }

    SettingsLikeItem(
        title = cfg.name,
        titleColor = titleColor,
        titleContent = {
            HighlightedText(
                text = cfg.name,
                query = searchQuery,
                style = MaterialTheme.typography.titleSmall,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        descriptionContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                HighlightedText(
                    text = cfg.transportLabel,
                    query = searchQuery,
                    style = MaterialTheme.typography.bodySmall,
                    color = transportColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )

                if (showCapabilitiesSummary) {
                    Text(strings.separatorDot, style = MaterialTheme.typography.bodySmall, color = separatorColor)
                    CapabilitiesInlineSummary(
                        toolsCount = cfg.toolsCount ?: 0,
                        promptsCount = cfg.promptsCount ?: 0,
                        resourcesCount = cfg.resourcesCount ?: 0,
                        tint = separatorColor,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                } else if (showStatusText && statusText != null) {
                    Text(strings.separatorDot, style = MaterialTheme.typography.bodySmall, color = separatorColor)
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                    )
                }
            }
        },
        onClick = onViewDetails,
    ) {
        Switch(
            checked = cfg.enabled,
            onCheckedChange = { enabled -> onToggle(cfg.id, enabled) },
            enabled = cfg.canToggle,
            modifier = Modifier.scale(0.7f),
            colors =
                SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                ),
        )
        IconButton(
            onClick = onRefresh,
            enabled = canRefresh,
        ) {
            Icon(
                Icons.Outlined.Refresh,
                contentDescription = strings.refreshContentDescription,
                tint = MaterialTheme.colorScheme.secondary,
            )
        }
        IconButton(onClick = onEdit) {
            Icon(
                Icons.Outlined.Edit,
                contentDescription = strings.editContentDescription,
                tint = MaterialTheme.colorScheme.secondary,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = strings.deleteContentDescription,
                tint = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun rememberConnectingSeconds(
    isConnecting: Boolean,
    connectingSinceEpochMillis: Long?,
): State<Long> =
    produceState(initialValue = 0L, key1 = isConnecting, key2 = connectingSinceEpochMillis) {
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
private fun EmptyState(
    title: String,
    subtitle: String,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(AppTheme.spacing.sm))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
