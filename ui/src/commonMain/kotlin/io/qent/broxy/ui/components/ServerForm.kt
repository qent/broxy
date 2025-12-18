package io.qent.broxy.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.*
import io.qent.broxy.ui.theme.AppTheme

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
            is UiStreamableHttpDraft -> arrayOf(
                "STREAMABLE_HTTP",
                "",
                "",
                t.url,
                t.headers.entries.joinToString("\n") { (k, v) -> "$k:$v" })

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
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md)
    ) {
        FormSection(title = "General") {
            OutlinedTextField(
                value = state.name,
                onValueChange = { onStateChange(state.copy(name = it)) },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = state.id,
                onValueChange = { onStateChange(state.copy(id = it)) },
                label = { Text("ID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        FormSection(title = "Transport") {
            TransportSelector(
                selected = state.transportType,
                onSelected = { onStateChange(state.copy(transportType = it)) }
            )
            FormDivider()
            when (state.transportType) {
                "STDIO" -> StdIoFields(state, onStateChange)
                "HTTP", "STREAMABLE_HTTP" -> HttpFields(
                    state,
                    onStateChange,
                    isStreamable = state.transportType == "STREAMABLE_HTTP"
                )

                else -> WebSocketFields(state, onStateChange)
            }
        }
    }
}

// Helper to build a UiMcpServerConfig from a UI draft for validation
// Deliberately avoid exposing core aliases in UI; conversion lives in ui-adapter services.

@Composable
private fun StdIoFields(state: ServerFormState, onStateChange: (ServerFormState) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
        OutlinedTextField(
            value = state.command,
            onValueChange = { onStateChange(state.copy(command = it)) },
            label = { Text("Command") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = state.args,
            onValueChange = { onStateChange(state.copy(args = it)) },
            label = { Text("Args (comma-separated)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = state.env,
            onValueChange = { onStateChange(state.copy(env = it)) },
            label = { Text("Env (key:value per line, values may use {ENV_VAR})") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4
        )
    }
}

@Composable
private fun HttpFields(
    state: ServerFormState,
    onStateChange: (ServerFormState) -> Unit,
    isStreamable: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
        OutlinedTextField(
            value = state.url,
            onValueChange = { onStateChange(state.copy(url = it)) },
            label = { Text(if (isStreamable) "Streamable HTTP URL" else "HTTP URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = state.headers,
            onValueChange = { onStateChange(state.copy(headers = it)) },
            label = { Text("Headers (key:value per line)") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3
        )
    }
}

@Composable
private fun WebSocketFields(state: ServerFormState, onStateChange: (ServerFormState) -> Unit) {
    OutlinedTextField(
        value = state.url,
        onValueChange = { onStateChange(state.copy(url = it)) },
        label = { Text("WebSocket URL") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}

@Composable
private fun TransportSelector(
    selected: String,
    onSelected: (String) -> Unit
) {
    val options = listOf(
        TransportOption("STDIO", "STDIO", "Local process"),
        TransportOption("HTTP", "HTTP", "Proxy over HTTP"),
        TransportOption("STREAMABLE_HTTP", "Streamable HTTP", "Server-sent events"),
        TransportOption("WS", "WebSocket", "Persistent socket")
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)
    ) {
        options.forEach { option ->
            TransportOptionCard(
                option = option,
                selected = option.value == selected,
                onSelected = { onSelected(option.value) }
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun RowScope.TransportOptionCard(
    option: TransportOption,
    selected: Boolean,
    onSelected: () -> Unit
) {
    var hovered by remember { mutableStateOf(false) }
    val hoverModifier = Modifier
        .onPointerEvent(PointerEventType.Enter) {
            hovered = true
        }
        .onPointerEvent(PointerEventType.Exit) {
            hovered = false
        }

    val borderColor = when {
        selected -> MaterialTheme.colorScheme.primary
        hovered -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    androidx.compose.material3.Card(
        modifier = Modifier
            .weight(1f)
            .then(hoverModifier)
            .clickable(onClick = onSelected),
        shape = AppTheme.shapes.item,
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppTheme.spacing.sm, vertical = AppTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)
        ) {
            Text(option.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                option.hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FormSection(
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
            FormDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
                content = content
            )
        }
    }
}

@Composable
private fun FormDivider() {
    HorizontalDivider(
        modifier = Modifier.fillMaxWidth(),
        thickness = AppTheme.strokeWidths.hairline,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

private data class TransportOption(
    val value: String,
    val label: String,
    val hint: String
)
