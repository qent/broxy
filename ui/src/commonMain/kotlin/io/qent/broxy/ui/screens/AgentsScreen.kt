@file:Suppress("FunctionNaming", "TooManyFunctions")

package io.qent.broxy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.qent.broxy.ui.adapter.models.UiAgent
import io.qent.broxy.ui.adapter.models.UiAgentOperation
import io.qent.broxy.ui.adapter.store.AppStore
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.components.AppDangerButton
import io.qent.broxy.ui.components.AppDialog
import io.qent.broxy.ui.components.AppSecondaryButton
import io.qent.broxy.ui.components.AppVerticalScrollbar
import io.qent.broxy.ui.components.CapabilitiesInlineSummary
import io.qent.broxy.ui.components.DeleteConfirmationDialog
import io.qent.broxy.ui.components.HighlightedText
import io.qent.broxy.ui.components.SearchField
import io.qent.broxy.ui.components.SearchFieldFabAlignedBottomPadding
import io.qent.broxy.ui.components.SettingsLikeItem
import io.qent.broxy.ui.components.dragReorderHandle
import io.qent.broxy.ui.components.moveItem
import io.qent.broxy.ui.components.rememberDragReorderState
import io.qent.broxy.ui.strings.AppStrings
import io.qent.broxy.ui.strings.LocalStrings
import io.qent.broxy.ui.theme.AppTheme
import io.qent.broxy.ui.viewmodels.AgentEditorState
import io.qent.broxy.ui.viewmodels.AppState
import kotlinx.coroutines.delay
import kotlin.random.Random
import kotlin.time.Clock

