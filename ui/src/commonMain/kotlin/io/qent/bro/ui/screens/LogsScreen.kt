package io.qent.bro.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.qent.bro.ui.adapter.models.UiLogEntry
import io.qent.bro.ui.adapter.models.UiLogLevel
import io.qent.bro.ui.adapter.store.UIState
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun LogsScreen(ui: UIState) {
    when (ui) {
        is UIState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading logs…", style = MaterialTheme.typography.bodyMedium)
        }
        is UIState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Logs unavailable: ${ui.message}", style = MaterialTheme.typography.bodyMedium)
        }
        is UIState.Ready -> LogsContent(ui.logs)
    }
}

@Composable
private fun LogsContent(logs: List<UiLogEntry>) {
    if (logs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No logs yet. Trigger a request to see activity.", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(logs) { entry ->
            LogRow(entry)
            Divider()
        }
    }
}

@Composable
private fun LogRow(entry: UiLogEntry) {
    val formattedTime = remember(entry.timestampMillis) {
        val instant = Instant.fromEpochMilliseconds(entry.timestampMillis)
        val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        "%02d:%02d:%02d".format(local.hour, local.minute, local.second)
    }
    val message = remember(entry) {
        if (entry.throwableMessage.isNullOrBlank()) entry.message
        else "${entry.message} (${entry.throwableMessage})"
    }
    val levelColor = when (entry.level) {
        UiLogLevel.ERROR -> MaterialTheme.colorScheme.error
        UiLogLevel.WARN -> MaterialTheme.colorScheme.tertiary
        UiLogLevel.DEBUG -> MaterialTheme.colorScheme.outline
        UiLogLevel.INFO -> MaterialTheme.colorScheme.primary
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(formattedTime, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
            Text(entry.level.name, color = levelColor, style = MaterialTheme.typography.labelMedium)
        }
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}
