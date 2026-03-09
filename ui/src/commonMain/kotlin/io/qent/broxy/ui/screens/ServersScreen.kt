@file:Suppress("FunctionNaming")

package io.qent.broxy.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.UiServer
import io.qent.broxy.ui.adapter.models.UiServerConnStatus
import io.qent.broxy.ui.adapter.store.AppStore
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.components.AppVerticalScrollbar
import io.qent.broxy.ui.components.CapabilitiesInlineSummary
import io.qent.broxy.ui.components.DeleteConfirmationDialog
import io.qent.broxy.ui.components.HighlightedText
import io.qent.broxy.ui.components.SearchField
import io.qent.broxy.ui.components.SearchFieldFabAlignedBottomPadding
import io.qent.broxy.ui.components.ServerIconBadge
import io.qent.broxy.ui.components.SettingsLikeItem
import io.qent.broxy.ui.strings.LocalStrings
import io.qent.broxy.ui.theme.AppTheme
import io.qent.broxy.ui.viewmodels.AppState
import io.qent.broxy.ui.viewmodels.ServerEditorState
import kotlinx.coroutines.delay
import androidx.compose.foundation.lazy.items as lazyItems

private const val DISABLED_ALPHA = 0.55f
private const val CAPABILITIES_PULSE_ALPHA = 0.45f
private const val CAPABILITIES_PULSE_DURATION_MS = 900
private const val CONNECTING_POLL_INTERVAL_MS = 1_000L
private const val TOGGLE_SCALE = 0.7f
private val SERVER_ICON_SIZE = 42.dp

@Composable
@Suppress("LongMethod")
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
    val listState = rememberLazyListState()

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
                            state = listState,
                            modifier = Modifier.weight(1f, fill = true),
                            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
                            contentPadding =
                                PaddingValues(
                                    top = AppTheme.spacing.lg,
                                    bottom = AppTheme.spacing.fab,
                                ),
                        ) {
                            lazyItems(filtered, key = { it.id }) { cfg ->
                                ServerCard(
                                    cfg = cfg,
                                    searchQuery = trimmedQuery,
                                    onViewDetails = {
                                        state.serverEditor.value = null
                                        state.serverDetailsId.value = cfg.id
                                    },
                                    onIconClick = { ui.intents.pickServerIcon(cfg.id) },
                                    onIconRemove = { ui.intents.clearServerIcon(cfg.id) },
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

        AppVerticalScrollbar(
            listState = listState,
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .offset(x = AppTheme.spacing.md - AppTheme.strokeWidths.hairline),
        )

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
@Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList")
private fun ServerCard(
    cfg: UiServer,
    searchQuery: String,
    onViewDetails: () -> Unit,
    onIconClick: () -> Unit,
    onIconRemove: () -> Unit,
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
    val titleColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDisabled) DISABLED_ALPHA else 1f)
    val transportColor =
        MaterialTheme.colorScheme.primary.copy(alpha = if (isDisabled) DISABLED_ALPHA else 1f)
    val separatorColor =
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isDisabled) DISABLED_ALPHA else 1f)
    val statusTextColor =
        if (isDisabled) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            statusColor
        }
    val connectingSeconds by rememberConnectingSeconds(isConnecting, cfg.connectingSinceEpochMillis)
    val showErrorStatus = cfg.enabled && cfg.status == UiServerConnStatus.Error
    val errorMessage = cfg.errorMessage?.takeIf { it.isNotBlank() }
    val hasCapabilitiesSummary =
        cfg.toolsCount != null &&
            cfg.promptsCount != null &&
            cfg.resourcesCount != null
    val showCapabilitiesSummary =
        cfg.enabled &&
            hasCapabilitiesSummary &&
            cfg.status != UiServerConnStatus.Error &&
            cfg.status != UiServerConnStatus.Authorization
    val isRefreshingCapabilities = showCapabilitiesSummary && cfg.isRefreshingCapabilities
    val showStatusText = showErrorStatus || (isConnecting && !showCapabilitiesSummary)
    val canRefresh = cfg.enabled && !isConnecting && !cfg.isRefreshingCapabilities
    val iconSize = SERVER_ICON_SIZE
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
    val summaryAlpha =
        if (isRefreshingCapabilities) {
            val transition = rememberInfiniteTransition(label = "capabilitiesPulse")
            val alpha by transition.animateFloat(
                initialValue = CAPABILITIES_PULSE_ALPHA,
                targetValue = 1f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(durationMillis = CAPABILITIES_PULSE_DURATION_MS),
                        repeatMode = RepeatMode.Reverse,
                    ),
                label = "capabilitiesPulseAlpha",
            )
            alpha
        } else {
            1f
        }
    val summaryModifier = if (isRefreshingCapabilities) Modifier.alpha(summaryAlpha) else Modifier

    SettingsLikeItem(
        title = cfg.name,
        titleColor = titleColor,
        leadingContent = {
            ServerIconBadge(
                icon = cfg.icon,
                backgroundColor = titleColor,
                modifier = Modifier.size(iconSize),
                onClick = onIconClick,
                onRemove = onIconRemove,
            )
        },
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
                verticalAlignment = Alignment.CenterVertically,
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
                    Text(
                        strings.separatorDot,
                        style = MaterialTheme.typography.bodySmall,
                        color = separatorColor,
                        modifier = summaryModifier,
                    )
                    CapabilitiesInlineSummary(
                        toolsCount = cfg.toolsCount ?: 0,
                        promptsCount = cfg.promptsCount ?: 0,
                        resourcesCount = cfg.resourcesCount ?: 0,
                        tint = separatorColor,
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = summaryModifier,
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
            modifier = Modifier.scale(TOGGLE_SCALE),
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
            val elapsedSeconds =
                ((System.currentTimeMillis() - startMillis) / CONNECTING_POLL_INTERVAL_MS).coerceAtLeast(0)
            value = elapsedSeconds
            delay(CONNECTING_POLL_INTERVAL_MS)
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
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(AppTheme.spacing.sm))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
