package io.qent.broxy.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.qent.broxy.ui.adapter.models.UiHttpDraft
import io.qent.broxy.ui.adapter.models.UiStdioDraft
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.theme.AppTheme
import io.qent.broxy.ui.viewmodels.AppState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyScreen(ui: UIState, state: AppState, notify: (String) -> Unit = {}) {
    var presetExpanded by remember { mutableStateOf(false) }
    val localOption = "Local (STDIO)"
    val remoteOption = "Remote (SSE)"
    var inboundMode by remember { mutableStateOf(remoteOption) }
    var inboundUrl by remember { mutableStateOf("http://0.0.0.0:3335/mcp") }

    Column(
        Modifier
            .fillMaxSize()
            .padding(AppTheme.spacing.md)
    ) {
        when (ui) {
            is UIState.Loading -> Text("Loading...", style = MaterialTheme.typography.bodyMedium)
            is UIState.Error -> Text("Error: ${ui.message}")
            is UIState.Ready -> {
                val presets = ui.presets
                if (presets.isEmpty()) {
                    Text("No presets available — create one on the Presets tab.")
                } else {
                    val selectedPresetId = ui.selectedPresetId
                    val currentName = presets.firstOrNull { it.id == selectedPresetId }?.name
                        ?: selectedPresetId ?: "(select)"
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

                    Spacer(Modifier.height(AppTheme.spacing.md))
                    Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
                        val options = listOf(localOption, remoteOption)
                        options.forEach { opt ->
                            val selected = inboundMode == opt
                            val (bg, fg) = if (opt == localOption) {
                                MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                            }
                            FilledTonalButton(
                                onClick = {
                                    inboundMode = opt
                                    if (opt == localOption) {
                                        inboundUrl = ""
                                    } else if (inboundUrl.isBlank()) {
                                        inboundUrl = "http://0.0.0.0:3335/mcp"
                                    }
                                },
                                shape = AppTheme.shapes.surfaceSm,
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (selected) bg else MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = if (selected) fg else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            ) {
                                Text(opt)
                            }
                        }
                    }
                    if (inboundMode != localOption) {
                        Spacer(Modifier.height(AppTheme.spacing.sm))
                        OutlinedTextField(
                            value = inboundUrl,
                            onValueChange = { inboundUrl = it },
                            label = { Text("Remote (SSE) URL") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(Modifier.height(AppTheme.spacing.md))
                    val running = ui.proxyStatus is io.qent.broxy.ui.adapter.models.UiProxyStatus.Running
                    val statusText = when (val s = ui.proxyStatus) {
                        is io.qent.broxy.ui.adapter.models.UiProxyStatus.Running -> "Running"
                        is io.qent.broxy.ui.adapter.models.UiProxyStatus.Stopped -> "Stopped"
                        is io.qent.broxy.ui.adapter.models.UiProxyStatus.Error -> "Error: ${s.message}"
                    }
                    Text(statusText, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(AppTheme.spacing.sm))

                    Button(
                        onClick = {
                            val pid = ui.selectedPresetId
                            if (!running) {
                                if (pid == null) {
                                    notify("Select a preset first")
                                    return@Button
                                }
                                val inbound = if (inboundMode == localOption) {
                                    UiStdioDraft(command = "")
                                } else {
                                    UiHttpDraft(url = inboundUrl)
                                }
                                ui.intents.startProxy(pid, inbound)
                                notify("Proxy started")
                            } else {
                                ui.intents.stopProxy()
                                notify("Proxy stopped")
                            }
                        },
                        shape = AppTheme.shapes.surfaceSm
                    ) { Text(if (running) "Stop proxy" else "Start proxy") }
                }
            }
        }
    }
}
