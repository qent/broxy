package io.qent.bro.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.qent.bro.core.mcp.DefaultMcpServerConnection
import io.qent.bro.core.mcp.ServerCapabilities
import io.qent.bro.core.models.McpServerConfig
import io.qent.bro.core.models.TransportConfig
import io.qent.bro.ui.viewmodels.AppState
import io.qent.bro.ui.viewmodels.UiPreset
import kotlinx.coroutines.launch

@Composable
fun AddServerDialog(state: AppState) {
    var name by remember { mutableStateOf(TextFieldValue("")) }
    var id by remember { mutableStateOf(TextFieldValue("")) }
    var transportType by remember { mutableStateOf("STDIO") }
    var expanded by remember { mutableStateOf(false) }
    var command by remember { mutableStateOf(TextFieldValue("")) }
    var args by remember { mutableStateOf(TextFieldValue("")) }
    var url by remember { mutableStateOf(TextFieldValue("")) }
    var headers by remember { mutableStateOf(TextFieldValue("")) } // key:value per line
    var testing by remember { mutableStateOf(false) }
    var lastTest: ServerCapabilities? by remember { mutableStateOf(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun buildConfig(): McpServerConfig? {
        val t: TransportConfig = when (transportType) {
            "STDIO" -> {
                val cmd = command.text.trim()
                if (cmd.isBlank()) return null
                val parts = args.text.split(',').mapNotNull { it.trim().takeIf { it.isNotEmpty() } }
                TransportConfig.StdioTransport(command = cmd, args = parts)
            }
            "HTTP" -> {
                val u = url.text.trim()
                if (u.isBlank()) return null
                val hdrs = headers.text.lines().mapNotNull { line ->
                    val idx = line.indexOf(':')
                    if (idx <= 0) null else (line.substring(0, idx).trim() to line.substring(idx + 1).trim())
                }.toMap()
                TransportConfig.HttpTransport(url = u, headers = hdrs)
            }
            else -> { // WebSocket
                val u = url.text.trim()
                if (u.isBlank()) return null
                TransportConfig.WebSocketTransport(url = u)
            }
        }
        val n = name.text.trim()
        val i = id.text.trim()
        if (n.isBlank() || i.isBlank()) return null
        return McpServerConfig(id = i, name = n, transport = t, enabled = true)
    }

    AlertDialog(
        onDismissRequest = { state.showAddServerDialog.value = false },
        title = { Text("Add server") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = id, onValueChange = { id = it }, label = { Text("ID") }, modifier = Modifier.fillMaxWidth())

                Text("Transport")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("STDIO", "HTTP", "WebSocket").forEach { opt ->
                        TextButton(onClick = { transportType = opt }) {
                            Text(if (transportType == opt) "[$opt]" else opt)
                        }
                    }
                }

                when (transportType) {
                    "STDIO" -> {
                        OutlinedTextField(value = command, onValueChange = { command = it }, label = { Text("Command") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = args, onValueChange = { args = it }, label = { Text("Args (comma-separated)") }, modifier = Modifier.fillMaxWidth())
                    }
                    "HTTP" -> {
                        OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("HTTP URL") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = headers, onValueChange = { headers = it }, label = { Text("Headers (key:value per line)") }, modifier = Modifier.fillMaxWidth())
                    }
                    else -> {
                        OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("WebSocket URL") }, modifier = Modifier.fillMaxWidth())
                    }
                }

                errorText?.let { Text(it, color = androidx.compose.ui.graphics.Color(0xFFD64545)) }
                lastTest?.let { caps ->
                    Text("Test OK. Tools: ${caps.tools.size}")
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val cfg = buildConfig()
                    if (cfg == null) {
                        errorText = "Please fill all required fields"
                        return@Button
                    }
                    scope.launch {
                        testing = true
                        errorText = null
                        lastTest = null
                        val conn = DefaultMcpServerConnection(cfg)
                        val r1 = conn.connect()
                        if (r1.isSuccess) {
                            val caps = conn.getCapabilities(forceRefresh = true)
                            if (caps.isSuccess) {
                                lastTest = caps.getOrNull()
                            } else {
                                errorText = caps.exceptionOrNull()?.message ?: "Failed to fetch capabilities"
                            }
                        } else {
                            errorText = r1.exceptionOrNull()?.message ?: "Failed to connect"
                        }
                        testing = false
                        conn.disconnect()
                    }
                }, enabled = !testing) {
                    if (testing) CircularProgressIndicator()
                    Text("Test Connection")
                }

                Button(onClick = {
                    val cfg = buildConfig()
                    if (cfg != null && lastTest != null) {
                        state.servers.add(cfg)
                        state.showAddServerDialog.value = false
                    } else {
                        errorText = "Please test connection before saving"
                    }
                }, enabled = !testing) { Text("Save") }
            }
        },
        dismissButton = {
            TextButton(onClick = { state.showAddServerDialog.value = false }) { Text("Cancel") }
        }
    )
}

