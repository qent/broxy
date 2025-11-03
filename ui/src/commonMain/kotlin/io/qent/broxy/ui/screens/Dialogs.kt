package io.qent.broxy.ui.screens

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.adapter.store.AppStore
import io.qent.broxy.ui.viewmodels.AppState
import io.qent.broxy.ui.adapter.models.UiServerDraft
import io.qent.broxy.ui.adapter.models.UiPresetDraft
import io.qent.broxy.ui.adapter.models.UiCapabilityArgument
import io.qent.broxy.ui.adapter.models.UiToolRef
import io.qent.broxy.ui.adapter.models.UiServer
import io.qent.broxy.ui.adapter.models.UiServerCapsSnapshot
import io.qent.broxy.ui.components.CapabilityArgumentList
import io.qent.broxy.ui.components.ServerForm
import io.qent.broxy.ui.components.ServerFormState
import io.qent.broxy.ui.components.ServerFormStateFactory
import io.qent.broxy.ui.components.toDraft
import io.qent.broxy.ui.components.PresetSelector
import io.qent.broxy.ui.adapter.services.validateServerConnection
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

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

@Composable
fun ServerDetailsDialog(
    server: UiServer,
    store: AppStore,
    onClose: () -> Unit
) {
    val loadState by produceState<ServerDetailsState>(
        initialValue = ServerDetailsState.Loading,
        key1 = server.id
    ) {
        value = try {
            val snapshot = store.getServerCaps(server.id)
            if (snapshot != null) {
                ServerDetailsState.Ready(snapshot)
            } else {
                ServerDetailsState.Error("Capabilities unavailable")
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            ServerDetailsState.Error(t.message ?: "Failed to load capabilities")
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Server details") },
        text = {
            when (val state = loadState) {
                ServerDetailsState.Loading -> Text("Loading capabilities…")
                is ServerDetailsState.Error -> Text("Failed to load capabilities: ${state.message}")
                is ServerDetailsState.Ready -> ServerDetailsContent(snapshot = state.snapshot)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

@Composable
private fun ServerDetailsContent(
    snapshot: UiServerCapsSnapshot
) {
    val listState = rememberLazyListState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(min = 360.dp)
            .heightIn(max = 420.dp)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(snapshot.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "ID: ${snapshot.serverId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item { SectionHeader("Tools") }
            if (snapshot.tools.isEmpty()) {
                item { SectionEmptyMessage("No tools available") }
            } else {
                items(snapshot.tools) { tool ->
                    CapabilityEntry(
                        title = tool.name,
                        description = tool.description?.takeIf { it.isNotBlank() } ?: "No description provided",
                        arguments = tool.arguments
                    )
                }
            }
            item { SectionHeader("Resources") }
            if (snapshot.resources.isEmpty()) {
                item { SectionEmptyMessage("No resources available") }
            } else {
                items(snapshot.resources) { resource ->
                    CapabilityEntry(
                        title = resource.name,
                        description = resource.description?.takeIf { it.isNotBlank() } ?: resource.key,
                        arguments = resource.arguments
                    )
                }
            }
            item { SectionHeader("Prompts") }
            if (snapshot.prompts.isEmpty()) {
                item { SectionEmptyMessage("No prompts available") }
            } else {
                items(snapshot.prompts) { prompt ->
                    CapabilityEntry(
                        title = prompt.name,
                        description = prompt.description?.takeIf { it.isNotBlank() } ?: "No description provided",
                        arguments = prompt.arguments
                    )
                }
            }
            item { Spacer(Modifier.height(4.dp)) }
        }

        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(12.dp)
                .padding(end = 2.dp)
                .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
        )
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(12.dp)
                .padding(end = 2.dp)
        )
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        "$label:",
        style = MaterialTheme.typography.titleSmall
    )
}

@Composable
private fun SectionEmptyMessage(message: String) {
    Text(
        message,
        modifier = Modifier.padding(start = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun CapabilityEntry(
    title: String,
    description: String,
    arguments: List<UiCapabilityArgument> = emptyList()
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        CapabilityArgumentList(
            arguments = arguments,
            modifier = Modifier.padding(top = 2.dp)
        )
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private sealed interface ServerDetailsState {
    object Loading : ServerDetailsState
    data class Ready(val snapshot: UiServerCapsSnapshot) : ServerDetailsState
    data class Error(val message: String) : ServerDetailsState
}
