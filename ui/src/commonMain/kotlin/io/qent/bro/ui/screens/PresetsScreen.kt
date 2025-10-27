package io.qent.bro.ui.screens

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
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qent.bro.ui.adapter.models.UiToolReference as ToolReference
import io.qent.bro.ui.viewmodels.AppState
import io.qent.bro.ui.adapter.viewmodels.PresetsViewModel
import io.qent.bro.ui.viewmodels.UiPreset
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
fun PresetsScreen(state: AppState) {
    val vm = remember { PresetsViewModel() }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) {
        vm.loadIntoState(state.presets)
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search presets") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        if (state.presets.isEmpty()) {
            EmptyState(
                title = "No presets yet",
                subtitle = "Use the + button to add your first preset"
            )
        } else {
            val filtered = state.presets.filter { p ->
                p.name.contains(query, ignoreCase = true) || (p.description?.contains(query, ignoreCase = true) == true)
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(filtered, key = { it.id }) { preset ->
                    PresetCard(
                        preset = preset,
                        onEdit = { vm.openEdit(preset.id) },
                        onDuplicate = { vm.duplicatePreset(state.presets, preset.id) },
                        onExport = {
                            val json = vm.exportToJson(preset)
                            vm.openExport(preset.id, json)
                        },
                        onDelete = { vm.removePreset(state.presets, preset.id) }
                    )
                    val ui = (vm.uiStates[preset.id] ?: MutableStateFlow(io.qent.bro.ui.adapter.viewmodels.PresetUiState())).collectAsState().value
                    if (ui.showEditor) {
                        val selection = vm.getSelection(preset.id)
                        PresetEditorDialog(
                            servers = state.servers,
                            existingPresets = state.presets,
                            initial = preset,
                            initialSelection = selection,
                            onSave = { newUi, tools -> vm.upsertPreset(state.presets, newUi, tools) },
                            onDismiss = { vm.closeEdit(preset.id) }
                        )
                    }
                    val exportJson = ui.exportJson
                    if (exportJson != null) {
                        ExportPresetDialog(json = exportJson) { vm.closeExport(preset.id) }
                    }
                }
            }
        }
    }

    // Create new preset flow via global toggle in MainWindow
    if (state.showAddPresetDialog.value) {
        PresetEditorDialog(
            servers = state.servers,
            existingPresets = state.presets,
            initial = null,
            initialSelection = emptyList(),
            onSave = { ui, tools -> vm.upsertPreset(state.presets, ui, tools) },
            onDismiss = { state.showAddPresetDialog.value = false }
        )
    }
}

@Composable
private fun PresetCard(
    preset: UiPreset,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(preset.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    if (!preset.description.isNullOrBlank()) {
                        Text(preset.description ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text("Tools: ${preset.toolsCount}", style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onEdit) {
                    Icon(Icons.Outlined.Edit, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Edit")
                }
                TextButton(onClick = onDuplicate) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Duplicate")
                }
                TextButton(onClick = onExport) {
                    Icon(Icons.Outlined.IosShare, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Export")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Delete") }
            }
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
        Spacer(Modifier.padding(6.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
