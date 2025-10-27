package io.qent.bro.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
 
import io.qent.bro.ui.adapter.store.UIState
import io.qent.bro.ui.viewmodels.AppState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyScreen(ui: UIState, state: AppState, notify: (String) -> Unit = {}) {
    var presetId by remember { mutableStateOf<String?>(null) }
    var presetExpanded by remember { mutableStateOf(false) }
    var inboundMode by remember { mutableStateOf("HTTP") }
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
                Text("Proxy", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.padding(2.dp))

                if (ui is UIState.Ready) {
                    val presets = ui.presets
                    if (presets.isEmpty()) {
                        Text("No presets available — create one on the Presets tab.")
                    } else {
                        val currentName = presets.firstOrNull { it.id == presetId }?.name
                            ?: presetId ?: "(select)"
                        Text("Preset:", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.padding(2.dp))
                        Box {
                            OutlinedTextField(
                                value = currentName,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Select preset") },
                                trailingIcon = { Text(if (presetExpanded) "▲" else "▼") },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                            )
                            DropdownMenu(expanded = presetExpanded, onDismissRequest = { presetExpanded = false }) {
                                presets.forEach { p ->
                                    DropdownMenuItem(text = { Text(p.name) }, onClick = {
                                        presetId = p.id
                                        presetExpanded = false
                                        notify("Preset selected: ${'$'}{p.name}")
                                    })
                                }
                            }
                            TextButton(onClick = { presetExpanded = !presetExpanded }) { Text(if (presetExpanded) "Hide" else "Choose") }
                        }

                        Spacer(Modifier.padding(8.dp))
                        Text("Inbound mode", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.padding(2.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("STDIO", "HTTP", "WS").forEach { opt ->
                                TextButton(onClick = {
                                    inboundMode = opt
                                    if (opt == "HTTP" && (inboundUrl.isBlank() || inboundUrl.startsWith("ws"))) {
                                        inboundUrl = "http://0.0.0.0:3335/mcp"
                                    }
                                    if (opt == "WS" && (inboundUrl.isBlank() || inboundUrl.startsWith("http"))) {
                                        inboundUrl = "ws://0.0.0.0:3336/ws"
                                    }
                                }) { Text(if (inboundMode == opt) "[$opt]" else opt) }
                            }
                        }
                        if (inboundMode != "STDIO") {
                            Spacer(Modifier.padding(2.dp))
                            OutlinedTextField(
                                value = inboundUrl,
                                onValueChange = { inboundUrl = it },
                                label = { Text(if (inboundMode == "WS") "WebSocket URL" else "HTTP URL") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Spacer(Modifier.padding(8.dp))
                        val running = ui.proxyStatus is io.qent.bro.ui.adapter.models.UiProxyStatus.Running
                        val statusText = when (val s = ui.proxyStatus) {
                            is io.qent.bro.ui.adapter.models.UiProxyStatus.Running -> "Running"
                            is io.qent.bro.ui.adapter.models.UiProxyStatus.Stopped -> "Stopped"
                            is io.qent.bro.ui.adapter.models.UiProxyStatus.Error -> "Error: ${'$'}{s.message}"
                        }
                        Text(statusText, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.padding(8.dp))

                        Button(onClick = {
                            val pid = presetId
                            if (!running) {
                                if (pid == null) {
                                    notify("Select a preset first")
                                    return@Button
                                }
                                ui.intents.startProxySimple(pid)
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
