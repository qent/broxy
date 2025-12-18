package io.qent.broxy.ui.screens

import AppPrimaryButton
import AppSecondaryButton
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.UiCapabilityArgument
import io.qent.broxy.ui.adapter.models.UiServer
import io.qent.broxy.ui.adapter.models.UiServerCapsSnapshot
import io.qent.broxy.ui.adapter.models.UiServerDraft
import io.qent.broxy.ui.adapter.services.validateServerConnection
import io.qent.broxy.ui.adapter.store.AppStore
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.components.*
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
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = AppTheme.shapes.item
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
