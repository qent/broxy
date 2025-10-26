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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.qent.bro.ui.viewmodels.AppState
import io.qent.bro.ui.viewmodels.ProxyStatus

@Composable
fun ProxyScreen(state: AppState) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Proxy status", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.padding(2.dp))
                val status = when (val s = state.proxyStatus.value) {
                    ProxyStatus.Running -> "Running"
                    ProxyStatus.Stopped -> "Stopped"
                    is ProxyStatus.Error -> "Error: ${s.message}"
                }
                Text(status, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.padding(8.dp))
                val running = state.proxyStatus.value is ProxyStatus.Running
                Button(onClick = {
                    state.proxyStatus.value = if (running) ProxyStatus.Stopped else ProxyStatus.Running
                }) {
                    Text(if (running) "Stop proxy" else "Start proxy")
                }
            }
        }
    }
}

