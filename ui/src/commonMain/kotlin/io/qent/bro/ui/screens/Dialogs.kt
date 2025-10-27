package io.qent.bro.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.qent.bro.ui.adapter.models.UiPreset
import io.qent.bro.ui.adapter.store.UIState
import io.qent.bro.ui.viewmodels.AppState
import io.qent.bro.ui.adapter.models.UiServer
import io.qent.bro.ui.adapter.models.UiServerDraft
import io.qent.bro.ui.adapter.models.UiStdioDraft
import io.qent.bro.ui.adapter.models.UiHttpDraft
import io.qent.bro.ui.adapter.models.UiWebSocketDraft
import io.qent.bro.ui.adapter.models.UiPresetDraft
import io.qent.bro.ui.adapter.models.UiToolRef

@Composable
fun AddServerDialog(ui: UIState, state: AppState, notify: (String) -> Unit) {
    val name = remember { mutableStateOf(TextFieldValue("")) }
    val id = remember { mutableStateOf(TextFieldValue("")) }

    AlertDialog(
        onDismissRequest = { state.showAddServerDialog.value = false },
        title = { Text("Add server") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name.value, onValueChange = { name.value = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = id.value, onValueChange = { id.value = it }, label = { Text("ID") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = {
                val n = name.value.text.trim()
                val i = id.value.text.trim()
                if (n.isNotBlank() && i.isNotBlank() && ui is UIState.Ready) {
                    ui.intents.addServerBasic(i, n)
                    state.showAddServerDialog.value = false
                    notify("Saved ${'$'}n")
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
    val name = remember { mutableStateOf(TextFieldValue(initial.name)) }
    val id = remember { mutableStateOf(TextFieldValue(initial.id)) }
    val enabled = remember { mutableStateOf(initial.enabled) }
    val transportType = remember { mutableStateOf(
        when (initial.transport) {
            is UiStdioDraft -> "STDIO"
            is UiHttpDraft -> "HTTP"
            is UiWebSocketDraft -> "WS"
        }
    ) }
    val command = remember { mutableStateOf(TextFieldValue((initial.transport as? UiStdioDraft)?.command ?: "")) }
    val args = remember { mutableStateOf(TextFieldValue((initial.transport as? UiStdioDraft)?.args?.joinToString(",") ?: "")) }
    val url = remember { mutableStateOf(TextFieldValue((initial.transport as? UiHttpDraft)?.url ?: (initial.transport as? UiWebSocketDraft)?.url ?: "")) }
    val headers = remember { mutableStateOf(TextFieldValue((initial.transport as? UiHttpDraft)?.headers?.entries?.joinToString("\n") { (k, v) -> "$k:$v" } ?: "")) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Edit server") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name.value, onValueChange = { name.value = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = id.value, onValueChange = { id.value = it }, label = { Text("ID") }, modifier = Modifier.fillMaxWidth())
                Row { Text("Enabled"); Spacer(modifier = Modifier.padding(4.dp)); Switch(checked = enabled.value, onCheckedChange = { enabled.value = it }) }
                Text("Transport")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("STDIO", "HTTP", "WS").forEach { label ->
                        TextButton(onClick = { transportType.value = label }) { Text(if (transportType.value == label) "[$label]" else label) }
                    }
                }
                when (transportType.value) {
                    "STDIO" -> {
                        OutlinedTextField(value = command.value, onValueChange = { command.value = it }, label = { Text("Command") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = args.value, onValueChange = { args.value = it }, label = { Text("Args (comma-separated)") }, modifier = Modifier.fillMaxWidth())
                    }
                    "HTTP" -> {
                        OutlinedTextField(value = url.value, onValueChange = { url.value = it }, label = { Text("HTTP URL") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = headers.value, onValueChange = { headers.value = it }, label = { Text("Headers (key:value per line)") }, modifier = Modifier.fillMaxWidth())
                    }
                    else -> {
                        OutlinedTextField(value = url.value, onValueChange = { url.value = it }, label = { Text("WebSocket URL") }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (ui is UIState.Ready) {
                    val draftTransport = when (transportType.value) {
                        "STDIO" -> UiStdioDraft(command = command.value.text.trim(), args = args.value.text.split(',').mapNotNull { it.trim().takeIf { it.isNotEmpty() } })
                        "HTTP" -> UiHttpDraft(url = url.value.text.trim(), headers = headers.value.text.lines().mapNotNull { line ->
                            val idx = line.indexOf(':'); if (idx <= 0) null else (line.substring(0, idx).trim() to line.substring(idx + 1).trim())
                        }.toMap())
                        else -> UiWebSocketDraft(url = url.value.text.trim())
                    }
                    val draft = UiServerDraft(
                        id = id.value.text.trim(),
                        name = name.value.text.trim(),
                        enabled = enabled.value,
                        transport = draftTransport
                    )
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