@Composable
fun EditServerDialog(
    initial: McpServerConfig,
    onSave: (McpServerConfig) -> Unit,
    onClose: () -> Unit
) {
    var name by remember { mutableStateOf(TextFieldValue(initial.name)) }
    var id by remember { mutableStateOf(TextFieldValue(initial.id)) }
    var transportType by remember { mutableStateOf(
        when (initial.transport) {
            is TransportConfig.StdioTransport -> "STDIO"
            is TransportConfig.HttpTransport -> "HTTP"
            is TransportConfig.WebSocketTransport -> "WebSocket"
        }
    ) }
    var command by remember { mutableStateOf(TextFieldValue((initial.transport as? TransportConfig.StdioTransport)?.command ?: "")) }
    var args by remember { mutableStateOf(TextFieldValue((initial.transport as? TransportConfig.StdioTransport)?.args?.joinToString(",") ?: "")) }
    var url by remember { mutableStateOf(TextFieldValue((initial.transport as? TransportConfig.HttpTransport)?.url ?: (initial.transport as? TransportConfig.WebSocketTransport)?.url ?: "")) }
    var headers by remember { mutableStateOf(TextFieldValue((initial.transport as? TransportConfig.HttpTransport)?.headers?.entries?.joinToString("\n") { (k, v) -> "$k:$v" } ?: "")) }

    fun buildConfig(): McpServerConfig? {
        val t: TransportConfig = when (transportType) {
            "STDIO" -> {
                val cmd = command.text.trim()
                val parts = args.text.split(',').mapNotNull { it.trim().takeIf { it.isNotEmpty() } }
                if (cmd.isBlank()) return null
                TransportConfig.StdioTransport(command = cmd, args = parts)
            }
            "HTTP" -> {
                val u = url.text.trim()
                if (u.isBlank()) return null
                val hdrs = headers.text.lines().mapNotNull { line ->
                    val idx = line.indexOf(':')
                    if (idx <= 0) null else (line.substring(0, idx).trim() to line.substring(idx + 1).trim())
                }.toMap()
                TransportConfig.HttpTransport(url = u, headers = hdrs)
            }
            else -> {
                val u = url.text.trim()
                if (u.isBlank()) return null
                TransportConfig.WebSocketTransport(url = u)
            }
        }
        val n = name.text.trim()
        val i = id.text.trim()
        if (n.isBlank() || i.isBlank()) return null
        return McpServerConfig(id = i, name = n, transport = t, enabled = initial.enabled)
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Edit server") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = id, onValueChange = { id = it }, label = { Text("ID") }, modifier = Modifier.fillMaxWidth())

                Text("Transport")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("STDIO", "HTTP", "WebSocket").forEach { opt ->
                        TextButton(onClick = { transportType = opt }) { Text(if (transportType == opt) "[$opt]" else opt) }
                    }
                }

                when (transportType) {
                    "STDIO" -> {
                        OutlinedTextField(value = command, onValueChange = { command = it }, label = { Text("Command") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = args, onValueChange = { args = it }, label = { Text("Args (comma-separated)") }, modifier = Modifier.fillMaxWidth())
                    }
                    "HTTP" -> {
                        OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("HTTP URL") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = headers, onValueChange = { headers = it }, label = { Text("Headers (key:value per line)") }, modifier = Modifier.fillMaxWidth())
                    }
                    else -> {
                        OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("WebSocket URL") }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val cfg = buildConfig()
                if (cfg != null) onSave(cfg)
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("Cancel") }
        }
    )
}

@Composable
fun AddPresetDialog(state: AppState) {
    var name by remember { mutableStateOf(TextFieldValue("")) }
    var description by remember { mutableStateOf(TextFieldValue("")) }

    AlertDialog(
        onDismissRequest = { state.showAddPresetDialog.value = false },
        title = { Text("Add preset") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description (optional)") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = {
            Button(onClick = {
                if (name.text.isNotBlank()) {
                    state.presets.add(UiPreset(id = name.text.trim().lowercase().replace(" ", "-"), name = name.text.trim(), description = description.text.ifBlank { null }))
                    state.showAddPresetDialog.value = false
                }
            }) { Text("Add") }
        },
        dismissButton = {
            Button(onClick = { state.showAddPresetDialog.value = false }) { Text("Cancel") }
        }
    )
}
