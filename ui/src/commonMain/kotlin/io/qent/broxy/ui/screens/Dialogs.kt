package io.qent.broxy.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import io.qent.broxy.ui.adapter.models.UiPromptRef
import io.qent.broxy.ui.adapter.models.UiResourceRef
import io.qent.broxy.ui.adapter.services.validateServerConnection
import io.qent.broxy.ui.adapter.store.AppStore
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.components.AppDialog
import io.qent.broxy.ui.components.AppPrimaryButton
import io.qent.broxy.ui.components.AppSecondaryButton
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
            AppSecondaryButton(onClick = { state.showAddServerDialog.value = false }) { Text("Cancel") }
        },
        confirmButton = {
            AppPrimaryButton(onClick = {
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
    val selectedPrompts = remember { mutableStateOf<List<UiPromptRef>>(emptyList()) }
    val selectedResources = remember { mutableStateOf<List<UiResourceRef>>(emptyList()) }
    val promptsConfigured = remember { mutableStateOf(true) }
    val resourcesConfigured = remember { mutableStateOf(true) }

    AppDialog(
        title = "Add preset",
        onDismissRequest = { state.showAddPresetDialog.value = false },
        dismissButton = {
            AppSecondaryButton(onClick = { state.showAddPresetDialog.value = false }) { Text("Cancel") }
        },
        confirmButton = {
            AppPrimaryButton(onClick = {
                if (name.value.text.isNotBlank() && ui is UIState.Ready) {
                    val draft = UiPresetDraft(
                        id = name.value.text.trim().lowercase().replace(" ", "-"),
                        name = name.value.text.trim(),
                        description = description.value.text.ifBlank { null },
                        tools = selectedTools.value,
                        prompts = selectedPrompts.value,
                        resources = selectedResources.value,
                        promptsConfigured = promptsConfigured.value,
                        resourcesConfigured = resourcesConfigured.value
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
            promptsConfigured = promptsConfigured.value,
            resourcesConfigured = resourcesConfigured.value,
            onSelectionChanged = { tools, prompts, resources ->
                selectedTools.value = tools
                selectedPrompts.value = prompts
                selectedResources.value = resources
            },
            onPromptsConfiguredChange = { promptsConfigured.value = it },
            onResourcesConfiguredChange = { resourcesConfigured.value = it }
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
        dismissButton = { AppSecondaryButton(onClick = onClose) { Text("Cancel") } },
        confirmButton = {
            AppPrimaryButton(onClick = {
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
    val selectedPrompts = remember { mutableStateOf<List<UiPromptRef>>(initial.prompts) }
    val selectedResources = remember { mutableStateOf<List<UiResourceRef>>(initial.resources) }
    val promptsConfigured = remember { mutableStateOf(initial.promptsConfigured) }
    val resourcesConfigured = remember { mutableStateOf(initial.resourcesConfigured) }

    AppDialog(
        title = "Edit preset",
        onDismissRequest = onClose,
        dismissButton = { AppSecondaryButton(onClick = onClose) { Text("Cancel") } },
        confirmButton = {
            AppPrimaryButton(onClick = {
                if (ui is UIState.Ready) {
                    val draft = UiPresetDraft(
                        id = id.value.text.trim(),
                        name = name.value.text.trim(),
                        description = description.value.text.ifBlank { null },
                        tools = selectedTools.value,
                        prompts = selectedPrompts.value,
                        resources = selectedResources.value,
                        promptsConfigured = promptsConfigured.value,
                        resourcesConfigured = resourcesConfigured.value
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
            initialPromptRefs = initial.prompts,
            initialResourceRefs = initial.resources,
            promptsConfigured = promptsConfigured.value,
            resourcesConfigured = resourcesConfigured.value,
            onSelectionChanged = { tools, prompts, resources ->
                selectedTools.value = tools
                selectedPrompts.value = prompts
                selectedResources.value = resources
            },
            onPromptsConfiguredChange = { promptsConfigured.value = it },
            onResourcesConfiguredChange = { resourcesConfigured.value = it }
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
        confirmButton = { AppSecondaryButton(onClick = onClose) { Text("Close") } }
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
                snapshot.tools.forEachIndexed { index, tool ->
                    CapabilityEntry(
                        title = tool.name,
                        description = tool.description?.takeIf { it.isNotBlank() } ?: "No description provided",
                        arguments = tool.arguments,
                        showDivider = index < snapshot.tools.lastIndex
                    )
                }
            }
        }
        SectionBlock("Resources") {
            if (snapshot.resources.isEmpty()) {
                SectionEmptyMessage("No resources available")
            } else {
                snapshot.resources.forEachIndexed { index, resource ->
                    CapabilityEntry(
                        title = resource.name,
                        description = resource.description?.takeIf { it.isNotBlank() } ?: resource.key,
                        arguments = resource.arguments,
                        showDivider = index < snapshot.resources.lastIndex
                    )
                }
            }
        }
        SectionBlock("Prompts") {
            if (snapshot.prompts.isEmpty()) {
                SectionEmptyMessage("No prompts available")
            } else {
                snapshot.prompts.forEachIndexed { index, prompt ->
                    CapabilityEntry(
                        title = prompt.name,
                        description = prompt.description?.takeIf { it.isNotBlank() } ?: "No description provided",
                        arguments = prompt.arguments,
                        showDivider = index < snapshot.prompts.lastIndex
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppTheme.shapes.surfaceSm,
        tonalElevation = AppTheme.elevation.level1,
        border = BorderStroke(
            width = AppTheme.strokeWidths.hairline,
            color = MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.sm),
                style = MaterialTheme.typography.labelLarge
            )
            SectionDivider()
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.none),
                content = content
            )
        }
    }
}

@Composable
private fun SectionEmptyMessage(message: String) {
    Text(
        message,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.sm),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun CapabilityEntry(
    title: String,
    description: String,
    arguments: List<UiCapabilityArgument> = emptyList(),
    showDivider: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.sm),
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
    if (showDivider) {
        SectionDivider()
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = AppTheme.spacing.md),
        thickness = AppTheme.strokeWidths.hairline,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

private sealed interface ServerDetailsState {
    object Loading : ServerDetailsState
    data class Ready(val snapshot: UiServerCapsSnapshot) : ServerDetailsState
    data class Error(val message: String) : ServerDetailsState
}
