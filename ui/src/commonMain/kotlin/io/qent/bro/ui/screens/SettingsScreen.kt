package io.qent.bro.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Switch
import io.qent.bro.ui.adapter.store.UIState

@Composable
fun SettingsScreen(ui: UIState, notify: (String) -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        when (ui) {
            UIState.Loading -> Text("Loading...", style = MaterialTheme.typography.bodyMedium)
            is UIState.Error -> Text("Error: ${ui.message}", style = MaterialTheme.typography.bodyMedium)
            is UIState.Ready -> SettingsContent(
                timeoutSeconds = ui.requestTimeoutSeconds,
                showTrayIcon = ui.showTrayIcon,
                onTimeoutSave = { seconds ->
                    ui.intents.updateRequestTimeout(seconds)
                    notify("Timeout saved: ${seconds}s")
                },
                onToggleTrayIcon = { enabled ->
                    ui.intents.updateTrayIconVisibility(enabled)
                    notify(if (enabled) "Tray icon enabled" else "Tray icon disabled")
                }
            )
        }
    }
}

@Composable
private fun SettingsContent(
    timeoutSeconds: Int,
    showTrayIcon: Boolean,
    onTimeoutSave: (Int) -> Unit,
    onToggleTrayIcon: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TrayIconToggle(checked = showTrayIcon, onToggle = onToggleTrayIcon)
        TimeoutForm(timeoutSeconds = timeoutSeconds, onSave = onTimeoutSave)
    }
}

@Composable
private fun TrayIconToggle(
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Show tray icon", style = MaterialTheme.typography.titleMedium)
            Text(
                "Show the bro icon in the system tray (macOS menu bar).",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

@Composable
private fun TimeoutForm(
    timeoutSeconds: Int,
    onSave: (Int) -> Unit
) {
    var input by rememberSaveable(timeoutSeconds) { mutableStateOf(timeoutSeconds.toString()) }

    LaunchedEffect(timeoutSeconds) {
        input = timeoutSeconds.toString()
    }

    val parsed = input.toLongOrNull()
    val resolved = parsed?.takeIf { it > 0 && it <= Int.MAX_VALUE }?.toInt()
    val canSave = resolved != null && resolved != timeoutSeconds

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { value ->
                if (value.isEmpty() || value.all { it.isDigit() }) {
                    input = value
                }
            },
            label = { Text("Timeout (seconds)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Button(
            onClick = {
                val seconds = resolved ?: return@Button
                onSave(seconds)
            },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save")
        }
    }
}
