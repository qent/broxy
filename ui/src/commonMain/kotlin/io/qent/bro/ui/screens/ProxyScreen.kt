package io.qent.bro.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.qent.bro.ui.adapter.models.UiTransportConfig as TransportConfig
import io.qent.bro.ui.adapter.models.UiStdioTransport as StdioTransport
import io.qent.bro.ui.adapter.models.UiHttpTransport as HttpTransport
import io.qent.bro.ui.adapter.models.UiWebSocketTransport as WebSocketTransport
import io.qent.bro.ui.adapter.data.provideConfigurationRepository
import io.qent.bro.ui.viewmodels.AppState
import io.qent.bro.ui.adapter.viewmodels.ProxyStatus
import io.qent.bro.ui.adapter.viewmodels.ProxyViewModel
import androidx.compose.runtime.collectAsState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyScreen(state: AppState, notify: (String) -> Unit = {}) {
    val vm = remember { ProxyViewModel() }
    val repo = remember { provideConfigurationRepository() }
    var presetId by remember { mutableStateOf<String?>(null) }
    var inboundMode by remember { mutableStateOf("HTTP") }
    var inboundUrl by remember { mutableStateOf("http://0.0.0.0:3335/mcp") }
    var presetExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(state.presets.size) {
        if (presetId == null && state.presets.isNotEmpty()) {
            presetId = state.presets.first().id
        }
    }
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
                // Preset selection
                if (state.presets.isEmpty()) {
                    Text("No presets available — create one on the Presets tab.")
                } else {
                    val currentName = state.presets.firstOrNull { it.id == presetId }?.name
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
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clickable { presetExpanded = !presetExpanded }
                                .let { m -> m }
                        )
                        DropdownMenu(expanded = presetExpanded, onDismissRequest = { presetExpanded = false }) {
                            state.presets.forEach { p ->
                                DropdownMenuItem(text = { Text(p.name) }, onClick = {
                                    presetId = p.id
                                    presetExpanded = false
                                    notify("Preset selected: ${p.name}")
                                })
                            }
                        }
                    }
                }

                Spacer(Modifier.padding(8.dp))
                // Inbound selection
                Text("Inbound mode", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.padding(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("STDIO", "HTTP", "WS").forEach { opt ->
                        androidx.compose.material3.TextButton(onClick = {
                            inboundMode = opt
                            if (opt == "HTTP" && (inboundUrl.isBlank() || inboundUrl.startsWith("ws"))) {
                                inboundUrl = "http://0.0.0.0:3335/mcp"
                            }
                            if (opt == "WS" && (inboundUrl.isBlank() || inboundUrl.startsWith("http"))) {
                                inboundUrl = "ws://0.0.0.0:3336/ws"
                            }
                        }) {
                            Text(if (inboundMode == opt) "[$opt]" else opt)
                        }
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
                val running = vm.status.collectAsState().value is ProxyStatus.Running
                val statusText = when (val s = vm.status.collectAsState().value) {
                    ProxyStatus.Running -> "Running"
                    ProxyStatus.Stopped -> "Stopped"
                    is ProxyStatus.Error -> "Error: ${s.message}"
                }
                Text(statusText, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.padding(8.dp))

                Button(onClick = {
                    if (!running) {
                        val pid = presetId
                        if (pid == null) {
                            notify("Select a preset first")
                            return@Button
                        }
                        val inbound = when (inboundMode) {
                            "STDIO" -> StdioTransport(command = "", args = emptyList())
                            "WS" -> WebSocketTransport(url = inboundUrl.replace("http://", "ws://").replace("https://", "wss://"))
                            else -> HttpTransport(url = inboundUrl)
                        }
                        val r = vm.start(state.servers.toList(), pid, inbound)
                        if (r.isSuccess) notify("Proxy started ($inboundMode)") else notify("Failed to start: ${r.exceptionOrNull()?.message}")
                    } else {
                        vm.stop()
                        notify("Proxy stopped")
                    }
                }) {
                    Text(if (running) "Stop proxy" else "Start proxy")
                }
            }
        }
    }
}
