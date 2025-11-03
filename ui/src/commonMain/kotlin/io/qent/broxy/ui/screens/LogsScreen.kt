package io.qent.broxy.ui.screens

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.UiLogEntry
import io.qent.broxy.ui.adapter.models.UiLogLevel
import io.qent.broxy.ui.adapter.store.UIState
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

    val listState = rememberLazyListState()

    val activeLevelsState = remember {
        mutableStateOf(setOf(
            UiLogLevel.ERROR, UiLogLevel.WARN, UiLogLevel.INFO, UiLogLevel.DEBUG
        ))
    }
    val activeLevels = activeLevelsState.value
    val filteredLogs = remember(logs, activeLevels) { logs.filter { it.level in activeLevels } }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 44.dp, bottom = 12.dp)
        ) {
            items(filteredLogs) { entry ->
                LogRow(entry)
            }
        }

        // Top pinned filter bar with level badges
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(UiLogLevel.ERROR, UiLogLevel.WARN, UiLogLevel.INFO, UiLogLevel.DEBUG).forEach { level ->
                val color = when (level) {
                    UiLogLevel.ERROR -> MaterialTheme.colorScheme.error
                    UiLogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                    UiLogLevel.DEBUG -> MaterialTheme.colorScheme.outline
                    UiLogLevel.INFO -> MaterialTheme.colorScheme.primary
                }
                val isActive = level in activeLevels
                Text(
                    level.name,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(color.copy(alpha = 0.18f))
                        .alpha(if (isActive) 1f else 0.3f)
                        .clickable {
                            activeLevelsState.value = if (isActive) activeLevels - level else activeLevels + level
                        }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    color = color,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        if (filteredLogs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No logs for selected levels", style = MaterialTheme.typography.bodyMedium)
            }
        }

        // Track background to visually indicate draggable area
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(12.dp)
                .padding(end = 2.dp)
                .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
        )

        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(12.dp)
                .padding(end = 2.dp)
        )
    }
}

@Composable
private fun LogRow(entry: UiLogEntry) {
    val clipboardManager = LocalClipboardManager.current
    val formattedTime = remember(entry.timestampMillis) {
        val instant = Instant.fromEpochMilliseconds(entry.timestampMillis)
        val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        "%02d:%02d:%02d".format(local.hour, local.minute, local.second)
    }
    val message = remember(entry) {
        if (entry.throwableMessage.isNullOrBlank()) entry.message
        else "${entry.message} (${entry.throwableMessage})"
    }
    val copyText = remember(entry, formattedTime) {
        "[" + formattedTime + "] " + entry.level.name + ": " + message
    }

    val levelColor = when (entry.level) {
        UiLogLevel.ERROR -> MaterialTheme.colorScheme.error
        UiLogLevel.WARN -> MaterialTheme.colorScheme.tertiary
        UiLogLevel.DEBUG -> MaterialTheme.colorScheme.outline
        UiLogLevel.INFO -> MaterialTheme.colorScheme.primary
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .border(1.dp, levelColor.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                formattedTime,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                entry.level.name,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(levelColor.copy(alpha = 0.18f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                color = levelColor,
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                clipboardManager.setText(AnnotatedString(copyText))
            }) {
                Text(
                    "Copy",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        SelectionContainer {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
            )
        }
    }
}
