package io.qent.bro.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import io.qent.bro.core.models.TransportConfig
import io.qent.bro.ui.data.provideConfigurationRepository
import io.qent.bro.ui.viewmodels.AppState
import io.qent.bro.ui.viewmodels.ProxyStatus
import io.qent.bro.ui.viewmodels.ProxyViewModel

@Composable
fun ProxyScreen(state: AppState, notify: (String) -> Unit = {}) {
    val vm = remember { ProxyViewModel() }
    val repo = remember { provideConfigurationRepository() }
    var presetId by remember { mutableStateOf<String?>(null) }
    var inboundMode by remember { mutableStateOf("HTTP") }
    var inboundUrl by remember { mutableStateOf("http://0.0.0.0:3335/mcp") }

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
                    Text("Preset: ${presetId ?: "(select)"}")
                }

                Spacer(Modifier.padding(8.dp))
                // Inbound selection (simple)
                Text("Inbound: $inboundMode")
                Spacer(Modifier.padding(2.dp))
                if (inboundMode != "STDIO") {
                    Text("URL: $inboundUrl", style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.padding(8.dp))
                val running = vm.status.value is ProxyStatus.Running
                val statusText = when (val s = vm.status.value) {
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
                            "STDIO" -> TransportConfig.StdioTransport(command = "", args = emptyList())
                            "WS" -> TransportConfig.WebSocketTransport(url = inboundUrl.replace("http://", "ws://").replace("https://", "wss://"))
                            else -> TransportConfig.HttpTransport(url = inboundUrl)
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
