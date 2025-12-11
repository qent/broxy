package io.qent.broxy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.components.AppVerticalScrollbar
import io.qent.broxy.ui.components.rememberStableScrollbarAdapter
import io.qent.broxy.ui.adapter.models.UiLogEntry
import io.qent.broxy.ui.adapter.models.UiLogLevel
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.theme.AppTheme
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
    val scrollbarAdapter = rememberStableScrollbarAdapter(listState)
    val showScrollbar by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            layoutInfo.totalItemsCount > layoutInfo.visibleItemsInfo.size
        }
    }

    val activeLevelsState = remember {
        mutableStateOf(setOf(
            UiLogLevel.ERROR, UiLogLevel.WARN, UiLogLevel.INFO, UiLogLevel.DEBUG
        ))
    }
    val activeLevels = activeLevelsState.value
    val filteredLogs = remember(logs, activeLevels) { logs.filter { it.level in activeLevels } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppTheme.spacing.lg)
                    .padding(top = AppTheme.spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
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
                            .clip(AppTheme.shapes.chip)
                            .background(color.copy(alpha = 0.18f))
                            .alpha(if (isActive) 1f else 0.3f)
                            .clickable {
                                activeLevelsState.value = if (isActive) activeLevels - level else activeLevels + level
                            }
                            .padding(horizontal = AppTheme.spacing.sm, vertical = AppTheme.spacing.xxs),
                        color = color,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(all = AppTheme.spacing.md)
                    .padding(end = AppTheme.spacing.xl),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md)
            ) {
                items(filteredLogs) { entry ->
                    LogRow(entry)
                }
            }
        }

        if (filteredLogs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No logs for selected levels", style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (showScrollbar) {
            AppVerticalScrollbar(
                adapter = scrollbarAdapter,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight(),
                canScroll = showScrollbar,
                isScrollInProgress = listState.isScrollInProgress
            )
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
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppTheme.shapes.item)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, AppTheme.shapes.item)
            .padding(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
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
                    .clip(AppTheme.shapes.chip)
                    .background(levelColor.copy(alpha = 0.1f))
                    .padding(horizontal = AppTheme.spacing.sm, vertical = AppTheme.spacing.xxs),
                color = levelColor,
                style = MaterialTheme.typography.labelSmall
            )
        }
        SelectionContainer {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
