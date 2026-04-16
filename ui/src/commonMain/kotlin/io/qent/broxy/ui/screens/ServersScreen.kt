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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.qent.broxy.ui.adapter.models.UiImportedServer
import io.qent.broxy.ui.adapter.models.UiServer
import io.qent.broxy.ui.adapter.models.UiServerConnStatus
import io.qent.broxy.ui.adapter.store.AppStore
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.components.AppPrimaryButton
import io.qent.broxy.ui.components.AppSecondaryButton
import io.qent.broxy.ui.components.AppVerticalScrollbar
import io.qent.broxy.ui.components.CapabilitiesInlineSummary
import io.qent.broxy.ui.components.DeleteConfirmationDialog
import io.qent.broxy.ui.components.HighlightedText
import io.qent.broxy.ui.components.SearchField
import io.qent.broxy.ui.components.SearchFieldFabAlignedBottomPadding
import io.qent.broxy.ui.components.ServerIconBadge
import io.qent.broxy.ui.components.SettingsLikeItem
import io.qent.broxy.ui.components.dragReorderHandle
import io.qent.broxy.ui.components.moveItem
import io.qent.broxy.ui.components.rememberDragReorderState
import io.qent.broxy.ui.strings.LocalStrings
import io.qent.broxy.ui.theme.AppTheme
import io.qent.broxy.ui.viewmodels.AppState
import io.qent.broxy.ui.viewmodels.ServerEditorState
import kotlinx.coroutines.delay

private const val DISABLED_ALPHA = 0.55f
private const val CAPABILITIES_PULSE_ALPHA = 0.45f
private const val CAPABILITIES_PULSE_DURATION_MS = 900
private const val CONNECTING_POLL_INTERVAL_MS = 1_000L
private const val TOGGLE_SCALE = 0.7f
private const val DRAGGED_CARD_ELEVATION = 12f
private val TOGGLE_END_COMPENSATION = 4.dp
private val SERVER_ICON_SIZE = 42.dp
private val REORDER_HANDLE_ICON_SIZE = 20.dp
private val REORDER_HANDLE_TOUCH_SIZE = 28.dp
private val REORDER_HANDLE_HORIZONTAL_PADDING = 2.dp
private val REORDER_HANDLE_VERTICAL_PADDING = 4.dp
private val IMPORT_ACTION_WIDTH = 90.dp

internal data class CatalogInstalledServerScrollDecision(
    val shouldScroll: Boolean,
    val targetIndex: Int = -1,
)

