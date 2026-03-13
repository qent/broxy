@file:Suppress("FunctionNaming")

package io.qent.broxy.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.qent.broxy.ui.adapter.models.UiPreset
import io.qent.broxy.ui.adapter.store.AppStore
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.components.AppVerticalScrollbar
import io.qent.broxy.ui.components.CapabilitiesInlineSummary
import io.qent.broxy.ui.components.DeleteConfirmationDialog
import io.qent.broxy.ui.components.HighlightedText
import io.qent.broxy.ui.components.SearchField
import io.qent.broxy.ui.components.SearchFieldFabAlignedBottomPadding
import io.qent.broxy.ui.components.SettingsLikeItem
import io.qent.broxy.ui.components.dragReorderHandle
import io.qent.broxy.ui.components.moveItem
import io.qent.broxy.ui.components.rememberDragReorderState
import io.qent.broxy.ui.strings.LocalStrings
import io.qent.broxy.ui.theme.AppTheme
import io.qent.broxy.ui.theme.PRESET_STATUS_DOT_PARTIAL_HEX
import io.qent.broxy.ui.viewmodels.AppState
import io.qent.broxy.ui.viewmodels.PresetEditorState

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
fun PresetsScreen(
    ui: UIState,
    state: AppState,
    store: AppStore,
) {
    val strings = LocalStrings.current
    var query by rememberSaveable { mutableStateOf("") }
    var pendingDeletion: UiPreset? by remember { mutableStateOf<UiPreset?>(null) }
    val editor = state.presetEditor.value
    val listState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize().padding(horizontal = AppTheme.spacing.md)) {
        if (editor != null) {
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(Modifier.height(1.dp))
                PresetEditorScreen(
                    ui = ui,
                    store = store,
                    editor = editor,
                    onClose = { state.presetEditor.value = null },
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
                    val presets = ui.presets
                    val disabledServerIds =
                        ui.servers
                            .asSequence()
                            .filterNot { it.enabled }
                            .map { it.id }
                            .toSet()
                    if (presets.isEmpty()) {
                        EmptyState(
                            title = strings.presetsEmptyTitle,
                            subtitle = strings.presetsEmptySubtitle,
                        )
                    } else {
                        val currentPresetOrder = presets.map { it.id }
                        val orderedPresetIds =
                            remember(currentPresetOrder) {
                                mutableStateListOf<String>().apply { addAll(currentPresetOrder) }
                            }
                        val presetsById = remember(presets) { presets.associateBy { it.id } }
                        val trimmedQuery = query.trim()
                        val canReorder = trimmedQuery.isBlank()
                        val visiblePresetIds =
                            if (trimmedQuery.isBlank()) {
                                orderedPresetIds.toList()
                            } else {
                                orderedPresetIds.filter { presetId ->
                                    presetsById[presetId]?.name?.contains(trimmedQuery, ignoreCase = true) == true
                                }
                            }
                        val reorderState =
                            rememberDragReorderState(
                                keysProvider = { orderedPresetIds.toList() },
                                onMove = { from, to -> orderedPresetIds.moveItem(from, to) },
                                onDragStopped = {
                                    if (!canReorder) return@rememberDragReorderState
                                    val updatedOrder = orderedPresetIds.toList()
                                    if (updatedOrder != currentPresetOrder) {
                                        ui.intents.reorderPresets(updatedOrder)
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
                            itemsIndexed(visiblePresetIds, key = { _, presetId -> presetId }) { _, presetId ->
                                val preset = presetsById[presetId] ?: return@itemsIndexed
                                val capabilityStatus = resolveCapabilityStatus(preset, disabledServerIds)
                                val isDragging = reorderState.isDragging(preset.id)
                                val cardModifier =
                                    Modifier
                                        .onSizeChanged { reorderState.updateItemHeight(preset.id, it.height) }
                                        .graphicsLayer {
                                            translationY = reorderState.offsetFor(preset.id)
                                            shadowElevation = if (isDragging) DRAGGED_CARD_ELEVATION else 0f
                                        }.zIndex(if (isDragging) 1f else 0f)
                                PresetCard(
                                    preset = preset,
                                    modifier = cardModifier,
                                    searchQuery = trimmedQuery,
                                    isActive = preset.id == ui.activeProxyPresetId,
                                    hasDisabledTools = capabilityStatus.hasDisabledTools,
                                    hasDisabledPrompts = capabilityStatus.hasDisabledPrompts,
                                    hasDisabledResources = capabilityStatus.hasDisabledResources,
                                    hasNoAvailableCapabilities = capabilityStatus.hasNoAvailableCapabilities,
                                    hasCapabilityWarning = capabilityStatus.hasCapabilityWarning,
                                    reorderHandle = {
                                        ReorderHandle(
                                            enabled = canReorder && orderedPresetIds.size > 1,
                                            contentDescription = strings.reorderContentDescription,
                                            modifier =
                                                Modifier.dragReorderHandle(
                                                    key = preset.id,
                                                    enabled = canReorder && orderedPresetIds.size > 1,
                                                    state = reorderState,
                                                ),
                                        )
                                    },
                                    onSelect = { ui.intents.selectProxyPreset(preset.id) },
                                    onEdit = {
                                        pendingDeletion = null
                                        state.presetEditor.value = PresetEditorState.Edit(preset.id)
                                    },
                                    onDelete = { pendingDeletion = preset },
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
            placeholder = strings.searchPresets,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = SearchFieldFabAlignedBottomPadding),
        )

        val readyUi = ui as? UIState.Ready
        val toDelete = pendingDeletion
        if (readyUi != null && toDelete != null) {
            DeleteConfirmationDialog(
                title = strings.deletePresetTitle,
                prompt = strings.deletePresetPrompt(toDelete.name),
                description = strings.deletePresetDescription,
                onConfirm = {
                    readyUi.intents.removePreset(toDelete.id)
                    pendingDeletion = null
                },
                onDismiss = { pendingDeletion = null },
                confirmLabel = strings.delete,
                dismissLabel = strings.cancel,
            )
        }
    }
}

private const val DRAGGED_CARD_ELEVATION = 12f
private val REORDER_HANDLE_ICON_SIZE = 20.dp
private val REORDER_HANDLE_TOUCH_SIZE = 28.dp
private val REORDER_HANDLE_HORIZONTAL_PADDING = 2.dp
private val REORDER_HANDLE_VERTICAL_PADDING = 4.dp

@Composable
@Suppress("LongMethod", "LongParameterList")
private fun PresetCard(
    preset: UiPreset,
    modifier: Modifier = Modifier,
    searchQuery: String,
    isActive: Boolean,
    hasDisabledTools: Boolean,
    hasDisabledPrompts: Boolean,
    hasDisabledResources: Boolean,
    hasNoAvailableCapabilities: Boolean,
    hasCapabilityWarning: Boolean,
    reorderHandle: (@Composable RowScope.() -> Unit)? = null,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val strings = LocalStrings.current
    val border =
        if (isActive) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        }
    val cardAlpha = if (hasNoAvailableCapabilities) NO_AVAILABLE_CAPABILITIES_CARD_ALPHA else 1f

    SettingsLikeItem(
        title = preset.name,
        modifier = modifier.alpha(cardAlpha),
        startControl = reorderHandle,
        titleContent = {
            HighlightedText(
                text = preset.name,
                query = searchQuery,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        descriptionContent = {
            val defaultTint = MaterialTheme.colorScheme.onSurfaceVariant
            val warningTint = Color(PRESET_STATUS_DOT_PARTIAL_HEX)
            val toolsTint =
                if (hasNoAvailableCapabilities) {
                    defaultTint
                } else if (hasDisabledTools) {
                    warningTint
                } else {
                    defaultTint
                }
            val promptsTint =
                if (hasNoAvailableCapabilities) {
                    defaultTint
                } else if (hasDisabledPrompts) {
                    warningTint
                } else {
                    defaultTint
                }
            val resourcesTint =
                if (hasNoAvailableCapabilities) {
                    defaultTint
                } else if (hasDisabledResources) {
                    warningTint
                } else {
                    defaultTint
                }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CapabilitiesInlineSummary(
                    toolsCount = preset.toolsCount,
                    promptsCount = preset.promptsCount,
                    resourcesCount = preset.resourcesCount,
                    tint = defaultTint,
                    toolsTint = toolsTint,
                    promptsTint = promptsTint,
                    resourcesTint = resourcesTint,
                )
                if (hasCapabilityWarning) {
                    Spacer(Modifier.width(AppTheme.spacing.sm))
                    Text(
                        text =
                            if (hasNoAvailableCapabilities) {
                                strings.presetAllCapabilitiesDisabled
                            } else {
                                strings.presetContainsDisabledCapabilities
                            },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hasNoAvailableCapabilities) defaultTint else warningTint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        onClick = onSelect,
        border = border,
    ) {
        IconButton(onClick = onEdit) {
            Icon(
                Icons.Outlined.Edit,
                contentDescription = strings.editPresetContentDescription,
                tint = MaterialTheme.colorScheme.secondary,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = strings.deletePresetContentDescription,
                tint = MaterialTheme.colorScheme.secondary,
            )
        }
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

private data class PresetCapabilityStatus(
    val hasDisabledTools: Boolean,
    val hasDisabledPrompts: Boolean,
    val hasDisabledResources: Boolean,
    val hasNoAvailableCapabilities: Boolean,
) {
    val hasCapabilityWarning: Boolean
        get() = hasDisabledTools || hasDisabledPrompts || hasDisabledResources || hasNoAvailableCapabilities
}

private fun resolveCapabilityStatus(
    preset: UiPreset,
    disabledServerIds: Set<String>,
): PresetCapabilityStatus {
    val hasDisabledTools = preset.toolsServerIds.any(disabledServerIds::contains)
    val hasDisabledPrompts = preset.promptsServerIds.any(disabledServerIds::contains)
    val hasDisabledResources = preset.resourcesServerIds.any(disabledServerIds::contains)
    val hasEnabledCapabilities = preset.toolsCount + preset.promptsCount + preset.resourcesCount > 0
    val hasAvailableCapabilities =
        (preset.toolsCount > 0 && preset.toolsServerIds.any { it !in disabledServerIds }) ||
            (preset.promptsCount > 0 && preset.promptsServerIds.any { it !in disabledServerIds }) ||
            (preset.resourcesCount > 0 && preset.resourcesServerIds.any { it !in disabledServerIds })
    val hasNoAvailableCapabilities =
        preset.allCapabilitiesDisabled ||
            !hasEnabledCapabilities ||
            (hasEnabledCapabilities && !hasAvailableCapabilities)
    return PresetCapabilityStatus(
        hasDisabledTools = hasDisabledTools,
        hasDisabledPrompts = hasDisabledPrompts,
        hasDisabledResources = hasDisabledResources,
        hasNoAvailableCapabilities = hasNoAvailableCapabilities,
    )
}

private const val NO_AVAILABLE_CAPABILITIES_CARD_ALPHA = 0.58f
