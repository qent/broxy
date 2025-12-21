package io.qent.broxy.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.*
import io.qent.broxy.ui.strings.LocalStrings
import io.qent.broxy.ui.theme.AppTheme

data class ServerFormState(
    val name: String = "",
    val enabled: Boolean = true,
    // One of: STDIO, HTTP, STREAMABLE_HTTP, WS.
    val transportType: String = "STDIO",
    val command: String = "",
    val args: String = "",
    val url: String = "",
    val headers: String = "",
    val env: String = "",
)

fun ServerFormState.toDraft(
    id: String,
    name: String,
    originalId: String?,
): UiServerDraft {
    fun parseHeaders(raw: String): Map<String, String> =
        raw.lines().mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) {
                null
            } else {
                line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
        }.toMap()

    val envMap =
        env.lines().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val idx = trimmed.indexOf(':')
            if (idx <= 0) return@mapNotNull null
            val key = trimmed.substring(0, idx).trim()
            val value = trimmed.substring(idx + 1).trim()
            if (key.isEmpty()) null else key to value
        }.toMap()
    val draftTransport =
        when (transportType) {
            "STDIO" ->
                UiStdioDraft(
                    command = command.trim(),
                    args = args.split(',').mapNotNull { it.trim().takeIf { v -> v.isNotEmpty() } },
                )

            "HTTP" ->
                UiHttpDraft(
                    url = url.trim(),
                    headers = parseHeaders(headers),
                )

            "STREAMABLE_HTTP" ->
                UiStreamableHttpDraft(
                    url = url.trim(),
                    headers = parseHeaders(headers),
                )

            else ->
                UiWebSocketDraft(
                    url = url.trim(),
                    headers = parseHeaders(headers),
                )
        }
    return UiServerDraft(
        id = id.trim(),
        name = name.trim(),
        enabled = enabled,
        transport = draftTransport,
        env = if (transportType == "STDIO") envMap else emptyMap(),
        originalId = originalId,
    )
}

object ServerFormStateFactory {
    fun from(initial: UiServerDraft): ServerFormState {
        val (transportType, command, args, url, headers) =
            when (val t = initial.transport) {
                is UiStdioDraft -> arrayOf("STDIO", t.command, t.args.joinToString(","), "", "")
                is UiHttpDraft -> arrayOf("HTTP", "", "", t.url, t.headers.entries.joinToString("\n") { (k, v) -> "$k:$v" })
                is UiStreamableHttpDraft ->
                    arrayOf(
                        "STREAMABLE_HTTP",
                        "",
                        "",
                        t.url,
                        t.headers.entries.joinToString("\n") { (k, v) -> "$k:$v" },
                    )

                is UiWebSocketDraft ->
                    arrayOf(
                        "WS",
                        "",
                        "",
                        t.url,
                        t.headers.entries.joinToString("\n") { (k, v) -> "$k:$v" },
                    )
            }
        return ServerFormState(
            name = initial.name,
            enabled = initial.enabled,
            transportType = transportType,
            command = command,
            args = args,
            url = url,
            headers = headers,
            env = initial.env.entries.joinToString("\n") { (k, v) -> "$k:$v" },
        )
    }
}

@Composable
fun ServerForm(
    state: ServerFormState,
    onStateChange: (ServerFormState) -> Unit,
    commandWarning: String? = null,
    onCommandBlur: ((String) -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
    ) {
        TransportSelector(
            selected = state.transportType,
            onSelected = { onStateChange(state.copy(transportType = it)) },
        )

        when (state.transportType) {
            "STDIO" -> StdIoFields(state, onStateChange, commandWarning, onCommandBlur)
            "HTTP", "STREAMABLE_HTTP" ->
                HttpFields(
                    state,
                    onStateChange,
                    isStreamable = state.transportType == "STREAMABLE_HTTP",
                )

            else -> WebSocketFields(state, onStateChange)
        }
    }
}

// Helper to build a UiMcpServerConfig from a UI draft for validation
// Deliberately avoid exposing core aliases in UI; conversion lives in ui-adapter services.