private data class ImportedServerListItem(
    val clientId: String,
    val clientName: String,
    val server: UiImportedServer,
)

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
fun ServersScreen(
    ui: UIState,
    state: AppState,
    store: AppStore,
    notify: (String) -> Unit = {},
) {
    val strings = LocalStrings.current
    var query by rememberSaveable { mutableStateOf("") }
    var pendingDeletion: UiServer? by remember { mutableStateOf<UiServer?>(null) }
    val readyUi = ui as? UIState.Ready
    if (readyUi?.pendingImportedServerCreate != null) {
        LaunchedEffect(readyUi.pendingImportedServerCreateRequestId) {
            val pendingCreate = readyUi.pendingImportedServerCreate ?: return@LaunchedEffect
            state.serverDetailsId.value = null
            state.serverEditor.value =
                ServerEditorState.CreateFromImport(
                    clientId = pendingCreate.clientId,
                    sourceServerId = pendingCreate.sourceServerId,
                    initialDraft = pendingCreate.draft,
                )
            readyUi.intents.consumePendingImportedServerCreate()
        }
    }
    val editor = state.serverEditor.value
    val viewingId = state.serverDetailsId.value
    val listState = rememberLazyListState()
    val serverIds = readyUi?.servers?.map { it.id }.orEmpty()
    var lastHandledCatalogInstalledServerRequestId by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(readyUi?.pendingCatalogInstalledServerRequestId, serverIds) {
        val ready = readyUi ?: return@LaunchedEffect
        val scrollDecision =
            resolveCatalogInstalledServerScrollDecision(
                pendingCatalogInstalledServerId = ready.pendingCatalogInstalledServerId,
                pendingCatalogInstalledServerRequestId = ready.pendingCatalogInstalledServerRequestId,
                lastHandledPendingCatalogInstalledServerRequestId = lastHandledCatalogInstalledServerRequestId,
                serverIds = serverIds,
            )
        if (!scrollDecision.shouldScroll) {
            return@LaunchedEffect
        }
        query = ""
        listState.scrollToItem(scrollDecision.targetIndex)
        lastHandledCatalogInstalledServerRequestId = ready.pendingCatalogInstalledServerRequestId
        ready.intents.consumePendingCatalogInstalledServer()
    }

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
                    val importGroups = ui.importedServerGroups
                    if (servers.isEmpty() && importGroups.isEmpty()) {
                        EmptyState(
                            title = strings.serversEmptyTitle,
                            subtitle = strings.serversEmptySubtitle,
                        )
                    } else {
                        val currentServerOrder = servers.map { it.id }
                        val orderedServerIds =
                            remember(currentServerOrder) {
                                mutableStateListOf<String>().apply { addAll(currentServerOrder) }
                            }
                        val serversById = remember(servers) { servers.associateBy { it.id } }
                        val trimmedQuery = query.trim()
                        val canReorder = trimmedQuery.isBlank()
                        val visibleServerIds =
                            if (trimmedQuery.isBlank()) {
                                orderedServerIds.toList()
                            } else {
                                orderedServerIds.filter { serverId ->
                                    serversById[serverId]?.name?.contains(trimmedQuery, ignoreCase = true) == true
                                }
                            }
                        val visibleImportedServers =
                            importGroups
                                .asSequence()
                                .flatMap { group ->
                                    group.servers.asSequence().map { server ->
                                        ImportedServerListItem(
                                            clientId = group.clientId,
                                            clientName = group.clientName,
                                            server = server,
                                        )
                                    }
                                }.filter { item ->
                                    if (trimmedQuery.isBlank()) {
                                        true
                                    } else {
                                        item.server.name.contains(trimmedQuery, ignoreCase = true) ||
                                            item.clientName.contains(trimmedQuery, ignoreCase = true)
                                    }
                                }.sortedWith(
                                    compareBy<ImportedServerListItem>(
                                        { it.server.name.lowercase() },
                                        { it.clientName.lowercase() },
                                        { it.server.sourceServerId.lowercase() },
                                    ),
                                ).toList()
                        val reorderState =
                            rememberDragReorderState(
                                keysProvider = { orderedServerIds.toList() },
                                onMove = { from, to -> orderedServerIds.moveItem(from, to) },
                                onDragStopped = {
                                    if (!canReorder) return@rememberDragReorderState
                                    val updatedOrder = orderedServerIds.toList()
                                    if (updatedOrder != currentServerOrder) {
                                        ui.intents.reorderServers(updatedOrder)
                                    }
                                },
                            )
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
                            itemsIndexed(visibleServerIds, key = { _, serverId -> serverId }) { _, serverId ->
                                val cfg = serversById[serverId] ?: return@itemsIndexed
                                val isDragging = reorderState.isDragging(cfg.id)
                                val cardModifier =
                                    Modifier
                                        .onSizeChanged { reorderState.updateItemHeight(cfg.id, it.height) }
                                        .graphicsLayer {
                                            translationY = reorderState.offsetFor(cfg.id)
                                            shadowElevation = if (isDragging) DRAGGED_CARD_ELEVATION else 0f
                                        }.zIndex(if (isDragging) 1f else 0f)
                                ServerCard(
                                    cfg = cfg,
                                    modifier = cardModifier,
                                    searchQuery = trimmedQuery,
                                    onViewDetails = {
                                        state.serverEditor.value = null
                                        state.serverDetailsId.value = cfg.id
                                    },
                                    reorderHandle = {
                                        ReorderHandle(
                                            enabled = canReorder && orderedServerIds.size > 1,
                                            contentDescription = strings.reorderContentDescription,
                                            modifier =
                                                Modifier.dragReorderHandle(
                                                    key = cfg.id,
                                                    enabled = canReorder && orderedServerIds.size > 1,
                                                    state = reorderState,
                                                ),
                                        )
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
                            if (visibleImportedServers.isNotEmpty()) {
                                item(key = "import-section-header") {
                                    ImportSectionHeader(title = strings.importSectionTitle)
                                }
                                itemsIndexed(
                                    items = visibleImportedServers,
                                    key = { _, item -> "${item.clientId}:${item.server.sourceServerId}" },
                                ) { _, item ->
                                    ImportedServerCard(
                                        server = item.server,
                                        clientName = item.clientName,
                                        searchQuery = trimmedQuery,
                                        onImport = {
                                            ui.intents.importServerFromClient(
                                                clientId = item.clientId,
                                                sourceServerId = item.server.sourceServerId,
                                            )
                                        },
                                        onHide = {
                                            ui.intents.hideImportedServer(
                                                clientId = item.clientId,
                                                sourceServerId = item.server.sourceServerId,
                                            )
                                        },
                                    )
                                }
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

        val showSearchField =
            when (ui) {
                is UIState.Ready ->
                    shouldShowServersSearchField(
                        serverCount = ui.servers.size,
                        importedServerCount = ui.importedServerGroups.sumOf { it.servers.size },
                    )
                else -> false
            }

        if (showSearchField) {
            SearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = strings.searchServers,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = SearchFieldFabAlignedBottomPadding),
            )
        }

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
    modifier: Modifier = Modifier,
    searchQuery: String,
    onViewDetails: () -> Unit,
    reorderHandle: (@Composable RowScope.() -> Unit)? = null,
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
        modifier = modifier,
        titleColor = titleColor,
        startControl = reorderHandle,
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
            modifier = Modifier.scale(TOGGLE_SCALE).padding(end = TOGGLE_END_COMPENSATION),
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
private fun ImportSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().padding(top = AppTheme.spacing.sm, start = AppTheme.spacing.sm),
    )
}

@Composable
@Suppress("LongMethod", "LongParameterList")
private fun ImportedServerCard(
    server: UiImportedServer,
    clientName: String,
    searchQuery: String,
    onImport: () -> Unit,
    onHide: () -> Unit,
) {
    val strings = LocalStrings.current
    SettingsLikeItem(
        title = server.name,
        leadingContent = {
            ServerIconBadge(
                icon = server.icon,
                backgroundColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(SERVER_ICON_SIZE),
                onClick = null,
                onRemove = null,
            )
        },
        titleContent = {
            HighlightedText(
                text = server.name,
                query = searchQuery,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
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
                    text = server.transportLabel,
                    query = searchQuery,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = strings.separatorDot,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
                ) {
                    ImportedClientInlineLabel(
                        clientName = clientName,
                        query = searchQuery,
                    )
                }
            }
        },
    ) {
        AppPrimaryButton(
            onClick = onImport,
            modifier = Modifier.width(IMPORT_ACTION_WIDTH).height(32.dp),
        ) {
            Text(strings.importButtonLabel, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.width(AppTheme.spacing.sm))
        AppSecondaryButton(
            onClick = onHide,
            modifier = Modifier.width(IMPORT_ACTION_WIDTH).height(32.dp),
        ) {
            Text(strings.hideButtonLabel, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ImportedClientInlineLabel(
    clientName: String,
    query: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HighlightedText(
            text = clientName,
            query = query,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ReorderHandle(
    enabled: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier,
    iconSize: Dp = REORDER_HANDLE_ICON_SIZE,
    touchSize: Dp = REORDER_HANDLE_TOUCH_SIZE,
) {
    Icon(
        imageVector = Icons.Outlined.DragIndicator,
        contentDescription = contentDescription,
        tint =
            if (enabled) {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        modifier =
            modifier
                .size(touchSize)
                .padding(
                    horizontal = REORDER_HANDLE_HORIZONTAL_PADDING,
                    vertical = REORDER_HANDLE_VERTICAL_PADDING,
                ).size(iconSize),
    )
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

internal fun resolveCatalogInstalledServerScrollDecision(
    pendingCatalogInstalledServerId: String?,
    pendingCatalogInstalledServerRequestId: Long,
    lastHandledPendingCatalogInstalledServerRequestId: Long?,
    serverIds: List<String>,
): CatalogInstalledServerScrollDecision {
    val targetServerId = pendingCatalogInstalledServerId?.trim().orEmpty()
    val targetIndex = serverIds.indexOf(targetServerId)
    val shouldScroll =
        targetServerId.isNotEmpty() &&
            pendingCatalogInstalledServerRequestId > 0L &&
            lastHandledPendingCatalogInstalledServerRequestId != pendingCatalogInstalledServerRequestId &&
            targetIndex >= 0
    return if (shouldScroll) {
        CatalogInstalledServerScrollDecision(
            shouldScroll = true,
            targetIndex = targetIndex,
        )
    } else {
        CatalogInstalledServerScrollDecision(shouldScroll = false)
    }
}

internal fun shouldShowServersSearchField(
    serverCount: Int,
    importedServerCount: Int,
): Boolean = serverCount > 0 || importedServerCount > 0