private const val RUNTIME_TICK_MILLIS = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
private const val STATUS_ROTATION_MIN_DELAY_MILLIS = 2_000L
private const val STATUS_ROTATION_MAX_DELAY_MILLIS = 4_000L
private const val DRAGGED_CARD_ELEVATION = 12f
private val AGENT_RUNTIME_BLOCK_WIDTH = 42.dp
private val REORDER_HANDLE_ICON_SIZE = 20.dp
private val REORDER_HANDLE_TOUCH_SIZE = 28.dp
private val REORDER_HANDLE_HORIZONTAL_PADDING = 2.dp
private val REORDER_HANDLE_VERTICAL_PADDING = 4.dp

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
fun AgentsScreen(
    ui: UIState,
    state: AppState,
    store: AppStore,
) {
    val strings = LocalStrings.current
    val generationState by store.agentGenerationState.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var pendingDeletion: UiAgent? by remember { mutableStateOf<UiAgent?>(null) }
    var pendingStop: UiAgent? by remember { mutableStateOf<UiAgent?>(null) }
    val editor = state.agentEditor.value
    val launchAgentId = state.agentLaunchId.value
    val viewingId = state.agentDetailsId.value
    val showGenerateMode = state.agentGenerateMode.value
    val listState = rememberLazyListState()
    val isAnyAgentRunning = (ui as? UIState.Ready)?.agents?.any { it.isRunning } == true
    val nowEpochMillis = rememberAgentRuntimeTicker(isAnyAgentRunning)

    LaunchedEffect(generationState.generatedAgentId) {
        val generatedId = generationState.generatedAgentId ?: return@LaunchedEffect
        applyGeneratedAgentCompletionNavigation(state, generatedId)
        store.acknowledgeAgentGenerationCompletion()
    }

    Box(modifier = Modifier.fillMaxSize().padding(horizontal = AppTheme.spacing.md)) {
        if (showGenerateMode) {
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(Modifier.height(1.dp))
                AgentGenerateScreen(
                    ui = ui,
                    store = store,
                    onBack = { state.agentGenerateMode.value = false },
                    onSkip = { applyGenerateSkipNavigation(state) },
                )
            }
            return@Box
        }

        if (editor != null) {
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(Modifier.height(1.dp))
                AgentEditorScreen(
                    ui = ui,
                    store = store,
                    editor = editor,
                    onClose = { state.agentEditor.value = null },
                )
            }
            return@Box
        }

        if (launchAgentId != null) {
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(Modifier.height(1.dp))
                AgentLaunchFormScreen(
                    ui = ui,
                    agentId = launchAgentId,
                    store = store,
                    onClose = { state.agentLaunchId.value = null },
                )
            }
            return@Box
        }

        if (viewingId != null) {
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(Modifier.height(1.dp))
                AgentDetailsScreen(
                    ui = ui,
                    store = store,
                    agentId = viewingId,
                    onClose = { state.agentDetailsId.value = null },
                )
            }
            return@Box
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
        ) {
            when (ui) {
                is UIState.Loading -> Text(strings.loading, style = MaterialTheme.typography.bodyMedium)
                is UIState.Error -> Text(strings.errorMessage(ui.message), style = MaterialTheme.typography.bodyMedium)
                is UIState.Ready -> {
                    val agents = ui.agents
                    if (agents.isEmpty()) {
                        EmptyState(
                            title = strings.agentsEmptyTitle,
                            subtitle = strings.agentsEmptySubtitle,
                        )
                    } else {
                        val currentAgentOrder = agents.map { it.id }
                        val orderedAgentIds =
                            remember(currentAgentOrder) {
                                mutableStateListOf<String>().apply { addAll(currentAgentOrder) }
                            }
                        val agentsById = remember(agents) { agents.associateBy { it.id } }
                        val trimmedQuery = query.trim()
                        val canReorder = trimmedQuery.isBlank()
                        val visibleAgentIds =
                            if (trimmedQuery.isBlank()) {
                                orderedAgentIds.toList()
                            } else {
                                orderedAgentIds.filter { agentId ->
                                    agentsById[agentId]?.name?.contains(trimmedQuery, ignoreCase = true) == true
                                }
                            }
                        val reorderState =
                            rememberDragReorderState(
                                keysProvider = { orderedAgentIds.toList() },
                                onMove = { from, to -> orderedAgentIds.moveItem(from, to) },
                                onDragStopped = {
                                    if (!canReorder) return@rememberDragReorderState
                                    val updatedOrder = orderedAgentIds.toList()
                                    if (updatedOrder != currentAgentOrder) {
                                        ui.intents.reorderAgents(updatedOrder)
                                    }
                                },
                            )
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f, fill = true),
                            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
                            contentPadding =
                                PaddingValues(
                                    top = AppTheme.spacing.lg,
                                    bottom = AppTheme.spacing.fab,
                                ),
                        ) {
                            itemsIndexed(visibleAgentIds, key = { _, agentId -> agentId }) { _, agentId ->
                                val agent = agentsById[agentId] ?: return@itemsIndexed
                                val isDragging = reorderState.isDragging(agent.id)
                                val cardModifier =
                                    Modifier
                                        .onSizeChanged { reorderState.updateItemHeight(agent.id, it.height) }
                                        .graphicsLayer {
                                            translationY = reorderState.offsetFor(agent.id)
                                            shadowElevation = if (isDragging) DRAGGED_CARD_ELEVATION else 0f
                                        }.zIndex(if (isDragging) 1f else 0f)
                                AgentCard(
                                    agent = agent,
                                    modifier = cardModifier,
                                    nowEpochMillis = nowEpochMillis,
                                    searchQuery = trimmedQuery,
                                    reorderHandle = {
                                        ReorderHandle(
                                            enabled = canReorder && orderedAgentIds.size > 1,
                                            contentDescription = strings.reorderContentDescription,
                                            modifier =
                                                Modifier.dragReorderHandle(
                                                    key = agent.id,
                                                    enabled = canReorder && orderedAgentIds.size > 1,
                                                    state = reorderState,
                                                ),
                                        )
                                    },
                                    onOpenDetails = {
                                        pendingDeletion = null
                                        pendingStop = null
                                        state.agentEditor.value = null
                                        state.agentLaunchId.value = null
                                        state.agentDetailsId.value = agent.id
                                    },
                                    onPrimaryAction = {
                                        state.agentDetailsId.value = null
                                        if (agent.isRunning) {
                                            state.agentLaunchId.value = null
                                            pendingStop = agent
                                        } else {
                                            pendingDeletion = null
                                            pendingStop = null
                                            state.agentEditor.value = null
                                            state.agentLaunchId.value = agent.id
                                        }
                                    },
                                    onEdit = {
                                        pendingDeletion = null
                                        pendingStop = null
                                        state.agentLaunchId.value = null
                                        state.agentDetailsId.value = null
                                        state.agentEditor.value = AgentEditorState.Edit(agent.id)
                                    },
                                    onDelete = {
                                        state.agentDetailsId.value = null
                                        pendingDeletion = agent
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        AppVerticalScrollbar(
            listState = listState,
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .offset(x = AppTheme.spacing.md - AppTheme.strokeWidths.hairline),
        )

        SearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = strings.searchAgents,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = SearchFieldFabAlignedBottomPadding),
        )

        val readyUi = ui as? UIState.Ready
        val toDelete = pendingDeletion
        if (readyUi != null && toDelete != null) {
            DeleteConfirmationDialog(
                title = strings.deleteAgentTitle,
                prompt = strings.deleteAgentPrompt(toDelete.name),
                description = strings.deleteAgentDescription,
                onConfirm = {
                    readyUi.intents.removeAgent(toDelete.id)
                    pendingDeletion = null
                },
                onDismiss = { pendingDeletion = null },
            )
        }

        val toStop = pendingStop
        if (readyUi != null && toStop != null) {
            AppDialog(
                title = strings.stopAgentTitle(toStop.name),
                onDismissRequest = { pendingStop = null },
                dismissButton = {
                    AppSecondaryButton(onClick = { pendingStop = null }) {
                        Text(strings.cancel)
                    }
                },
                confirmButton = {
                    AppDangerButton(
                        onClick = {
                            readyUi.intents.stopAgent(toStop.id)
                            pendingStop = null
                        },
                    ) {
                        Text(strings.stopAgentConfirm)
                    }
                },
            ) {
                Text(
                    text = strings.stopAgentWarning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun applyGeneratedAgentCompletionNavigation(
    state: AppState,
    generatedAgentId: String,
) {
    if (generatedAgentId.isBlank()) {
        return
    }
    state.agentGenerateMode.value = false
    state.agentLaunchId.value = null
    state.agentDetailsId.value = null
    state.agentEditor.value = AgentEditorState.Edit(generatedAgentId)
}

internal fun applyGenerateSkipNavigation(state: AppState) {
    state.agentGenerateMode.value = false
    state.agentLaunchId.value = null
    state.agentDetailsId.value = null
    state.agentEditor.value = AgentEditorState.Create
}

@Composable
@Suppress("LongMethod", "LongParameterList")
private fun AgentCard(
    agent: UiAgent,
    modifier: Modifier = Modifier,
    nowEpochMillis: Long,
    searchQuery: String,
    reorderHandle: (@Composable RowScope.() -> Unit)? = null,
    onOpenDetails: () -> Unit,
    onPrimaryAction: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val strings = LocalStrings.current
    val failedStatusLabel = resolveAgentFailedStatusLabel(agent, strings)
    val operationStatusLabel = resolveAgentOperationStatusLabel(agent.activeOperation, strings)
    val schedule = agent.schedule
    val statusColor =
        when {
            agent.isRunning -> AppTheme.extendedColors.success
            failedStatusLabel != null -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }

    SettingsLikeItem(
        title = agent.name,
        modifier = modifier,
        onClick = onOpenDetails,
        startControl = reorderHandle,
        contentPadding =
            PaddingValues(
                start = AppTheme.spacing.md,
                end = AppTheme.spacing.md,
                top = AppTheme.spacing.md,
                bottom = AppTheme.spacing.md,
            ),
        titleContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
            ) {
                HighlightedText(
                    text = agent.name,
                    query = searchQuery,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (agent.isRunning) {
                    val startedAt = agent.runningSinceEpochMillis ?: nowEpochMillis
                    val runtimeLabel = formatElapsedDuration(startedAt, nowEpochMillis)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
                    ) {
                        Text(
                            text = runtimeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            maxLines = 1,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(AGENT_RUNTIME_BLOCK_WIDTH),
                        )
                        Text(
                            text = operationStatusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else if (failedStatusLabel != null) {
                    Text(
                        text = failedStatusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        descriptionContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CapabilitiesInlineSummary(
                    toolsCount = agent.toolsCount,
                    promptsCount = agent.promptsCount,
                    resourcesCount = agent.resourcesCount,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    textStyle = MaterialTheme.typography.bodySmall,
                )
            }
        },
        supportingContent =
            schedule?.let {
                {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.height(14.dp),
                        )
                        Text(
                            text = " ${scheduleToHumanReadable(it.cron, strings)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
    ) {
        if (agent.isRunning) {
            IconButton(onClick = onPrimaryAction) {
                Icon(
                    Icons.Outlined.Stop,
                    contentDescription = strings.stopAgentContentDescription,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            IconButton(onClick = onPrimaryAction) {
                Icon(
                    if (schedule != null) Icons.Outlined.Schedule else Icons.Outlined.PlayArrow,
                    contentDescription =
                        if (schedule != null) {
                            strings.editScheduleContentDescription
                        } else {
                            strings.runAgentContentDescription
                        },
                    tint = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        IconButton(onClick = onEdit) {
            Icon(
                Icons.Outlined.Edit,
                contentDescription = strings.editContentDescription,
                tint = MaterialTheme.colorScheme.secondary,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = strings.deleteContentDescription,
                tint = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun ReorderHandle(
    enabled: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val tint =
        if (enabled) {
            MaterialTheme.colorScheme.secondary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        }
    Icon(
        imageVector = Icons.Outlined.DragIndicator,
        contentDescription = contentDescription,
        tint = tint,
        modifier =
            modifier
                .size(REORDER_HANDLE_TOUCH_SIZE)
                .padding(
                    horizontal = REORDER_HANDLE_HORIZONTAL_PADDING,
                    vertical = REORDER_HANDLE_VERTICAL_PADDING,
                ).size(REORDER_HANDLE_ICON_SIZE),
    )
}

@Composable
private fun resolveAgentOperationStatusLabel(
    operation: UiAgentOperation?,
    strings: AppStrings,
): String {
    val requestLabel =
        rememberRotatingStatusPhrase(
            phrases = strings.agentStatusLlmRequestVariants,
            active = operation is UiAgentOperation.LlmRequest,
        )
    val responseLabel =
        rememberRotatingStatusPhrase(
            phrases = strings.agentStatusLlmResponseGenerationVariants,
            active = operation is UiAgentOperation.LlmResponseGeneration,
        )
    val thinkingLabel =
        rememberRotatingStatusPhrase(
            phrases = strings.agentStatusLlmThinkingVariants,
            active = operation is UiAgentOperation.LlmThinking,
        )
    return when (operation) {
        null,
        UiAgentOperation.PreparingRun,
        -> strings.agentStatusPreparingRun
        UiAgentOperation.LoadingCapabilities -> strings.agentStatusLoadingCapabilities
        is UiAgentOperation.LlmRequest -> requestLabel
        is UiAgentOperation.LlmThinking -> thinkingLabel
        is UiAgentOperation.LlmResponseGeneration -> responseLabel
        is UiAgentOperation.ToolExecution ->
            strings.agentStatusToolExecution(
                toolName = operation.toolName,
                serverId = operation.serverId,
            )
    }
}

@Composable
private fun rememberRotatingStatusPhrase(
    phrases: List<String>,
    active: Boolean,
): String {
    val fallback = listOf("")
    val effectivePhrases = if (phrases.isEmpty()) fallback else phrases
    var currentIndex by remember(effectivePhrases) {
        mutableStateOf(Random.nextInt(effectivePhrases.size))
    }

    LaunchedEffect(active, effectivePhrases) {
        if (!active || effectivePhrases.size <= 1) {
            return@LaunchedEffect
        }
        while (true) {
            delay(
                Random.nextLong(
                    from = STATUS_ROTATION_MIN_DELAY_MILLIS,
                    until = STATUS_ROTATION_MAX_DELAY_MILLIS + 1,
                ),
            )
            var nextIndex = currentIndex
            while (nextIndex == currentIndex) {
                nextIndex = Random.nextInt(effectivePhrases.size)
            }
            currentIndex = nextIndex
        }
    }

    return effectivePhrases[currentIndex]
}

@Composable
private fun EmptyState(
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

@Composable
private fun rememberAgentRuntimeTicker(active: Boolean): Long {
    var nowEpochMillis by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            nowEpochMillis = Clock.System.now().toEpochMilliseconds()
            delay(RUNTIME_TICK_MILLIS)
        }
    }
    return nowEpochMillis
}

private fun formatElapsedDuration(
    startedAtEpochMillis: Long,
    nowEpochMillis: Long,
): String {
    val elapsedSeconds = ((nowEpochMillis - startedAtEpochMillis).coerceAtLeast(0L)) / RUNTIME_TICK_MILLIS
    val hours = (elapsedSeconds / SECONDS_PER_HOUR).toInt()
    val minutes = ((elapsedSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE).toInt()
    val seconds = (elapsedSeconds % SECONDS_PER_MINUTE).toInt()
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }
}
