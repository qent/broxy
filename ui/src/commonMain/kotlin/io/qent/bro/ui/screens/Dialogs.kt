package io.qent.bro.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.qent.bro.ui.adapter.models.UiPreset
import io.qent.bro.ui.adapter.store.UIState
import io.qent.bro.ui.viewmodels.AppState
import io.qent.bro.ui.adapter.models.UiServerDraft
import io.qent.bro.ui.adapter.models.UiPresetDraft
import io.qent.bro.ui.adapter.models.UiToolRef
import io.qent.bro.ui.components.ServerForm
import io.qent.bro.ui.components.ServerFormState
import io.qent.bro.ui.components.ServerFormStateFactory
import io.qent.bro.ui.components.toDraft

@Composable
fun AddServerDialog(ui: UIState, state: AppState, notify: (String) -> Unit) {
    val form = remember { mutableStateOf(ServerFormState()) }

    AlertDialog(
        onDismissRequest = { state.showAddServerDialog.value = false },
        title = { Text("Add server") },
        text = {
            ServerForm(state = form.value, onStateChange = { form.value = it })
        },
        confirmButton = {
            Button(onClick = {
                if (ui is UIState.Ready) {
                    val draft: UiServerDraft = form.value.toDraft()
                    ui.intents.upsertServer(draft)
                    state.showAddServerDialog.value = false
                    notify("Saved ${'$'}{draft.name}")
                }
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = { state.showAddServerDialog.value = false }) { Text("Cancel") }
        }
    )
}

@Composable
fun AddPresetDialog(ui: UIState, state: AppState) {
    val name = remember { mutableStateOf(TextFieldValue("")) }
    val description = remember { mutableStateOf(TextFieldValue("")) }

    AlertDialog(
        onDismissRequest = { state.showAddPresetDialog.value = false },
        title = { Text("Add preset") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(value = name.value, onValueChange = { name.value = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = description.value, onValueChange = { description.value = it }, label = { Text("Description (optional)") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = {
            Button(onClick = {
                if (name.value.text.isNotBlank() && ui is UIState.Ready) {
                    ui.intents.addOrUpdatePreset(
                        UiPreset(
                            id = name.value.text.trim().lowercase().replace(" ", "-"),
                            name = name.value.text.trim(),
                            description = description.value.text.ifBlank { null },
                            toolsCount = 0
                        )
                    )
                    state.showAddPresetDialog.value = false
                }
            }) { Text("Add") }
        },
        dismissButton = {
            Button(onClick = { state.showAddPresetDialog.value = false }) { Text("Cancel") }
        }
    )
}

@Composable
fun EditServerDialog(
    initial: UiServerDraft,
    ui: UIState,
    onClose: () -> Unit,
    notify: (String) -> Unit = {}
) {
    val form = remember { mutableStateOf(ServerFormStateFactory.from(initial)) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Edit server") },
        text = {
            ServerForm(state = form.value, onStateChange = { form.value = it })
        },
        confirmButton = {
            Button(onClick = {
                if (ui is UIState.Ready) {
                    val draft = form.value.toDraft()
                    ui.intents.upsertServer(draft)
                    onClose()
                    notify("Saved ${'$'}{draft.name}")
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } }
    )
}

@Composable
fun EditPresetDialog(
    ui: UIState,
    initial: UiPresetDraft,
    onClose: () -> Unit
) {
    val name = remember { mutableStateOf(TextFieldValue(initial.name)) }
    val id = remember { mutableStateOf(TextFieldValue(initial.id)) }
    val description = remember { mutableStateOf(TextFieldValue(initial.description ?: "")) }
    val toolsText = remember { mutableStateOf(TextFieldValue(initial.tools.joinToString("\n") { t -> "${'$'}{t.serverId}:${'$'}{t.toolName}" })) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Edit preset") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name.value, onValueChange = { name.value = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = id.value, onValueChange = { id.value = it }, label = { Text("ID") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = description.value, onValueChange = { description.value = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = toolsText.value, onValueChange = { toolsText.value = it }, label = { Text("Tools (serverId:tool per line)") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
            }
        },
        confirmButton = {
            Button(onClick = {
                if (ui is UIState.Ready) {
                    val tools: List<UiToolRef> = toolsText.value.text.lines().mapNotNull { line ->
                        val idx = line.indexOf(':')
                        if (idx <= 0) null else UiToolRef(serverId = line.substring(0, idx).trim(), toolName = line.substring(idx + 1).trim(), enabled = true)
                    }
                    val draft = UiPresetDraft(
                        id = id.value.text.trim(),
                        name = name.value.text.trim(),
                        description = description.value.text.ifBlank { null },
                        tools = tools
                    )
                    ui.intents.upsertPreset(draft)
                    onClose()
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } }
    )
}
