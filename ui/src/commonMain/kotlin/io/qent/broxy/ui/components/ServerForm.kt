package io.qent.broxy.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.UiHttpDraft
import io.qent.broxy.ui.adapter.models.UiServerDraft
import io.qent.broxy.ui.adapter.models.UiStdioDraft
import io.qent.broxy.ui.adapter.models.UiWebSocketDraft
import io.qent.broxy.ui.adapter.models.UiStreamableHttpDraft

data class ServerFormState(
    val name: String = "",
    val id: String = "",
    val enabled: Boolean = true,
    val transportType: String = "STDIO", // one of: STDIO, HTTP, STREAMABLE_HTTP, WS
    val command: String = "",
    val args: String = "",
    val url: String = "",
    val headers: String = "",
    val env: String = ""
)

fun ServerFormState.toDraft(): UiServerDraft {
    val envMap = env.lines().mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return@mapNotNull null
        val idx = trimmed.indexOf(':')
        if (idx <= 0) return@mapNotNull null
        val key = trimmed.substring(0, idx).trim()
        val value = trimmed.substring(idx + 1).trim()
        if (key.isEmpty()) null else key to value
    }.toMap()
    val draftTransport = when (transportType) {
        "STDIO" -> UiStdioDraft(
            command = command.trim(),
            args = args.split(',').mapNotNull { it.trim().takeIf { v -> v.isNotEmpty() } }
        )
        "HTTP" -> UiHttpDraft(
            url = url.trim(),
            headers = headers.lines().mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) null else (
                    line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                )
            }.toMap()
        )
        "STREAMABLE_HTTP" -> UiStreamableHttpDraft(
            url = url.trim(),
            headers = headers.lines().mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) null else (
                    line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                )
            }.toMap()
        )
        else -> UiWebSocketDraft(url = url.trim())
    }
    return UiServerDraft(
        id = id.trim(),
        name = name.trim(),
        enabled = enabled,
        transport = draftTransport,
        env = if (transportType == "STDIO") envMap else emptyMap()
    )
}

object ServerFormStateFactory {
    fun from(initial: UiServerDraft): ServerFormState {
        val (transportType, command, args, url, headers) = when (val t = initial.transport) {
            is UiStdioDraft -> arrayOf("STDIO", t.command, t.args.joinToString(","), "", "")
            is UiHttpDraft -> arrayOf("HTTP", "", "", t.url, t.headers.entries.joinToString("\n") { (k, v) -> "$k:$v" })
            is UiStreamableHttpDraft -> arrayOf("STREAMABLE_HTTP", "", "", t.url, t.headers.entries.joinToString("\n") { (k, v) -> "$k:$v" })
            is UiWebSocketDraft -> arrayOf("WS", "", "", t.url, "")
            else -> arrayOf("STDIO", "", "", "", "")
        }
        return ServerFormState(
            name = initial.name,
            id = initial.id,
            enabled = initial.enabled,
            transportType = transportType,
            command = command,
            args = args,
            url = url,
            headers = headers,
            env = initial.env.entries.joinToString("\n") { (k, v) -> "$k:$v" }
        )
    }
}

@Composable
fun ServerForm(
    state: ServerFormState,
    onStateChange: (ServerFormState) -> Unit
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.name,
            onValueChange = { onStateChange(state.copy(name = it)) },
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = state.id,
            onValueChange = { onStateChange(state.copy(id = it)) },
            label = { Text("ID") },
            modifier = Modifier.fillMaxWidth()
        )
        Row {
            Text("Enabled")
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(4.dp))
            Switch(checked = state.enabled, onCheckedChange = { onStateChange(state.copy(enabled = it)) })
        }
        Text("Transport")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("STDIO", "HTTP", "STREAMABLE_HTTP", "WS").forEach { label ->
                TextButton(onClick = { onStateChange(state.copy(transportType = label)) }) {
                    val pretty = when (label) {
                        "STDIO" -> "STDIO"
                        "HTTP" -> "HTTP"
                        "STREAMABLE_HTTP" -> "HTTP (Streamable)"
                        else -> "WS"
                    }
                    Text(if (state.transportType == label) "[$pretty]" else pretty)
                }
            }
        }
        when (state.transportType) {
            "STDIO" -> {
                OutlinedTextField(
                    value = state.command,
                    onValueChange = { onStateChange(state.copy(command = it)) },
                    label = { Text("Command") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.args,
                    onValueChange = { onStateChange(state.copy(args = it)) },
                    label = { Text("Args (comma-separated)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.env,
                    onValueChange = { onStateChange(state.copy(env = it)) },
                    label = { Text("Env (key:value per line, values may use {ENV_VAR})") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            "HTTP", "STREAMABLE_HTTP" -> {
                OutlinedTextField(
                    value = state.url,
                    onValueChange = { onStateChange(state.copy(url = it)) },
                    label = { Text("HTTP URL") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.headers,
                    onValueChange = { onStateChange(state.copy(headers = it)) },
                    label = { Text("Headers (key:value per line)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            else -> {
                OutlinedTextField(
                    value = state.url,
                    onValueChange = { onStateChange(state.copy(url = it)) },
                    label = { Text("WebSocket URL") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// Helper to build a UiMcpServerConfig from a UI draft for validation
// Deliberately avoid exposing core aliases in UI; conversion lives in ui-adapter services.
