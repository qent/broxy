package io.qent.bro.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.qent.bro.ui.adapter.models.UiMcpServerConfig as McpServerConfig
import io.qent.bro.ui.adapter.models.UiToolReference as ToolReference
import io.qent.bro.ui.components.ToolSelector
import io.qent.bro.ui.viewmodels.UiPreset
import io.qent.bro.ui.adapter.viewmodels.slugify

@Composable
fun PresetEditorDialog(
    servers: List<McpServerConfig>,
    existingPresets: List<UiPreset>,
    initial: UiPreset? = null,
    initialSelection: List<ToolReference> = emptyList(),
    onSave: (UiPreset, List<ToolReference>) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(TextFieldValue(initial?.name ?: "")) }
    var description by remember { mutableStateOf(TextFieldValue(initial?.description ?: "")) }
    var selected by remember { mutableStateOf(initialSelection) }
    var error by remember { mutableStateOf<String?>(null) }
    var previewTitle by remember { mutableStateOf<String?>(null) }
    var previewText by remember { mutableStateOf<String?>(null) }

    fun validate(): Boolean {
        val id = slugify(name.text)
        if (id.isBlank()) {
            error = "Name cannot be blank"
            return false
        }
        val exists = existingPresets.any { it.id == id && it.id != initial?.id }
        if (exists) {
            error = "Preset ID already exists: $id"
            return false
        }
        error = null
        return true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Create Preset" else "Edit Preset") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (Markdown)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4
                )
                error?.let { Text(it, color = androidx.compose.ui.graphics.Color(0xFFD64545)) }

                Text("Tools", style = MaterialTheme.typography.titleSmall)
                ToolSelector(
                    servers = servers,
                    initialSelection = selected,
                    onSelectionChanged = { sel -> selected = sel },
                    onPreview = { _, tool, desc ->
                        previewTitle = tool
                        previewText = desc
                    }
                )
                if (!previewTitle.isNullOrBlank() || !previewText.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(previewTitle ?: "Tool", style = MaterialTheme.typography.titleSmall)
                    if (!previewText.isNullOrBlank()) {
                        Text(previewText!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (!validate()) return@Button
                val id = slugify(name.text)
                val ui = UiPreset(id = id, name = name.text.trim(), description = description.text.ifBlank { null }, toolsCount = selected.count { it.enabled })
                onSave(ui, selected)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
