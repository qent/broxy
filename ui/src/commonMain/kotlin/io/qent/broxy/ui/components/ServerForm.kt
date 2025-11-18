package io.qent.broxy.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import io.qent.broxy.ui.adapter.models.UiHttpDraft
import io.qent.broxy.ui.adapter.models.UiServerDraft
import io.qent.broxy.ui.adapter.models.UiStdioDraft
import io.qent.broxy.ui.adapter.models.UiWebSocketDraft
import io.qent.broxy.ui.adapter.models.UiStreamableHttpDraft
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
                "HTTP", "STREAMABLE_HTTP" -> HttpFields(state, onStateChange, isStreamable = state.transportType == "STREAMABLE_HTTP")
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
    Surface(
        modifier = Modifier
            .weight(1f)
            .then(hoverModifier)
            .clickable(onClick = onSelected),
        shape = AppTheme.shapes.surfaceSm,
        tonalElevation = if (selected || hovered) AppTheme.elevation.level1 else AppTheme.elevation.level0,
        border = BorderStroke(
            width = AppTheme.strokeWidths.hairline,
            color = when {
                selected -> MaterialTheme.colorScheme.primary
                hovered -> MaterialTheme.colorScheme.outline
                else -> MaterialTheme.colorScheme.outlineVariant
            }
        )
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
