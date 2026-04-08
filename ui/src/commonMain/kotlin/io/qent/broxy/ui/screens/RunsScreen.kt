@file:Suppress("FunctionNaming", "LongMethod", "TooManyFunctions")

package io.qent.broxy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import io.qent.broxy.ui.adapter.models.UiAgentRunStatus
import io.qent.broxy.ui.adapter.models.UiAgentRunTrigger
import io.qent.broxy.ui.adapter.models.UiAgentRuntime
import io.qent.broxy.ui.adapter.models.UiRunActionEntry
import io.qent.broxy.ui.adapter.models.UiRunActionType
import io.qent.broxy.ui.adapter.models.UiRunDetails
import io.qent.broxy.ui.adapter.models.UiRunDialogueEntry
import io.qent.broxy.ui.adapter.models.UiRunDialogueRole
import io.qent.broxy.ui.adapter.models.UiRunSummary
import io.qent.broxy.ui.adapter.store.AppStore
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.components.AppVerticalScrollbar
import io.qent.broxy.ui.components.SettingsLikeItem
import io.qent.broxy.ui.strings.AppStrings
import io.qent.broxy.ui.strings.LocalStrings
import io.qent.broxy.ui.theme.AppTheme
import io.qent.broxy.ui.viewmodels.AppState
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

@Composable
fun RunsScreen(
    ui: UIState,
    state: AppState,
    store: AppStore,
) {
    val selectedRunId = state.runDetailsId.value
    if (selectedRunId != null) {
        RunDetailsScreen(
            runId = selectedRunId,
            ui = ui,
            store = store,
            onBack = { state.runDetailsId.value = null },
        )
        return
    }

    RunsListScreen(
        ui = ui,
        onOpenRun = { runId -> state.runDetailsId.value = runId },
    )
}

