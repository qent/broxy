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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.UiPreset
import io.qent.broxy.ui.adapter.store.AppStore
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.components.AppDialog
import io.qent.broxy.ui.components.CapabilitiesInlineSummary
import io.qent.broxy.ui.components.SettingsLikeItem
import io.qent.broxy.ui.theme.AppTheme
import io.qent.broxy.ui.viewmodels.AppState
import io.qent.broxy.ui.viewmodels.PresetEditorState

@Composable
fun PresetsScreen(ui: UIState, state: AppState, store: AppStore) {
    var query by rememberSaveable { mutableStateOf("") }
    var pendingDeletion: UiPreset? by remember { mutableStateOf<UiPreset?>(null) }
    val editor = state.presetEditor.value

    Box(modifier = Modifier.fillMaxSize().padding(horizontal = AppTheme.spacing.md)) {
        if (editor != null) {
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(Modifier.height(1.dp))
                PresetEditorScreen(
                    ui = ui,
                    store = store,
                    editor = editor,
                    onClose = { state.presetEditor.value = null }
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
                label = { Text("Search presets") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            when (ui) {
                is UIState.Loading -> Text("Loading...", style = MaterialTheme.typography.bodyMedium)
                is UIState.Error -> Text("Error: ${ui.message}", style = MaterialTheme.typography.bodyMedium)
                is UIState.Ready -> {
                    val presets = ui.presets
                    if (presets.isEmpty()) {
                        EmptyState(
                            title = "No presets yet",
                            subtitle = "Use the + button to add your first preset"
                        )
                    } else {
                        val filtered = presets.filter { p ->
                            p.name.contains(query, ignoreCase = true) ||
                                p.id.contains(query, ignoreCase = true)
                        }
                        LazyColumn(
                            modifier = Modifier.weight(1f, fill = true),
                            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
                            contentPadding = PaddingValues(bottom = PresetListBottomPadding)
                        ) {
                            items(filtered, key = { it.id }) { preset ->
                                PresetCard(
                                    preset = preset,
                                    onEdit = {
                                        pendingDeletion = null
                                        state.presetEditor.value = PresetEditorState.Edit(preset.id)
                                    },
                                    onDelete = { pendingDeletion = preset }
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
            DeletePresetDialog(
                preset = toDelete,
                onConfirm = {
                    readyUi.intents.removePreset(toDelete.id)
                    pendingDeletion = null
                },
                onDismiss = { pendingDeletion = null }
            )
        }
    }
}

@Composable
private fun PresetCard(
    preset: UiPreset,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    SettingsLikeItem(
        title = preset.name,
        descriptionContent = {
            CapabilitiesInlineSummary(
                toolsCount = preset.toolsCount,
                promptsCount = preset.promptsCount,
                resourcesCount = preset.resourcesCount,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        onClick = onEdit
    ) {
        IconButton(onClick = onEdit) {
            Icon(Icons.Outlined.Edit, contentDescription = "Edit preset", tint = MaterialTheme.colorScheme.secondary)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = "Delete preset", tint = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun DeletePresetDialog(
    preset: UiPreset,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AppDialog(
        title = "Delete preset",
        onDismissRequest = onDismiss,
        dismissButton = { AppSecondaryButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = { AppPrimaryButton(onClick = onConfirm) { Text("Delete") } }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
            Text(
                text = "Remove \"${preset.name}\"?",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "This preset will disappear from broxy, including the CLI shortcuts that rely on it. This action cannot be undone.",
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
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(AppTheme.spacing.sm))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val PresetListBottomPadding = 88.dp
