package io.qent.broxy.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import io.qent.broxy.ui.strings.LocalStrings
import io.qent.broxy.ui.theme.AppTheme
import io.qent.broxy.ui.viewmodels.AppState
import io.qent.broxy.ui.viewmodels.PresetEditorState

@Composable
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
            Spacer(Modifier.height(AppTheme.spacing.sm))

            when (ui) {
                is UIState.Loading -> Text(strings.loading, style = MaterialTheme.typography.bodyMedium)
                is UIState.Error -> Text(strings.errorMessage(ui.message), style = MaterialTheme.typography.bodyMedium)
                is UIState.Ready -> {
                    val presets = ui.presets
                    if (presets.isEmpty()) {
                        EmptyState(
                            title = strings.presetsEmptyTitle,
                            subtitle = strings.presetsEmptySubtitle,
                        )
                    } else {
                        val trimmedQuery = query.trim()
                        val filtered =
                            presets.filter { p ->
                                trimmedQuery.isBlank() || p.name.contains(trimmedQuery, ignoreCase = true)
                            }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f, fill = true),
                            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
                            contentPadding = PaddingValues(bottom = AppTheme.spacing.fab),
                        ) {
                            items(filtered, key = { it.id }) { preset ->
                                PresetCard(
                                    preset = preset,
                                    searchQuery = trimmedQuery,
                                    isActive = preset.id == ui.selectedPresetId,
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
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
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

@Composable
private fun PresetCard(
    preset: UiPreset,
    searchQuery: String,
    isActive: Boolean,
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

    SettingsLikeItem(
        title = preset.name,
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
            CapabilitiesInlineSummary(
                toolsCount = preset.toolsCount,
                promptsCount = preset.promptsCount,
                resourcesCount = preset.resourcesCount,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        onClick = onEdit,
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
