package io.qent.broxy.ui.screens

import AppPrimaryButton
import AppSecondaryButton
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.UiPreset
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.adapter.store.AppStore
import io.qent.broxy.ui.components.AppDialog
import io.qent.broxy.ui.viewmodels.AppState
import io.qent.broxy.ui.theme.AppTheme

@Composable
fun PresetsScreen(ui: UIState, state: AppState, store: AppStore) {
    var query by rememberSaveable { mutableStateOf("") }
    var editing: UiPreset? by remember { mutableStateOf<UiPreset?>(null) }
    var pendingDeletion: UiPreset? by remember { mutableStateOf<UiPreset?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppTheme.spacing.md)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search presets") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(AppTheme.spacing.md))

        when (ui) {
            is UIState.Loading -> Text("Loading...", style = MaterialTheme.typography.bodyMedium)
            is UIState.Error -> Text("Error: ${ui.message}")
            is UIState.Ready -> {
                val presets = ui.presets
                if (presets.isEmpty()) {
                    EmptyState(
                        title = "No presets yet",
                        subtitle = "Use the + button to add your first preset"
                    )
                } else {
                    val filtered = presets.filter { p ->
                        p.name.contains(query, ignoreCase = true) || (p.description?.contains(query, ignoreCase = true) == true)
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md)
                    ) {
                        items(filtered, key = { it.id }) { preset ->
                            PresetCard(
                                preset = preset,
                                onEdit = { editing = preset },
                                onDelete = { pendingDeletion = preset }
                            )
                        }
                    }
                }
            }
        }

        if (editing != null) {
            val draft = store.getPresetDraft(editing!!.id)
            if (draft != null) {
                EditPresetDialog(ui = ui, initial = draft, onClose = { editing = null }, store = store)
            } else {
                editing = null
            }
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
    Card(
        onClick = onEdit,
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
            val descriptionLine = preset.description
                ?.lineSequence()
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.trim()

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xxs)
            ) {
                Text(
                    preset.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                descriptionLine?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val countsText = buildString {
                    append("tools ${preset.toolsCount}")
                    append(" • prompts ${preset.promptsCount}")
                    append(" • resources ${preset.resourcesCount}")
                }
                Text(
                    countsText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(AppTheme.spacing.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Edit preset", tint = MaterialTheme.colorScheme.secondary)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete preset", tint = MaterialTheme.colorScheme.secondary)
                }
            }
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
                text = "This preset will disappear from Broxy, including the CLI shortcuts that rely on it. This action cannot be undone.",
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
