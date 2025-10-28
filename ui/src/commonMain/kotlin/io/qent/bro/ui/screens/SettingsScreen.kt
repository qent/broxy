package io.qent.bro.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.qent.bro.ui.adapter.store.UIState
import io.qent.bro.ui.viewmodels.AppState
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(state: AppState, ui: UIState) {
    val theme = state.theme.value
    val ready = ui as? UIState.Ready
    val timeoutFromState = ready?.requestTimeoutSeconds ?: 60
    var timeout by remember(timeoutFromState) { mutableStateOf(timeoutFromState.toFloat()) }
    LaunchedEffect(timeoutFromState) { timeout = timeoutFromState.toFloat() }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Theme", style = MaterialTheme.typography.titleMedium)

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Dark theme", modifier = Modifier.weight(1f))
                    Switch(checked = theme.darkTheme, onCheckedChange = { state.theme.value = theme.copy(darkTheme = it) })
                }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Use dynamic colors", modifier = Modifier.weight(1f))
                    Switch(checked = theme.dynamicColors, onCheckedChange = { state.theme.value = theme.copy(dynamicColors = it) })
                }

                Text("Corner radius (medium): ${theme.mediumCornerRadius}dp")
                Slider(
                    value = theme.mediumCornerRadius.toFloat(),
                    onValueChange = { v -> state.theme.value = theme.copy(mediumCornerRadius = v.roundToInt().coerceIn(8, 32)) },
                    valueRange = 8f..32f
                )

                Text("Corner radius (large): ${theme.largeCornerRadius}dp")
                Slider(
                    value = theme.largeCornerRadius.toFloat(),
                    onValueChange = { v -> state.theme.value = theme.copy(largeCornerRadius = v.roundToInt().coerceIn(16, 40)) },
                    valueRange = 16f..40f
                )

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Animations enabled", modifier = Modifier.weight(1f))
                    Switch(checked = theme.motionEnabled, onCheckedChange = { state.theme.value = theme.copy(motionEnabled = it) })
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("MCP timeouts", style = MaterialTheme.typography.titleMedium)
                Text("Tool call timeout: ${timeout.roundToInt()}s", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = timeout,
                    onValueChange = { timeout = it },
                    valueRange = 5f..600f,
                    steps = 595,
                    enabled = ready != null,
                    onValueChangeFinished = {
                        ready?.intents?.updateRequestTimeout(timeout.roundToInt())
                    }
                )
                if (ready == null) {
                    Text("Timeout controls become available once data is loaded.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
