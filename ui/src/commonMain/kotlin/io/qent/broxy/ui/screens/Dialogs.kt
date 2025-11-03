package io.qent.broxy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import io.qent.broxy.ui.adapter.models.UiCapabilityArgument
import io.qent.broxy.ui.adapter.models.UiPresetDraft
import io.qent.broxy.ui.adapter.models.UiServer
import io.qent.broxy.ui.adapter.models.UiServerCapsSnapshot
import io.qent.broxy.ui.adapter.models.UiServerDraft
import io.qent.broxy.ui.adapter.models.UiToolRef
import io.qent.broxy.ui.adapter.services.validateServerConnection
import io.qent.broxy.ui.adapter.store.AppStore
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.components.AppDialog
import io.qent.broxy.ui.components.CapabilityArgumentList
import io.qent.broxy.ui.components.PresetSelector
import io.qent.broxy.ui.components.ServerForm
import io.qent.broxy.ui.components.ServerFormState
import io.qent.broxy.ui.components.ServerFormStateFactory
import io.qent.broxy.ui.components.toDraft
import io.qent.broxy.ui.theme.AppTheme
import io.qent.broxy.ui.viewmodels.AppState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun AddServerDialog(ui: UIState, state: AppState, notify: (String) -> Unit) {
    val form = remember { mutableStateOf(ServerFormState()) }
    val scope = rememberCoroutineScope()

    AppDialog(
        title = "Add server",
        onDismissRequest = { state.showAddServerDialog.value = false },
        dismissButton = {
            TextButton(onClick = { state.showAddServerDialog.value = false }) { Text("Cancel") }
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
        }
    ) {
        ServerForm(
            state = form.value,
            onStateChange = { form.value = it }
        )
    }
}

@Composable
fun AddPresetDialog(ui: UIState, state: AppState, store: AppStore) {
    val name = remember { mutableStateOf(TextFieldValue("")) }
    val description = remember { mutableStateOf(TextFieldValue("")) }
    val selectedTools = remember { mutableStateOf<List<UiToolRef>>(emptyList()) }

    AppDialog(
        title = "Add preset",
        onDismissRequest = { state.showAddPresetDialog.value = false },
        dismissButton = {
            TextButton(onClick = { state.showAddPresetDialog.value = false }) { Text("Cancel") }
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
        }
    ) {
        OutlinedTextField(
            value = name.value,
            onValueChange = { name.value = it },
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = description.value,
            onValueChange = { description.value = it },
            label = { Text("Description (optional)") },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Select tools/prompts/resources from connected servers",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        PresetSelector(
            store = store,
            onToolsChanged = { selectedTools.value = it }
        )
    }
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

    AppDialog(
        title = "Edit server",
        onDismissRequest = onClose,
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
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
        }
    ) {
        ServerForm(
            state = form.value,
            onStateChange = { form.value = it }
        )
    }
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

    AppDialog(
        title = "Edit preset",
        onDismissRequest = onClose,
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
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
        }
    ) {
        OutlinedTextField(
            value = name.value,
            onValueChange = { name.value = it },
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = id.value,
            onValueChange = { id.value = it },
            label = { Text("ID") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = description.value,
            onValueChange = { description.value = it },
            label = { Text("Description") },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Select tools/prompts/resources from connected servers",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        PresetSelector(
            store = store,
            initialToolRefs = initial.tools,
            onToolsChanged = { selectedTools.value = it }
        )
    }
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

    AppDialog(
        title = "Server details",
        onDismissRequest = onClose,
        dismissButton = null,
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    ) {
        when (val state = loadState) {
            ServerDetailsState.Loading -> Text("Loading capabilities…")
            is ServerDetailsState.Error -> Text("Failed to load capabilities: ${state.message}")
            is ServerDetailsState.Ready -> ServerDetailsContent(snapshot = state.snapshot)
        }
    }
}

@Composable
private fun ServerDetailsContent(
    snapshot: UiServerCapsSnapshot
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
            Text(snapshot.name, style = MaterialTheme.typography.titleMedium)
            Text(
                "ID: ${snapshot.serverId}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SectionBlock("Tools") {
            if (snapshot.tools.isEmpty()) {
                SectionEmptyMessage("No tools available")
            } else {
                snapshot.tools.forEach { tool ->
                    CapabilityEntry(
                        title = tool.name,
                        description = tool.description?.takeIf { it.isNotBlank() } ?: "No description provided",
                        arguments = tool.arguments
                    )
                }
            }
        }
        SectionBlock("Resources") {
            if (snapshot.resources.isEmpty()) {
                SectionEmptyMessage("No resources available")
            } else {
                snapshot.resources.forEach { resource ->
                    CapabilityEntry(
                        title = resource.name,
                        description = resource.description?.takeIf { it.isNotBlank() } ?: resource.key,
                        arguments = resource.arguments
                    )
                }
            }
        }
        SectionBlock("Prompts") {
            if (snapshot.prompts.isEmpty()) {
                SectionEmptyMessage("No prompts available")
            } else {
                snapshot.prompts.forEach { prompt ->
                    CapabilityEntry(
                        title = prompt.name,
                        description = prompt.description?.takeIf { it.isNotBlank() } ?: "No description provided",
                        arguments = prompt.arguments
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionBlock(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)
    ) {
        SectionHeader(title)
        content()
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
        modifier = Modifier.padding(start = AppTheme.spacing.sm),
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
            .padding(start = AppTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        if (arguments.isNotEmpty()) {
            CapabilityArgumentList(
                arguments = arguments,
                modifier = Modifier.padding(top = AppTheme.spacing.xs)
            )
        }
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
