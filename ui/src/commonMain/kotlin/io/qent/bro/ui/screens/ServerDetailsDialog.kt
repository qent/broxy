package io.qent.bro.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qent.bro.core.models.McpServerConfig
import io.qent.bro.ui.viewmodels.ServersViewModel

@Composable
fun ServerDetailsDialog(cfg: McpServerConfig, vm: ServersViewModel, onClose: () -> Unit) {
    val state = vm.uiStates[cfg.id]?.value
    val caps = state?.lastCapabilities
    val logs = vm.getLogs(cfg.id)

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("${cfg.name} • Details") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Tools", style = MaterialTheme.typography.titleMedium)
                if (caps?.tools.isNullOrEmpty()) {
                    Text("No tools fetched yet. Use 'Test Connection' to fetch capabilities.")
                } else {
                    LazyColumn(modifier = Modifier.height(180.dp)) {
                        items(caps!!.tools) { t ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Text(t.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!t.description.isNullOrBlank()) {
                                    Text(t.description!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Divider()
                        }
                    }
                }

                Text("Logs", style = MaterialTheme.typography.titleMedium)
                LazyColumn(modifier = Modifier.height(120.dp)) {
                    items(logs) { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Text(" ")
        },
        dismissButton = {
            Text(" ", modifier = Modifier) // no secondary button
        }
    )
}