@Composable
private fun StdIoFields(
    state: ServerFormState,
    onStateChange: (ServerFormState) -> Unit,
    commandWarning: String?,
    onCommandBlur: ((String) -> Unit)?,
) {
    val strings = LocalStrings.current
    var wasFocused by remember { mutableStateOf(false) }
    val warningContent: (@Composable () -> Unit)? =
        commandWarning?.let { warning ->
            { Text(warning, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
        }
    val commandModifier =
        Modifier
            .fillMaxWidth()
            .onFocusChanged { focusState ->
                if (wasFocused && !focusState.isFocused) {
                    onCommandBlur?.invoke(state.command)
                }
                wasFocused = focusState.isFocused
            }
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
        TransportTextField(
            value = state.command,
            onValueChange = { onStateChange(state.copy(command = it)) },
            label = strings.commandLabel,
            modifier = commandModifier,
            singleLine = true,
            isError = commandWarning != null,
            supportingText = warningContent,
        )
        TransportTextField(
            value = state.args,
            onValueChange = { onStateChange(state.copy(args = it)) },
            label = strings.argsLabel,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        TransportTextField(
            value = state.env,
            onValueChange = { onStateChange(state.copy(env = it)) },
            label = strings.envLabel,
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
        )
    }
}

@Composable
private fun HttpFields(
    state: ServerFormState,
    onStateChange: (ServerFormState) -> Unit,
    isStreamable: Boolean,
) {
    val strings = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
        TransportTextField(
            value = state.url,
            onValueChange = { onStateChange(state.copy(url = it)) },
            label = if (isStreamable) strings.httpStreamableUrlLabel else strings.httpSseUrlLabel,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        TransportTextField(
            value = state.headers,
            onValueChange = { onStateChange(state.copy(headers = it)) },
            label = strings.headersLabel,
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )
    }
}

@Composable
private fun WebSocketFields(
    state: ServerFormState,
    onStateChange: (ServerFormState) -> Unit,
) {
    val strings = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
        TransportTextField(
            value = state.url,
            onValueChange = { onStateChange(state.copy(url = it)) },
            label = strings.webSocketUrlLabel,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        TransportTextField(
            value = state.headers,
            onValueChange = { onStateChange(state.copy(headers = it)) },
            label = strings.headersLabel,
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )
    }
}

@Composable
private fun TransportSelector(
    selected: String,
    onSelected: (String) -> Unit,
) {
    val strings = LocalStrings.current
    val options =
        listOf(
            TransportOption("STDIO", strings.transportStdioLabel),
            TransportOption("STREAMABLE_HTTP", strings.transportStreamableHttpLabel),
            TransportOption("HTTP", strings.transportHttpSseLabel),
            TransportOption("WS", strings.transportWebSocketLabel),
        )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        options.forEach { option ->
            TransportOptionCard(
                option = option,
                selected = option.value == selected,
                onSelected = { onSelected(option.value) },
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun RowScope.TransportOptionCard(
    option: TransportOption,
    selected: Boolean,
    onSelected: () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    val hoverModifier =
        Modifier
            .onPointerEvent(PointerEventType.Enter) {
                hovered = true
            }
            .onPointerEvent(PointerEventType.Exit) {
                hovered = false
            }

    val borderColor =
        when {
            selected -> MaterialTheme.colorScheme.primary
            hovered -> MaterialTheme.colorScheme.outline
            else -> MaterialTheme.colorScheme.background
        }

    androidx.compose.material3.Card(
        modifier =
            Modifier
                .weight(1f)
                .then(hoverModifier)
                .clickable(onClick = onSelected),
        shape = AppTheme.shapes.item,
        colors =
            androidx.compose.material3.CardDefaults.cardColors(
                containerColor =
                    if (selected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(
                            alpha = 0.1f,
                        )
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
            ),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppTheme.spacing.lg, vertical = AppTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.none),
        ) {
            Text(option.label, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun TransportTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    minLines: Int = 1,
    isError: Boolean = false,
    supportingText: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        modifier = modifier,
        singleLine = singleLine,
        minLines = minLines,
        textStyle = MaterialTheme.typography.bodySmall,
        isError = isError,
        supportingText = supportingText,
    )
}

private data class TransportOption(
    val value: String,
    val label: String,
)
