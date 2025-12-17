package io.qent.broxy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.UiProxyStatus
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.theme.AppTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalHeader(
    ui: UIState,
    notify: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val port = (ui as? UIState.Ready)?.inboundSsePort ?: 3335
    val sseUrl = "http://0.0.0.0:$port/mcp"

    val status = (ui as? UIState.Ready)?.proxyStatus
    val (dotColor, statusText) = when (status) {
        UiProxyStatus.Running -> AppTheme.extendedColors.success to "Running"
        UiProxyStatus.Starting -> MaterialTheme.colorScheme.secondary to "Starting"
        UiProxyStatus.Stopping -> MaterialTheme.colorScheme.secondary to "Stopping"
        UiProxyStatus.Stopped, null -> MaterialTheme.colorScheme.outline to "Stopped"
        is UiProxyStatus.Error -> {
            val message = status.message.ifBlank { "Error" }
            val portBusy = message.contains("already in use", ignoreCase = true) ||
                message.contains("Address already in use", ignoreCase = true)
            MaterialTheme.colorScheme.error to (if (portBusy) "Порт занят" else message)
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.md)
    ) {
        PresetDropdown(ui = ui, notify = notify, modifier = Modifier.width(320.dp))

        OutlinedTextField(
            value = sseUrl,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("SSE endpoint") },
            modifier = Modifier.weight(1f),
            shape = AppTheme.shapes.input
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(Modifier.width(AppTheme.spacing.sm))
            Text(
                statusText,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    Spacer(Modifier.height(AppTheme.spacing.md))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetDropdown(
    ui: UIState,
    notify: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    when (ui) {
        UIState.Loading -> OutlinedTextField(
            value = "Loading…",
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("Preset") },
            modifier = modifier,
            shape = AppTheme.shapes.input
        )

        is UIState.Error -> OutlinedTextField(
            value = "Unavailable",
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("Preset") },
            modifier = modifier,
            shape = AppTheme.shapes.input
        )

        is UIState.Ready -> {
            val selectedPresetId = ui.selectedPresetId
            val currentName = ui.presets.firstOrNull { it.id == selectedPresetId }?.name
                ?: if (selectedPresetId == null) "No preset" else selectedPresetId

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
                modifier = modifier
            ) {
                OutlinedTextField(
                    value = currentName,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text("Preset") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor(),
                    shape = AppTheme.shapes.input
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("No preset") },
                        onClick = {
                            expanded = false
                            if (ui.selectedPresetId != null) {
                                ui.intents.selectProxyPreset(null)
                                notify("Preset cleared")
                            }
                        }
                    )
                    if (ui.presets.isNotEmpty()) {
                        DropdownMenuItem(
                            enabled = false,
                            text = { Text("—") },
                            onClick = {}
                        )
                    }
                    ui.presets.forEach { p ->
                        val isSelected = p.id == selectedPresetId
                        DropdownMenuItem(
                            text = { Text(p.name) },
                            onClick = {
                                expanded = false
                                if (!isSelected) {
                                    ui.intents.selectProxyPreset(p.id)
                                    notify("Preset selected: ${p.name}")
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