@Composable
private fun RunsListScreen(
    ui: UIState,
    onOpenRun: (String) -> Unit,
) {
    val strings = LocalStrings.current
    val listState = rememberLazyListState()
    val runs = sortRunsByStartedAtDesc((ui as? UIState.Ready)?.runs.orEmpty())

    Box(modifier = Modifier.fillMaxSize().padding(horizontal = AppTheme.spacing.md)) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
        ) {
            Spacer(Modifier.height(AppTheme.spacing.xs))
            Text(
                text = strings.runsTitle,
                style = MaterialTheme.typography.titleLarge,
            )

            when (ui) {
                is UIState.Loading -> {
                    Text(strings.loading, style = MaterialTheme.typography.bodyMedium)
                }
                is UIState.Error -> {
                    Text(strings.errorMessage(ui.message), style = MaterialTheme.typography.bodyMedium)
                }
                is UIState.Ready -> {
                    if (runs.isEmpty()) {
                        RunsEmptyState(
                            title = strings.runsEmptyTitle,
                            subtitle = strings.runsEmptySubtitle,
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f, fill = true),
                            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
                            contentPadding = PaddingValues(bottom = AppTheme.spacing.lg),
                        ) {
                            items(runs, key = { it.runId }) { run ->
                                RunSummaryItem(
                                    run = run,
                                    onClick = { onOpenRun(run.runId) },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (runs.isNotEmpty()) {
            AppVerticalScrollbar(
                listState = listState,
                modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .offset(x = AppTheme.spacing.md - AppTheme.strokeWidths.hairline),
            )
        }
    }
}

@Composable
private fun RunDetailsScreen(
    runId: String,
    ui: UIState,
    store: AppStore,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    val listState = rememberLazyListState()
    val fallbackSummary = (ui as? UIState.Ready)?.runs?.firstOrNull { it.runId == runId }
    val detailsState by produceState<RunDetailsLoadState>(initialValue = RunDetailsLoadState.Loading, runId) {
        value = RunDetailsLoadState.Loading
        value =
            store
                .loadRunDetails(runId)
                .fold(
                    onSuccess = { RunDetailsLoadState.Ready(it) },
                    onFailure = { RunDetailsLoadState.Error(it.message ?: strings.runNotFound) },
                )
    }
    val title =
        when (val state = detailsState) {
            is RunDetailsLoadState.Ready -> strings.runsDetailsTitle(state.details.summary.agentName)
            else -> strings.runsDetailsTitle(fallbackSummary?.agentName ?: strings.unavailable)
        }

    Box(modifier = Modifier.fillMaxSize().padding(horizontal = AppTheme.spacing.md)) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
        ) {
            Spacer(Modifier.height(AppTheme.spacing.xs))
            RunsHeaderRow(
                title = title,
                onBack = onBack,
            )
            when (val state = detailsState) {
                RunDetailsLoadState.Loading -> {
                    Text(strings.loading, style = MaterialTheme.typography.bodyMedium)
                }
                is RunDetailsLoadState.Error -> {
                    Text(
                        text = strings.errorMessage(state.message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                is RunDetailsLoadState.Ready -> {
                    val details = state.details
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f, fill = true),
                        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
                        contentPadding = PaddingValues(bottom = AppTheme.spacing.lg),
                    ) {
                        item {
                            RunSummaryItem(run = details.summary, onClick = null)
                        }
                        item {
                            Text(
                                text = strings.runsDialogueSectionTitle,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        if (details.dialogue.isEmpty()) {
                            item {
                                Text(
                                    text = strings.runsDialogueEmpty,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            items(details.dialogue) { entry ->
                                DialogueEntryItem(entry)
                            }
                        }
                        item {
                            Text(
                                text = strings.runsActionsSectionTitle,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        if (details.actions.isEmpty()) {
                            item {
                                Text(
                                    text = strings.runsActionsEmpty,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            items(details.actions) { entry ->
                                ActionEntryItem(entry)
                            }
                        }
                    }
                }
            }
        }

        if (detailsState is RunDetailsLoadState.Ready) {
            AppVerticalScrollbar(
                listState = listState,
                modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .offset(x = AppTheme.spacing.md - AppTheme.strokeWidths.hairline),
            )
        }
    }
}

@Composable
private fun RunsHeaderRow(
    title: String,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = strings.back)
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RunSummaryItem(
    run: UiRunSummary,
    onClick: (() -> Unit)?,
) {
    val strings = LocalStrings.current
    val statusLabel = statusLabel(run.status, strings)
    val triggerLabel = triggerLabel(run.trigger, strings)
    val runtimeLabel = runtimeLabel(run.runtime, strings)
    val statusColor =
        when (run.status) {
            UiAgentRunStatus.SUCCESS -> AppTheme.extendedColors.success
            UiAgentRunStatus.FAILED -> MaterialTheme.colorScheme.error
            UiAgentRunStatus.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
        }

    SettingsLikeItem(
        title = "${run.agentName}${strings.separatorDot}$statusLabel${strings.separatorDot}$runtimeLabel",
        titleColor = statusColor,
        onClick = onClick ?: {},
        descriptionContent = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xxs),
            ) {
                Text(
                    text = "${strings.runHistoryPromptPrefix} ${run.prompt}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "$triggerLabel${strings.separatorDot}${formatRunTimestamp(run.startedAtEpochMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                run.response?.takeIf { it.isNotBlank() }?.let { response ->
                    Text(
                        text = "${strings.runHistoryResponsePrefix} $response",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                run.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
                    Text(
                        text = "${strings.runHistoryErrorPrefix} $error",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
    ) {}
}

@Composable
private fun DialogueEntryItem(entry: UiRunDialogueEntry) {
    SettingsLikeItem(
        title = "${entry.role.readableLabel()} · ${formatRunTimestamp(entry.timestampEpochMillis)}",
        descriptionContent = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xxs),
            ) {
                val meta = buildRunMeta(step = entry.step, serverId = entry.serverId, toolName = entry.toolName)
                if (meta != null) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = entry.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
    ) {}
}

@Composable
private fun ActionEntryItem(entry: UiRunActionEntry) {
    SettingsLikeItem(
        title = "${entry.type.readableLabel()} · ${formatRunTimestamp(entry.timestampEpochMillis)}",
        descriptionContent = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xxs),
            ) {
                val meta = buildRunMeta(step = entry.step, serverId = entry.serverId, toolName = entry.toolName)
                if (meta != null) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                entry.message?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                entry.requestPayload?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = "Request: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                entry.responsePayload?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = "Response: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                entry.errorMessage?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = "Error: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
    ) {}
}

private fun statusLabel(
    status: UiAgentRunStatus,
    strings: AppStrings,
): String =
    when (status) {
        UiAgentRunStatus.SUCCESS -> strings.runHistoryStatusSuccess
        UiAgentRunStatus.FAILED -> strings.runHistoryStatusFailed
        UiAgentRunStatus.SKIPPED -> strings.runHistoryStatusSkipped
    }

private fun triggerLabel(
    trigger: UiAgentRunTrigger,
    strings: AppStrings,
): String =
    when (trigger) {
        UiAgentRunTrigger.MANUAL -> strings.runHistoryTriggerManual
        UiAgentRunTrigger.SCHEDULED -> strings.runHistoryTriggerScheduled
    }

private fun runtimeLabel(
    runtime: UiAgentRuntime,
    strings: AppStrings,
): String =
    when (runtime) {
        UiAgentRuntime.LANGCHAIN -> strings.runtimeLangChain
        UiAgentRuntime.CODEX_CLI -> strings.runtimeCodex
    }

private fun UiRunDialogueRole.readableLabel(): String =
    when (this) {
        UiRunDialogueRole.SYSTEM -> "System"
        UiRunDialogueRole.USER -> "User"
        UiRunDialogueRole.ASSISTANT -> "Assistant"
        UiRunDialogueRole.TOOL -> "Tool"
    }

private fun UiRunActionType.readableLabel(): String =
    when (this) {
        UiRunActionType.PREPARING_RUN -> "Preparing run"
        UiRunActionType.LOADING_CAPABILITIES -> "Loading capabilities"
        UiRunActionType.LLM_REQUEST -> "LLM request"
        UiRunActionType.LLM_THINKING -> "LLM thinking"
        UiRunActionType.LLM_RESPONSE_GENERATION -> "LLM response generation"
        UiRunActionType.TOOL_CALL -> "Tool call"
        UiRunActionType.TOOL_RESULT -> "Tool result"
        UiRunActionType.RUNTIME_EVENT -> "Runtime event"
    }

private fun buildRunMeta(
    step: Int?,
    serverId: String?,
    toolName: String?,
): String? {
    val parts = mutableListOf<String>()
    step?.let { parts += "Step $it" }
    if (!serverId.isNullOrBlank()) {
        parts += "Server: $serverId"
    }
    if (!toolName.isNullOrBlank()) {
        parts += "Tool: $toolName"
    }
    if (parts.isEmpty()) {
        return null
    }
    return parts.joinToString(" · ")
}

internal fun sortRunsByStartedAtDesc(runs: List<UiRunSummary>): List<UiRunSummary> =
    runs.sortedWith(
        compareByDescending<UiRunSummary> { it.startedAtEpochMillis }
            .thenByDescending { it.finishedAtEpochMillis }
            .thenBy { it.runId },
    )

private fun formatRunTimestamp(epochMillis: Long): String {
    val dateTime =
        Instant
            .fromEpochMilliseconds(epochMillis)
            .toLocalDateTime(TimeZone.currentSystemDefault())
    return buildString {
        append(dateTime.date)
        append(' ')
        append(dateTime.hour.toString().padStart(2, '0'))
        append(':')
        append(dateTime.minute.toString().padStart(2, '0'))
        append(':')
        append(dateTime.second.toString().padStart(2, '0'))
    }
}

private sealed interface RunDetailsLoadState {
    data object Loading : RunDetailsLoadState

    data class Error(
        val message: String,
    ) : RunDetailsLoadState

    data class Ready(
        val details: UiRunDetails,
    ) : RunDetailsLoadState
}

@Composable
private fun RunsEmptyState(
    title: String,
    subtitle: String,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(AppTheme.spacing.sm))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
