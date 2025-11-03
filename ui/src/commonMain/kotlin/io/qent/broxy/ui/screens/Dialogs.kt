package io.qent.broxy.ui.screens

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
import io.qent.broxy.ui.adapter.models.UiPreset
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.adapter.store.AppStore
import io.qent.broxy.ui.viewmodels.AppState
import io.qent.broxy.ui.adapter.models.UiServerDraft
import io.qent.broxy.ui.adapter.models.UiPresetDraft
import io.qent.broxy.ui.adapter.models.UiToolRef
import io.qent.broxy.ui.components.ServerForm
import io.qent.broxy.ui.components.ServerFormState
import io.qent.broxy.ui.components.ServerFormStateFactory
import io.qent.broxy.ui.components.toDraft
import io.qent.broxy.ui.components.PresetSelector
import io.qent.broxy.ui.adapter.services.validateServerConnection
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

@Composable
fun AddServerDialog(ui: UIState, state: AppState, notify: (String) -> Unit) {
    val form = remember { mutableStateOf(ServerFormState()) }
    val scope = rememberCoroutineScope()

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
                    scope.launch {
                        var toSave = draft
                        if (draft.enabled) {
                            val result = validateServerConnection(draft)
                            if (result.isFailure) {
                                val e = result.exceptionOrNull()
                                val isTimeout = e?.message?.contains("timed out", ignoreCase = true) == true
                                if (isTimeout) {
                                    notify("Connection timed out. Saved as disabled.")
                                } else {
                                val errMsg = e?.message?.takeIf { it.isNotBlank() }
                                val details = errMsg?.let { ": $it" } ?: ""
                                notify("Connection failed$details. Saved as disabled.")
                                }
                                toSave = draft.copy(enabled = false)
                            }
                        }
                        ui.intents.upsertServer(toSave)
                        state.showAddServerDialog.value = false
                        notify("Saved ${toSave.name}")
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = { state.showAddServerDialog.value = false }) { Text("Cancel") }
        }
    )
}

@Composable
fun AddPresetDialog(ui: UIState, state: AppState, store: AppStore) {
    val name = remember { mutableStateOf(TextFieldValue("")) }
    val description = remember { mutableStateOf(TextFieldValue("")) }
    val selectedTools = remember { mutableStateOf<List<UiToolRef>>(emptyList()) }

    AlertDialog(
        onDismissRequest = { state.showAddPresetDialog.value = false },
        title = { Text("Add preset") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name.value, onValueChange = { name.value = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = description.value, onValueChange = { description.value = it }, label = { Text("Description (optional)") }, modifier = Modifier.fillMaxWidth())
                Text("Select tools/prompts/resources from connected servers", modifier = Modifier.padding(top = 4.dp))
                PresetSelector(store = store, onToolsChanged = { selectedTools.value = it })
            }
        },
        confirmButton = {
            Button(onClick = {
                if (name.value.text.isNotBlank() && ui is UIState.Ready) {
                    val draft = UiPresetDraft(
                        id = name.value.text.trim().lowercase().replace(" ", "-"),
                        name = name.value.text.trim(),
                        description = description.value.text.ifBlank { null },
                        tools = selectedTools.value
                    )
                    ui.intents.upsertPreset(draft)
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
    val scope = rememberCoroutineScope()

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
                    scope.launch {
                        var toSave = draft
                        if (draft.enabled) {
                            val result = validateServerConnection(draft)
                            if (result.isFailure) {
                                val e = result.exceptionOrNull()
                                val isTimeout = e?.message?.contains("timed out", ignoreCase = true) == true
                                if (isTimeout) {
                                    notify("Connection timed out. Saved as disabled.")
                                } else {
                                val errMsg = e?.message?.takeIf { it.isNotBlank() }
                                val details = errMsg?.let { ": $it" } ?: ""
                                notify("Connection failed$details. Saved as disabled.")
                                }
                                toSave = draft.copy(enabled = false)
                            }
                        }
                        ui.intents.upsertServer(toSave)
                        onClose()
                        notify("Saved ${toSave.name}")
                    }
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
    onClose: () -> Unit,
    store: AppStore
) {
    val name = remember { mutableStateOf(TextFieldValue(initial.name)) }
    val id = remember { mutableStateOf(TextFieldValue(initial.id)) }
    val description = remember { mutableStateOf(TextFieldValue(initial.description ?: "")) }
    val selectedTools = remember { mutableStateOf<List<UiToolRef>>(initial.tools) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Edit preset") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name.value, onValueChange = { name.value = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = id.value, onValueChange = { id.value = it }, label = { Text("ID") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = description.value, onValueChange = { description.value = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
                Text("Select tools/prompts/resources from connected servers", modifier = Modifier.padding(top = 4.dp))
                PresetSelector(store = store, initialToolRefs = initial.tools, onToolsChanged = { selectedTools.value = it })
            }
        },
        confirmButton = {
            Button(onClick = {
                if (ui is UIState.Ready) {
                    val draft = UiPresetDraft(
                        id = id.value.text.trim(),
                        name = name.value.text.trim(),
                        description = description.value.text.ifBlank { null },
                        tools = selectedTools.value
                    )
                    ui.intents.upsertPreset(draft)
                    onClose()
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } }
    )
}
