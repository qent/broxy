package io.qent.broxy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.viewmodels.AppState
import io.qent.broxy.ui.adapter.models.UiHttpDraft
import io.qent.broxy.ui.adapter.models.UiStreamableHttpDraft
import io.qent.broxy.ui.adapter.models.UiWebSocketDraft
import io.qent.broxy.ui.adapter.models.UiStdioDraft

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyScreen(ui: UIState, state: AppState, notify: (String) -> Unit = {}) {
    var presetExpanded by remember { mutableStateOf(false) }
    var inboundMode by remember { mutableStateOf("HTTP SSE") }
    var inboundUrl by remember { mutableStateOf("http://0.0.0.0:3335/mcp") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Proxy", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.padding(2.dp))

                if (ui is UIState.Ready) {
                    val presets = ui.presets
                    if (presets.isEmpty()) {
                        Text("No presets available — create one on the Presets tab.")
                    } else {
                        val selectedPresetId = ui.selectedPresetId
                        val currentName = presets.firstOrNull { it.id == selectedPresetId }?.name
                            ?: selectedPresetId ?: "(select)"
                        Text("Preset:", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.padding(2.dp))
                        ExposedDropdownMenuBox(
                            expanded = presetExpanded,
                            onExpandedChange = { presetExpanded = !presetExpanded }
                        ) {
                            OutlinedTextField(
                                value = currentName,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Select preset") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = presetExpanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                                    .menuAnchor()
                            )
                            DropdownMenu(
                                expanded = presetExpanded,
                                onDismissRequest = { presetExpanded = false }
                            ) {
                                presets.forEach { p ->
                                    val isSelected = p.id == selectedPresetId
                                    DropdownMenuItem(
                                        text = { Text(p.name) },
                                        onClick = {
                                            presetExpanded = false
                                            if (!isSelected) {
                                                ui.intents.selectProxyPreset(p.id)
                                                notify("Preset selected: ${p.name}")
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.padding(8.dp))
                        Text("Inbound mode", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.padding(2.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("STDIO", "HTTP SSE", "WS").forEach { opt ->
                                val selected = inboundMode == opt
                                val (bg, fg) = when (opt) {
                                    "STDIO" -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
                                    "HTTP SSE" -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                                    "HTTP Streaming" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
                                    else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                FilledTonalButton(
                                    onClick = {
                                        inboundMode = opt
                                        when (opt) {
                                            "HTTP SSE" -> if (inboundUrl.isBlank() || inboundUrl.startsWith("ws")) inboundUrl = "http://0.0.0.0:3335/mcp"
                                            "HTTP Streaming" -> if (inboundUrl.isBlank() || inboundUrl.startsWith("ws")) inboundUrl = "http://0.0.0.0:3337/mcp"
                                            "WS" -> if (inboundUrl.isBlank() || inboundUrl.startsWith("http")) inboundUrl = "ws://0.0.0.0:3336/ws"
                                            else -> {}
                                        }
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = if (selected) bg else MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = if (selected) fg else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                ) {
                                    Text(opt)
                                }
                            }
                        }
                        if (inboundMode != "STDIO") {
                            Spacer(Modifier.padding(2.dp))
                            OutlinedTextField(
                                value = inboundUrl,
                                onValueChange = { inboundUrl = it },
                                label = { Text(
                                    when (inboundMode) {
                                        "WS" -> "WebSocket URL"
                                        "HTTP Streaming" -> "HTTP Streaming URL"
                                        else -> "HTTP (SSE) URL"
                                    }
                                ) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Spacer(Modifier.padding(8.dp))
                        val running = ui.proxyStatus is io.qent.broxy.ui.adapter.models.UiProxyStatus.Running
                        val statusText = when (val s = ui.proxyStatus) {
                            is io.qent.broxy.ui.adapter.models.UiProxyStatus.Running -> "Running"
                            is io.qent.broxy.ui.adapter.models.UiProxyStatus.Stopped -> "Stopped"
                            is io.qent.broxy.ui.adapter.models.UiProxyStatus.Error -> "Error: ${s.message}"
                        }
                        Text(statusText, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.padding(8.dp))

                        Button(onClick = {
                            val pid = ui.selectedPresetId
                            if (!running) {
                                if (pid == null) {
                                    notify("Select a preset first")
                                    return@Button
                                }
                                val inbound = when (inboundMode) {
                                    "STDIO" -> UiStdioDraft(command = "")
                                    "HTTP SSE" -> UiHttpDraft(url = inboundUrl)
                                    "HTTP Streaming" -> UiStreamableHttpDraft(url = inboundUrl)
                                    "WS" -> UiWebSocketDraft(url = inboundUrl)
                                    else -> UiHttpDraft(url = inboundUrl)
                                }
                                ui.intents.startProxy(pid, inbound)
                                notify("Proxy started")
                            } else {
                                ui.intents.stopProxy()
                                notify("Proxy stopped")
                            }
                        }) { Text(if (running) "Stop proxy" else "Start proxy") }
                    }
                } else {
                    Text("Loading presets...", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
