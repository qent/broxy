@file:Suppress("FunctionNaming", "TooManyFunctions")

package io.qent.broxy.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.DEFAULT_UI_AGENT_WORKSPACE_PATH
import io.qent.broxy.ui.adapter.models.UiAgentCodexConfig
import io.qent.broxy.ui.adapter.models.UiAgentCodexReasoningEffort
import io.qent.broxy.ui.adapter.models.UiAgentFileSystemAccess
import io.qent.broxy.ui.adapter.models.UiAgentFileSystemSettings
import io.qent.broxy.ui.adapter.models.UiAgentLlmConfig
import io.qent.broxy.ui.adapter.models.UiAgentProviderSettings
import io.qent.broxy.ui.adapter.models.UiAgentRuntime
import io.qent.broxy.ui.adapter.models.UiLlmProvider
import io.qent.broxy.ui.adapter.store.AppStore
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.components.AppDangerButton
import io.qent.broxy.ui.components.AppPrimaryButton
import io.qent.broxy.ui.components.AppSecondaryButton
import io.qent.broxy.ui.components.AppVerticalScrollbar
import io.qent.broxy.ui.components.EditorHeaderRow
import io.qent.broxy.ui.components.SettingsLikeItem
import io.qent.broxy.ui.strings.AppStrings
import io.qent.broxy.ui.strings.LocalStrings
import io.qent.broxy.ui.theme.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val LAUNCH_FORM_SIDE_FIELD_WIDTH = 190.dp
private val LAUNCH_FORM_SPLIT_FIELD_SPACING = 14.dp
private val LAUNCH_FORM_TEMPERATURE_FIELD_WIDTH = SettingControlWidth / 2f
private val ACTION_ROW_HEIGHT = 40.dp

private val SCHEDULE_PATTERN_SELECTOR_WIDTH = SettingControlWidth * 2f
private const val SCHEDULE_PREVIEW_DEBOUNCE_MILLIS = 300L
private const val SCHEDULE_PREVIEW_LIMIT = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
fun AgentLaunchFormScreen(
    ui: UIState,
    agentId: String,
    store: AppStore,
    onClose: () -> Unit,
) {
    val strings = LocalStrings.current
    val readyUi = ui as? UIState.Ready
    val agent = readyUi?.agents?.firstOrNull { it.id == agentId }

    if (readyUi == null) {
        val message =
            when (ui) {
                UIState.Loading -> strings.loading
                is UIState.Error -> strings.errorMessage(ui.message)
                is UIState.Ready -> strings.agentNotFound
            }
        AgentLaunchUnavailableState(
            title = strings.runAgentTitle(agentId),
            message = message,
            onClose = onClose,
        )
        return
    }

    if (agent == null) {
        AgentLaunchUnavailableState(
            title = strings.runAgentTitle(agentId),
            message = strings.agentNotFound,
            onClose = onClose,
        )
        return
    }

    val scope = rememberCoroutineScope()
    val providerSettings = readyUi.agentProviderSettings
    val schedule = agent.schedule
    val persistedManualDefaults = agent.manualLaunchDefaults

    val initialManualPrompt =
        remember(agent.id, persistedManualDefaults?.prompt) {
            persistedManualDefaults?.prompt.orEmpty()
        }
    val initialSchedulePrompt =
        remember(agent.id, schedule?.prompt, initialManualPrompt) {
            schedule?.prompt ?: initialManualPrompt
        }
    val initialLlm =
        remember(agent.id, schedule?.llm, persistedManualDefaults?.llm, providerSettings.modelCache) {
            schedule?.llm
                ?: persistedManualDefaults?.llm
                ?: UiAgentLlmConfig(
                    provider = UiLlmProvider.OPENAI,
                    model = defaultModelForProvider(UiLlmProvider.OPENAI, providerSettings),
                    temperature = 0.2,
                )
        }
    val initialRuntime =
        remember(agent.id, schedule?.runtime, persistedManualDefaults?.runtime, providerSettings.enableCodexProvider) {
            val preferred = schedule?.runtime ?: persistedManualDefaults?.runtime ?: UiAgentRuntime.LANGCHAIN
            if (!providerSettings.enableCodexProvider && preferred == UiAgentRuntime.CODEX_CLI) {
                UiAgentRuntime.LANGCHAIN
            } else {
                preferred
            }
        }
    val initialCodex =
        remember(agent.id, schedule?.codex, persistedManualDefaults?.codex) {
            schedule?.codex ?: persistedManualDefaults?.codex ?: UiAgentCodexConfig()
        }
    val initialFileSystem =
        remember(agent.id, schedule?.fileSystem, persistedManualDefaults?.fileSystem) {
            schedule?.fileSystem ?: persistedManualDefaults?.fileSystem ?: UiAgentFileSystemSettings()
        }

    val parsedScheduleForm =
        remember(agent.id, schedule?.cron) {
            schedule?.cron?.let(::scheduleFormStateFromCronOrNull)
        }

    var runOnSchedule by remember(agent.id, schedule?.cron) {
        mutableStateOf(schedule != null)
    }
    var manualPrompt by remember(agent.id, initialManualPrompt) {
        mutableStateOf(initialManualPrompt)
    }
    var schedulePrompt by remember(agent.id, initialSchedulePrompt) {
        mutableStateOf(initialSchedulePrompt)
    }
    var scheduleForm by remember(agent.id, schedule?.cron, parsedScheduleForm) {
        mutableStateOf(parsedScheduleForm ?: ScheduleFormState())
    }
    var useAdvancedCron by remember(agent.id, schedule?.cron, parsedScheduleForm) {
        mutableStateOf(schedule?.cron != null && parsedScheduleForm == null)
    }
    var advancedCronInput by remember(agent.id, schedule?.cron) {
        mutableStateOf(schedule?.cron.orEmpty())
    }

    var provider by remember(agent.id, initialLlm.provider) {
        mutableStateOf(initialLlm.provider)
    }
    var runtime by remember(agent.id, initialRuntime) {
        mutableStateOf(initialRuntime)
    }
    var model by remember(agent.id, initialLlm.model) {
        mutableStateOf(initialLlm.model)
    }
    var codexModel by remember(agent.id, initialCodex.model) {
        mutableStateOf(initialCodex.model)
    }
    var codexReasoningEffort by remember(agent.id, initialCodex.reasoningEffort) {
        mutableStateOf(initialCodex.reasoningEffort)
    }
    var codexWebSearch by remember(agent.id, initialCodex.webSearch) {
        mutableStateOf(initialCodex.webSearch)
    }
    var temperatureInput by remember(agent.id, initialLlm.temperature) {
        mutableStateOf(initialLlm.temperature.toString())
    }
    var workspacePath by remember(agent.id, initialFileSystem.path) {
        mutableStateOf(initialFileSystem.path)
    }
    var fileSystemAccess by remember(agent.id, initialFileSystem.access) {
        mutableStateOf(initialFileSystem.access)
    }

    var providerExpanded by remember(agent.id) { mutableStateOf(false) }
    var runtimeExpanded by remember(agent.id) { mutableStateOf(false) }
    var modelExpanded by remember(agent.id) { mutableStateOf(false) }
    var codexModelExpanded by remember(agent.id) { mutableStateOf(false) }
    var codexReasoningExpanded by remember(agent.id) { mutableStateOf(false) }
    var fileSystemAccessExpanded by remember(agent.id) { mutableStateOf(false) }
    var schedulePatternExpanded by remember(agent.id) { mutableStateOf(false) }

    var models by remember(agent.id, provider) {
        mutableStateOf(providerSettings.modelsFor(provider))
    }
    var codexModels by remember(agent.id) {
        mutableStateOf(providerSettings.modelCache.codex)
    }
    var loadingModels by remember(agent.id, provider) { mutableStateOf(false) }
    var modelLoadError by remember(agent.id, provider) { mutableStateOf<String?>(null) }
    var loadingCodexModels by remember(agent.id) { mutableStateOf(false) }
    var codexModelLoadError by remember(agent.id) { mutableStateOf<String?>(null) }
    var isInitialProviderSync by remember(agent.id) { mutableStateOf(false) }
    var workspacePickerInProgress by remember(agent.id) { mutableStateOf(false) }
    var workspacePathError by remember(agent.id) { mutableStateOf<String?>(null) }

    var previewLoading by remember(agent.id) { mutableStateOf(false) }
    var previewError by remember(agent.id) { mutableStateOf<String?>(null) }
    var previewRuns by remember(agent.id) { mutableStateOf<List<Long>>(emptyList()) }

    LaunchedEffect(agent.id, providerSettings.enableCodexProvider) {
        if (!providerSettings.enableCodexProvider && runtime == UiAgentRuntime.CODEX_CLI) {
            runtime = UiAgentRuntime.LANGCHAIN
        }
    }

    fun openWorkspacePicker() {
        if (workspacePickerInProgress) {
            return
        }
        scope.launch {
            workspacePickerInProgress = true
            try {
                val currentWorkspacePath = workspacePath.trim().ifBlank { DEFAULT_UI_AGENT_WORKSPACE_PATH }
                val pickResult = store.pickAgentWorkspaceDirectory(currentWorkspacePath)
                val pickedPath = pickResult.getOrNull()?.trim().orEmpty()
                if (pickedPath.isNotBlank()) {
                    workspacePath = pickedPath
                }
            } finally {
                workspacePickerInProgress = false
            }
        }
    }

    LaunchedEffect(agent.id, workspacePath) {
        val observedInput = workspacePath
        val normalizedInput = observedInput.trim().ifBlank { DEFAULT_UI_AGENT_WORKSPACE_PATH }
        if (normalizedInput == DEFAULT_UI_AGENT_WORKSPACE_PATH) {
            workspacePathError = null
            return@LaunchedEffect
        }

        val exists = store.agentWorkspaceDirectoryExists(normalizedInput)
        if (workspacePath != observedInput) {
            return@LaunchedEffect
        }
        workspacePathError =
            if (exists) {
                null
            } else {
                strings.workspaceDirectoryMissing(normalizedInput)
            }
    }

    suspend fun loadModels(
        selectedProvider: UiLlmProvider,
        forceRefresh: Boolean,
    ) {
        loadingModels = true
        modelLoadError = null
        val result = store.listProviderModels(selectedProvider, forceRefresh)
        loadingModels = false
        if (provider != selectedProvider) {
            return
        }
        if (result.isSuccess) {
            val loaded = result.getOrThrow()
            models = loaded
            if (model.trim().isBlank()) {
                model =
                    loaded
                        .firstOrNull()
                        .orEmpty()
                        .ifBlank {
                            defaultModelForProvider(selectedProvider, providerSettings)
                        }
            }
        } else {
            modelLoadError = result.exceptionOrNull()?.message
            if (models.isEmpty()) {
                models = providerSettings.modelsFor(selectedProvider)
            }
            if (model.trim().isBlank()) {
                model =
                    models
                        .firstOrNull()
                        .orEmpty()
                        .ifBlank {
                            defaultModelForProvider(selectedProvider, providerSettings)
                        }
            }
        }
    }

    suspend fun loadCodexModels(forceRefresh: Boolean) {
        loadingCodexModels = true
        codexModelLoadError = null
        val result = store.listCodexModels(forceRefresh)
        loadingCodexModels = false
        if (result.isSuccess) {
            val loaded = result.getOrThrow()
            codexModels = loaded
            if (codexModel.trim().isBlank()) {
                codexModel =
                    loaded
                        .firstOrNull()
                        .orEmpty()
                        .ifBlank { initialCodex.model }
            }
        } else {
            codexModelLoadError = result.exceptionOrNull()?.message
            if (codexModels.isEmpty()) {
                codexModels = providerSettings.modelCache.codex
            }
            if (codexModel.trim().isBlank()) {
                codexModel =
                    codexModels
                        .firstOrNull()
                        .orEmpty()
                        .ifBlank { initialCodex.model }
            }
        }
    }

    LaunchedEffect(agent.id, provider) {
        val cachedModels = providerSettings.modelsFor(provider)
        models = cachedModels

        if (!isInitialProviderSync) {
            isInitialProviderSync = true
            if (model.trim().isBlank()) {
                model =
                    cachedModels
                        .firstOrNull()
                        .orEmpty()
                        .ifBlank {
                            defaultModelForProvider(provider, providerSettings)
                        }
            }
            if (cachedModels.isEmpty()) {
                loadModels(selectedProvider = provider, forceRefresh = false)
            }
            return@LaunchedEffect
        }

        model =
            cachedModels
                .firstOrNull()
                .orEmpty()
                .ifBlank {
                    defaultModelForProvider(provider, providerSettings)
                }
        modelExpanded = false
        loadModels(selectedProvider = provider, forceRefresh = false)
    }

    LaunchedEffect(agent.id, runtime) {
        if (runtime != UiAgentRuntime.CODEX_CLI) {
            codexModelExpanded = false
            return@LaunchedEffect
        }
        codexModels = providerSettings.modelCache.codex
        loadCodexModels(forceRefresh = false)
    }

    val displayedPrompt = if (runOnSchedule) schedulePrompt else manualPrompt
    val normalizedPrompt = displayedPrompt.trim()
    val normalizedModel = model.trim()
    val normalizedCodexModel = codexModel.trim()
    val normalizedWorkspacePath = workspacePath.trim().ifBlank { DEFAULT_UI_AGENT_WORKSPACE_PATH }
    val normalizedTemperature = temperatureInput.trim().toDoubleOrNull()
    val normalizedCodexConfig =
        UiAgentCodexConfig(
            model = normalizedCodexModel,
            reasoningEffort = codexReasoningEffort,
            webSearch = codexWebSearch,
        )
    val effectiveFileSystemAccess = normalizeFileSystemAccessForRuntime(runtime = runtime, access = fileSystemAccess)
    val normalizedFileSystem =
        UiAgentFileSystemSettings(
            path = normalizedWorkspacePath,
            access = effectiveFileSystemAccess,
        )
    val fileSystemAccessOptions = availableFileSystemAccessesForRuntime(runtime)

    val scheduleInputError =
        when {
            !runOnSchedule -> null
            !useAdvancedCron && !scheduleForm.isValid() -> strings.scheduleInvalidConfiguration
            else -> null
        }
    val normalizedCron =
        when {
            !runOnSchedule -> null
            useAdvancedCron -> advancedCronInput.trim().takeIf { it.isNotBlank() }
            else -> scheduleForm.toCron().takeIf { scheduleForm.isValid() }
        }

    LaunchedEffect(agent.id, runOnSchedule, useAdvancedCron, advancedCronInput, scheduleForm) {
        if (!runOnSchedule) {
            previewLoading = false
            previewError = null
            previewRuns = emptyList()
            return@LaunchedEffect
        }
        if (scheduleInputError != null) {
            previewLoading = false
            previewError = scheduleInputError
            previewRuns = emptyList()
            return@LaunchedEffect
        }
        val cronForPreview = normalizedCron
        if (cronForPreview == null) {
            previewLoading = false
            previewError = strings.scheduleInvalidCron
            previewRuns = emptyList()
            return@LaunchedEffect
        }

        delay(SCHEDULE_PREVIEW_DEBOUNCE_MILLIS)
        previewLoading = true
        val previewResult = store.previewAgentSchedule(cron = cronForPreview, limit = SCHEDULE_PREVIEW_LIMIT)
        previewLoading = false
        if (previewResult.isSuccess) {
            previewRuns = previewResult.getOrThrow().nextRunsEpochMillis
            previewError = null
        } else {
            previewRuns = emptyList()
            previewError = previewResult.exceptionOrNull()?.message ?: strings.scheduleInvalidCron
        }
    }

    val scheduleReady =
        !runOnSchedule ||
            (
                scheduleInputError == null &&
                    normalizedCron != null &&
                    !previewLoading &&
                    previewError == null
            )
    val canSubmit =
        normalizedPrompt.isNotBlank() &&
            (
                if (runtime == UiAgentRuntime.LANGCHAIN) {
                    normalizedModel.isNotBlank() && normalizedTemperature != null
                } else {
                    providerSettings.enableCodexProvider && normalizedCodexModel.isNotBlank()
                }
            ) &&
            scheduleReady

    val scrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
        ) {
            Spacer(Modifier.height(AppTheme.spacing.xs))

            EditorHeaderRow(
                title = strings.runAgentTitle(agent.name),
                onBack = onClose,
                actions = {
                    AppSecondaryButton(
                        onClick = onClose,
                        modifier = Modifier.height(ACTION_ROW_HEIGHT),
                    ) {
                        Text(strings.cancel, style = MaterialTheme.typography.labelSmall)
                    }
                    AppPrimaryButton(
                        onClick = {
                            val llm =
                                UiAgentLlmConfig(
                                    provider = provider,
                                    model = normalizedModel,
                                    temperature = normalizedTemperature ?: 0.2,
                                )
                            readyUi.intents.runAgent(
                                id = agent.id,
                                prompt = normalizedPrompt,
                                runtime = runtime,
                                llm = llm,
                                codex = if (runtime == UiAgentRuntime.CODEX_CLI) normalizedCodexConfig else null,
                                fileSystem = normalizedFileSystem,
                                cron = normalizedCron,
                                clearExistingScheduleBeforeRun = !runOnSchedule && schedule != null,
                            )
                            onClose()
                        },
                        enabled = canSubmit,
                        modifier = Modifier.height(ACTION_ROW_HEIGHT),
                    ) {
                        Text(
                            if (runOnSchedule) strings.scheduleAction else strings.launchAction,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
            )

            OutlinedTextField(
                value = displayedPrompt,
                onValueChange = { next ->
                    if (runOnSchedule) {
                        schedulePrompt = next
                    } else {
                        manualPrompt = next
                    }
                },
                label = { Text(strings.promptLabel) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 8,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(LAUNCH_FORM_SPLIT_FIELD_SPACING),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = workspacePath,
                        onValueChange = { workspacePath = it },
                        label = { Text(strings.workspaceLabel) },
                        modifier = Modifier.fillMaxWidth(),
                        isError = workspacePathError != null,
                        trailingIcon = {
                            IconButton(
                                onClick = ::openWorkspacePicker,
                                enabled = !workspacePickerInProgress,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.FolderOpen,
                                    contentDescription = strings.openFolder,
                                    tint = MaterialTheme.colorScheme.secondary,
                                )
                            }
                        },
                        singleLine = true,
                        supportingText = {
                            val error = workspacePathError
                            if (error != null) {
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        },
                    )
                }
                ExposedDropdownMenuBox(
                    expanded = fileSystemAccessExpanded,
                    onExpandedChange = { fileSystemAccessExpanded = !fileSystemAccessExpanded },
                    modifier = Modifier.width(LAUNCH_FORM_SIDE_FIELD_WIDTH),
                ) {
                    val fieldShape = dropdownFieldShape(fileSystemAccessExpanded)
                    val menuShape = dropdownMenuShape(fileSystemAccessExpanded)
                    OutlinedTextField(
                        value =
                            fileSystemAccessLabel(
                                access = effectiveFileSystemAccess,
                                strings = strings,
                            ),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(strings.filesystemAccessLabel) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = fileSystemAccessExpanded)
                        },
                        shape = fieldShape,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .menuAnchor(
                                    type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                                    enabled = true,
                                ),
                        singleLine = true,
                    )
                    ExposedDropdownMenu(
                        expanded = fileSystemAccessExpanded,
                        onDismissRequest = { fileSystemAccessExpanded = false },
                        modifier =
                            Modifier
                                .background(color = MaterialTheme.colorScheme.surface, shape = menuShape)
                                .border(
                                    BorderStroke(AppTheme.strokeWidths.thin, MaterialTheme.colorScheme.outline),
                                    menuShape,
                                ),
                    ) {
                        fileSystemAccessOptions.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        fileSystemAccessLabel(option, strings),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                },
                                contentPadding =
                                    PaddingValues(
                                        horizontal = AppTheme.spacing.md,
                                        vertical = AppTheme.spacing.xxs,
                                    ),
                                onClick = {
                                    fileSystemAccess = option
                                    fileSystemAccessExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            SettingsLikeItem(
                title = strings.runtimeLabel,
                descriptionContent = {},
                contentPadding =
                    PaddingValues(
                        start = AppTheme.spacing.md + AppTheme.spacing.sm,
                        end = AppTheme.spacing.md,
                        top = AppTheme.spacing.md,
                        bottom = AppTheme.spacing.md,
                    ),
                titleContent = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
                        ) {
                            Text(
                                text = strings.runtimeLabel,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = strings.runtimeHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Box(modifier = Modifier.width(SCHEDULE_PATTERN_SELECTOR_WIDTH)) {
                            ExposedDropdownMenuBox(
                                expanded = runtimeExpanded,
                                onExpandedChange = {
                                    if (providerSettings.enableCodexProvider) {
                                        runtimeExpanded = !runtimeExpanded
                                    }
                                },
                            ) {
                                val fieldShape = dropdownFieldShape(runtimeExpanded)
                                val menuShape = dropdownMenuShape(runtimeExpanded)
                                SchedulePatternDropdownField(
                                    text =
                                        when (runtime) {
                                            UiAgentRuntime.LANGCHAIN -> strings.runtimeLangChain
                                            UiAgentRuntime.CODEX_CLI -> strings.runtimeCodex
                                        },
                                    expanded = runtimeExpanded,
                                    shape = fieldShape,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .menuAnchor(
                                                type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                                                enabled = providerSettings.enableCodexProvider,
                                            ),
                                )
                                ExposedDropdownMenu(
                                    expanded = runtimeExpanded,
                                    onDismissRequest = { runtimeExpanded = false },
                                    modifier =
                                        Modifier
                                            .background(color = MaterialTheme.colorScheme.surface, shape = menuShape)
                                            .border(
                                                BorderStroke(
                                                    AppTheme.strokeWidths.thin,
                                                    MaterialTheme.colorScheme.outline,
                                                ),
                                                menuShape,
                                            ),
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                strings.runtimeLangChain,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        },
                                        contentPadding =
                                            PaddingValues(
                                                horizontal = AppTheme.spacing.md,
                                                vertical = AppTheme.spacing.xxs,
                                            ),
                                        onClick = {
                                            runtime = UiAgentRuntime.LANGCHAIN
                                            runtimeExpanded = false
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                strings.runtimeCodex,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        },
                                        enabled = providerSettings.enableCodexProvider,
                                        contentPadding =
                                            PaddingValues(
                                                horizontal = AppTheme.spacing.md,
                                                vertical = AppTheme.spacing.xxs,
                                            ),
                                        onClick = {
                                            runtime = UiAgentRuntime.CODEX_CLI
                                            runtimeExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                supportingContent = {
                    if (!providerSettings.enableCodexProvider) {
                        Text(
                            text = strings.codexProviderDisabledHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = AppTheme.spacing.sm),
                        )
                    }
                },
                control = {},
            )
            if (runtime == UiAgentRuntime.CODEX_CLI) {
                SettingsLikeItem(
                    title = strings.runtimeCodex,
                    descriptionContent = {},
                    contentPadding =
                        PaddingValues(
                            start = AppTheme.spacing.md + AppTheme.spacing.sm,
                            end = AppTheme.spacing.md,
                            top = AppTheme.spacing.md,
                            bottom = AppTheme.spacing.md,
                        ),
                    titleContent = {
                        Text(
                            text = strings.runtimeCodex,
                            style = MaterialTheme.typography.titleSmall,
                        )
                    },
                    supportingContent = {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = AppTheme.spacing.sm),
                            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = strings.modelLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            ExposedDropdownMenuBox(
                                expanded = codexModelExpanded,
                                onExpandedChange = { expanded ->
                                    codexModelExpanded = expanded && codexModels.isNotEmpty()
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Surface(
                                    modifier =
                                        Modifier
                                            .height(32.dp)
                                            .fillMaxWidth()
                                            .menuAnchor(
                                                type = ExposedDropdownMenuAnchorType.PrimaryEditable,
                                                enabled = false,
                                            ),
                                    shape = AppTheme.shapes.input,
                                    color = MaterialTheme.colorScheme.surface,
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                    border =
                                        BorderStroke(
                                            AppTheme.strokeWidths.thin,
                                            MaterialTheme.colorScheme.outline,
                                        ),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxSize().padding(horizontal = AppTheme.spacing.md),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        BasicTextField(
                                            value = codexModel,
                                            onValueChange = { codexModel = it },
                                            modifier =
                                                Modifier
                                                    .weight(1f)
                                                    .onFocusChanged { focusState ->
                                                        if (focusState.isFocused) {
                                                            codexModelExpanded = false
                                                        }
                                                    },
                                            textStyle =
                                                MaterialTheme.typography.bodySmall.copy(
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                ),
                                            singleLine = true,
                                            decorationBox = { innerTextField -> innerTextField() },
                                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(
                                                onClick = {
                                                    codexModelExpanded = false
                                                    scope.launch {
                                                        loadCodexModels(forceRefresh = true)
                                                    }
                                                },
                                                enabled = !loadingCodexModels,
                                                modifier = Modifier.size(24.dp),
                                            ) {
                                                if (loadingCodexModels) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(14.dp),
                                                        strokeWidth = 1.5.dp,
                                                    )
                                                } else {
                                                    Icon(
                                                        imageVector = Icons.Outlined.Refresh,
                                                        contentDescription = strings.refreshContentDescription,
                                                        tint = MaterialTheme.colorScheme.secondary,
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                }
                                            }
                                            Box(
                                                modifier =
                                                    Modifier
                                                        .size(24.dp)
                                                        .menuAnchor(
                                                            type = ExposedDropdownMenuAnchorType.SecondaryEditable,
                                                            enabled = codexModels.isNotEmpty(),
                                                        ),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = codexModelExpanded)
                                            }
                                        }
                                    }
                                }
                                ExposedDropdownMenu(
                                    expanded = codexModelExpanded,
                                    onDismissRequest = { codexModelExpanded = false },
                                    modifier =
                                        Modifier
                                            .background(
                                                color = MaterialTheme.colorScheme.surface,
                                                shape = dropdownMenuShape(codexModelExpanded),
                                            ).border(
                                                BorderStroke(
                                                    AppTheme.strokeWidths.thin,
                                                    MaterialTheme.colorScheme.outline,
                                                ),
                                                dropdownMenuShape(codexModelExpanded),
                                            ),
                                ) {
                                    codexModels.forEach { modelId ->
                                        DropdownMenuItem(
                                            text = { Text(modelId, style = MaterialTheme.typography.bodySmall) },
                                            contentPadding =
                                                PaddingValues(
                                                    horizontal = AppTheme.spacing.md,
                                                    vertical = AppTheme.spacing.xxs,
                                                ),
                                            onClick = {
                                                codexModel = modelId
                                                codexModelExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.width(AppTheme.spacing.xl))
                            Text(
                                text = strings.codexReasoningLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Box(modifier = Modifier.width(LAUNCH_FORM_SIDE_FIELD_WIDTH)) {
                                ExposedDropdownMenuBox(
                                    expanded = codexReasoningExpanded,
                                    onExpandedChange = { codexReasoningExpanded = !codexReasoningExpanded },
                                ) {
                                    val fieldShape = dropdownFieldShape(codexReasoningExpanded)
                                    val menuShape = dropdownMenuShape(codexReasoningExpanded)
                                    val menuBackgroundColor = MaterialTheme.colorScheme.surface
                                    val menuOutlineColor = MaterialTheme.colorScheme.outline
                                    SchedulePatternDropdownField(
                                        text = codexReasoningEffortLabel(codexReasoningEffort, strings),
                                        expanded = codexReasoningExpanded,
                                        shape = fieldShape,
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .menuAnchor(
                                                    type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                                                    enabled = true,
                                                ),
                                    )
                                    ExposedDropdownMenu(
                                        expanded = codexReasoningExpanded,
                                        onDismissRequest = { codexReasoningExpanded = false },
                                        modifier =
                                            Modifier
                                                .background(color = menuBackgroundColor, shape = menuShape)
                                                .border(
                                                    BorderStroke(
                                                        AppTheme.strokeWidths.thin,
                                                        menuOutlineColor,
                                                    ),
                                                    menuShape,
                                                ),
                                    ) {
                                        UiAgentCodexReasoningEffort.entries.forEach { effort ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        codexReasoningEffortLabel(effort, strings),
                                                        style = MaterialTheme.typography.bodySmall,
                                                    )
                                                },
                                                contentPadding =
                                                    PaddingValues(
                                                        horizontal = AppTheme.spacing.md,
                                                        vertical = AppTheme.spacing.xxs,
                                                    ),
                                                onClick = {
                                                    codexReasoningEffort = effort
                                                    codexReasoningExpanded = false
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (codexModelLoadError != null) {
                            Text(
                                text = codexModelLoadError.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = AppTheme.spacing.xs),
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = AppTheme.spacing.sm),
                            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = codexWebSearch,
                                    onCheckedChange = { codexWebSearch = it },
                                    modifier = Modifier.offset(x = -AppTheme.spacing.md),
                                )
                                Text(strings.codexWebSearchLabel, style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    },
                    control = {},
                )
            }

            if (runtime == UiAgentRuntime.LANGCHAIN) {
                SettingsLikeItem(
                    title = strings.providerLabel,
                    descriptionContent = {},
                    contentPadding =
                        PaddingValues(
                            start = AppTheme.spacing.md + AppTheme.spacing.sm,
                            end = AppTheme.spacing.md,
                            top = AppTheme.spacing.md,
                            bottom = AppTheme.spacing.md,
                        ),
                    titleContent = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
                            ) {
                                Text(
                                    text = strings.providerLabel,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                val subtitleText =
                                    if (modelLoadError != null) {
                                        modelLoadError.orEmpty()
                                    } else {
                                        strings.aiProviderHint
                                    }
                                val subtitleColor =
                                    if (modelLoadError != null) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }

                                Text(
                                    text = subtitleText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = subtitleColor,
                                )
                            }
                            Box(modifier = Modifier.width(SCHEDULE_PATTERN_SELECTOR_WIDTH)) {
                                ExposedDropdownMenuBox(
                                    expanded = providerExpanded,
                                    onExpandedChange = { providerExpanded = !providerExpanded },
                                ) {
                                    val fieldShape = dropdownFieldShape(providerExpanded)
                                    val menuShape = dropdownMenuShape(providerExpanded)
                                    SchedulePatternDropdownField(
                                        text =
                                            when (provider) {
                                                UiLlmProvider.OPENAI -> strings.providerOpenAi
                                                UiLlmProvider.ANTHROPIC -> strings.providerAnthropic
                                                UiLlmProvider.LM_STUDIO -> strings.providerLmStudio
                                            },
                                        expanded = providerExpanded,
                                        shape = fieldShape,
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .menuAnchor(
                                                    type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                                                    enabled = true,
                                                ),
                                    )
                                    ExposedDropdownMenu(
                                        expanded = providerExpanded,
                                        onDismissRequest = { providerExpanded = false },
                                        modifier =
                                            Modifier
                                                .background(
                                                    color = MaterialTheme.colorScheme.surface,
                                                    shape = menuShape,
                                                ).border(
                                                    BorderStroke(
                                                        AppTheme.strokeWidths.thin,
                                                        MaterialTheme.colorScheme.outline,
                                                    ),
                                                    menuShape,
                                                ),
                                    ) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    strings.providerOpenAi,
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                            },
                                            contentPadding =
                                                PaddingValues(
                                                    horizontal = AppTheme.spacing.md,
                                                    vertical = AppTheme.spacing.xxs,
                                                ),
                                            onClick = {
                                                provider = UiLlmProvider.OPENAI
                                                providerExpanded = false
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    strings.providerAnthropic,
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                            },
                                            contentPadding =
                                                PaddingValues(
                                                    horizontal = AppTheme.spacing.md,
                                                    vertical = AppTheme.spacing.xxs,
                                                ),
                                            onClick = {
                                                provider = UiLlmProvider.ANTHROPIC
                                                providerExpanded = false
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    strings.providerLmStudio,
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                            },
                                            contentPadding =
                                                PaddingValues(
                                                    horizontal = AppTheme.spacing.md,
                                                    vertical = AppTheme.spacing.xxs,
                                                ),
                                            onClick = {
                                                provider = UiLlmProvider.LM_STUDIO
                                                providerExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    },
                    supportingContent = {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = AppTheme.spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
                        ) {
                            Text(
                                text = strings.modelLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            ExposedDropdownMenuBox(
                                expanded = modelExpanded,
                                onExpandedChange = { expanded ->
                                    modelExpanded = expanded && models.isNotEmpty()
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Surface(
                                    modifier =
                                        Modifier
                                            .height(32.dp)
                                            .fillMaxWidth()
                                            .menuAnchor(
                                                type = ExposedDropdownMenuAnchorType.PrimaryEditable,
                                                enabled = false,
                                            ),
                                    shape = AppTheme.shapes.input,
                                    color = MaterialTheme.colorScheme.surface,
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                    border =
                                        BorderStroke(
                                            AppTheme.strokeWidths.thin,
                                            MaterialTheme.colorScheme.outline,
                                        ),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxSize().padding(horizontal = AppTheme.spacing.md),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        BasicTextField(
                                            value = model,
                                            onValueChange = { model = it },
                                            modifier =
                                                Modifier
                                                    .weight(1f)
                                                    .onFocusChanged { focusState ->
                                                        if (focusState.isFocused) {
                                                            modelExpanded = false
                                                        }
                                                    },
                                            textStyle =
                                                MaterialTheme.typography.bodySmall.copy(
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                ),
                                            singleLine = true,
                                            decorationBox = { innerTextField -> innerTextField() },
                                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(
                                                onClick = {
                                                    modelExpanded = false
                                                    scope.launch {
                                                        loadModels(selectedProvider = provider, forceRefresh = true)
                                                    }
                                                },
                                                enabled = !loadingModels,
                                                modifier = Modifier.size(24.dp),
                                            ) {
                                                if (loadingModels) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(14.dp),
                                                        strokeWidth = 1.5.dp,
                                                    )
                                                } else {
                                                    Icon(
                                                        imageVector = Icons.Outlined.Refresh,
                                                        contentDescription = strings.refreshContentDescription,
                                                        tint = MaterialTheme.colorScheme.secondary,
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                }
                                            }
                                            Box(
                                                modifier =
                                                    Modifier
                                                        .size(24.dp)
                                                        .menuAnchor(
                                                            type = ExposedDropdownMenuAnchorType.SecondaryEditable,
                                                            enabled = models.isNotEmpty(),
                                                        ),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded)
                                            }
                                        }
                                    }
                                }
                                ExposedDropdownMenu(
                                    expanded = modelExpanded,
                                    onDismissRequest = { modelExpanded = false },
                                    modifier =
                                        Modifier
                                            .background(
                                                color = MaterialTheme.colorScheme.surface,
                                                shape = dropdownMenuShape(modelExpanded),
                                            ).border(
                                                BorderStroke(
                                                    AppTheme.strokeWidths.thin,
                                                    MaterialTheme.colorScheme.outline,
                                                ),
                                                dropdownMenuShape(modelExpanded),
                                            ),
                                ) {
                                    models.forEach { modelId ->
                                        DropdownMenuItem(
                                            text = { Text(modelId, style = MaterialTheme.typography.bodySmall) },
                                            contentPadding =
                                                PaddingValues(
                                                    horizontal = AppTheme.spacing.md,
                                                    vertical = AppTheme.spacing.xxs,
                                                ),
                                            onClick = {
                                                model = modelId
                                                modelExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.width(AppTheme.spacing.xl))
                            Text(
                                text = strings.temperatureLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Box(modifier = Modifier.width(LAUNCH_FORM_TEMPERATURE_FIELD_WIDTH)) {
                                CompactTextField(
                                    value = temperatureInput,
                                    onValueChange = { next ->
                                        if (next.isEmpty() || next.toDoubleOrNull() != null) {
                                            temperatureInput = next
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    },
                    control = {},
                )
            }

            val scheduleSupportingContent: @Composable ColumnScope.() -> Unit = {
                if (!runOnSchedule && schedule != null) {
                    Text(
                        text = strings.runOnScheduleRemovalHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (runOnSchedule) {
                    if (useAdvancedCron) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = AppTheme.spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CompactTextField(
                                value = advancedCronInput,
                                onValueChange = { advancedCronInput = it },
                                label = strings.advancedCronLabel,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (schedule != null && parsedScheduleForm == null) {
                            Text(
                                text = strings.customScheduleHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        when (scheduleForm.pattern) {
                            SchedulePattern.EVERY_N_MINUTES -> {
                                ScheduleInputRow(
                                    label = strings.scheduleEveryMinutesLabel,
                                    value = scheduleForm.everyMinutes.toString(),
                                    onValueChange = { next ->
                                        next.toIntOrNull()?.let { value ->
                                            scheduleForm = scheduleForm.copy(everyMinutes = value)
                                        }
                                    },
                                )
                            }

                            SchedulePattern.EVERY_N_HOURS -> {
                                ScheduleInputRow(
                                    label = strings.scheduleEveryHoursLabel,
                                    value = scheduleForm.everyHours.toString(),
                                    onValueChange = { next ->
                                        next.toIntOrNull()?.let { value ->
                                            scheduleForm = scheduleForm.copy(everyHours = value)
                                        }
                                    },
                                )
                                ScheduleInputRow(
                                    label = strings.scheduleMinuteLabel,
                                    value = scheduleForm.minute.toString(),
                                    onValueChange = { next ->
                                        next.toIntOrNull()?.let { value ->
                                            scheduleForm = scheduleForm.copy(minute = value)
                                        }
                                    },
                                )
                            }

                            SchedulePattern.DAILY,
                            SchedulePattern.WEEKDAYS,
                            -> {
                                ScheduleInputRow(
                                    label = strings.scheduleHourLabel,
                                    value = scheduleForm.hour.toString(),
                                    onValueChange = { next ->
                                        next.toIntOrNull()?.let { value ->
                                            scheduleForm = scheduleForm.copy(hour = value)
                                        }
                                    },
                                )
                                ScheduleInputRow(
                                    label = strings.scheduleMinuteLabel,
                                    value = scheduleForm.minute.toString(),
                                    onValueChange = { next ->
                                        next.toIntOrNull()?.let { value ->
                                            scheduleForm = scheduleForm.copy(minute = value)
                                        }
                                    },
                                )
                            }

                            SchedulePattern.WEEKLY -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = AppTheme.spacing.sm),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = strings.scheduleWeekdaysLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        weeklyDaysInDisplayOrder().forEach { day ->
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Checkbox(
                                                    checked = scheduleForm.weeklyDays.contains(day),
                                                    onCheckedChange = { checked ->
                                                        val updated = scheduleForm.weeklyDays.toMutableSet()
                                                        if (checked) {
                                                            updated += day
                                                        } else {
                                                            updated -= day
                                                        }
                                                        scheduleForm = scheduleForm.copy(weeklyDays = updated)
                                                    },
                                                )
                                                Text(day.label, style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }
                                ScheduleInputRow(
                                    label = strings.scheduleHourLabel,
                                    value = scheduleForm.hour.toString(),
                                    onValueChange = { next ->
                                        next.toIntOrNull()?.let { value ->
                                            scheduleForm = scheduleForm.copy(hour = value)
                                        }
                                    },
                                )
                                ScheduleInputRow(
                                    label = strings.scheduleMinuteLabel,
                                    value = scheduleForm.minute.toString(),
                                    onValueChange = { next ->
                                        next.toIntOrNull()?.let { value ->
                                            scheduleForm = scheduleForm.copy(minute = value)
                                        }
                                    },
                                )
                            }

                            SchedulePattern.MONTHLY -> {
                                ScheduleInputRow(
                                    label = strings.scheduleDayOfMonthLabel,
                                    value = scheduleForm.monthlyDay.toString(),
                                    onValueChange = { next ->
                                        next.toIntOrNull()?.let { value ->
                                            scheduleForm = scheduleForm.copy(monthlyDay = value)
                                        }
                                    },
                                )
                                ScheduleInputRow(
                                    label = strings.scheduleHourLabel,
                                    value = scheduleForm.hour.toString(),
                                    onValueChange = { next ->
                                        next.toIntOrNull()?.let { value ->
                                            scheduleForm = scheduleForm.copy(hour = value)
                                        }
                                    },
                                )
                                ScheduleInputRow(
                                    label = strings.scheduleMinuteLabel,
                                    value = scheduleForm.minute.toString(),
                                    onValueChange = { next ->
                                        next.toIntOrNull()?.let { value ->
                                            scheduleForm = scheduleForm.copy(minute = value)
                                        }
                                    },
                                )
                            }
                        }
                    }

                    when {
                        previewLoading -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.height(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Text(
                                    text = strings.loadingInline,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        previewRuns.isNotEmpty() -> {
                            Column(
                                modifier = Modifier.padding(top = AppTheme.spacing.md),
                                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
                            ) {
                                Text(
                                    text = strings.scheduleNextRunsLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                previewRuns.forEach { epochMillis ->
                                    Text(
                                        text = formatSchedulePreviewTimestamp(epochMillis),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }

                if (schedule != null && runOnSchedule) {
                    AppDangerButton(
                        onClick = {
                            readyUi.intents.removeAgentSchedule(agent.id)
                            onClose()
                        },
                    ) {
                        Text(strings.removeSchedule)
                    }
                }
            }

            SettingsLikeItem(
                title = strings.scheduleAction,
                descriptionContent = {},
                contentPadding =
                    PaddingValues(
                        start = AppTheme.spacing.md + AppTheme.spacing.sm,
                        end = AppTheme.spacing.md,
                        top = AppTheme.spacing.md,
                        bottom = AppTheme.spacing.md,
                    ),
                titleContent = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
                        ) {
                            Text(
                                text = strings.scheduleAction,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            val subtitleText =
                                if (!runOnSchedule) {
                                    strings.scheduleCardHint
                                } else if (previewError != null) {
                                    previewError.orEmpty()
                                } else if (useAdvancedCron || !scheduleForm.isValid()) {
                                    ""
                                } else {
                                    scheduleForm.toHumanReadable(strings)
                                }
                            val subtitleColor =
                                if (runOnSchedule && previewError != null) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            if (subtitleText.isNotEmpty()) {
                                Text(
                                    text = subtitleText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = subtitleColor,
                                )
                            }
                        }
                        SchedulePatternSelector(
                            runOnSchedule = runOnSchedule,
                            useAdvancedCron = useAdvancedCron,
                            schedulePattern = scheduleForm.pattern,
                            expanded = schedulePatternExpanded,
                            onExpandedChange = { schedulePatternExpanded = it },
                            onSelectDisabled = {
                                runOnSchedule = false
                                schedulePatternExpanded = false
                            },
                            onSelectPattern = { pattern ->
                                runOnSchedule = true
                                useAdvancedCron = false
                                scheduleForm = scheduleForm.copy(pattern = pattern)
                                schedulePatternExpanded = false
                            },
                            onSelectAdvanced = {
                                if (!useAdvancedCron) {
                                    scheduleForm
                                        .toCron()
                                        .takeIf { scheduleForm.isValid() }
                                        ?.let { advancedCronInput = it }
                                }
                                runOnSchedule = true
                                useAdvancedCron = true
                                schedulePatternExpanded = false
                            },
                            strings = strings,
                            modifier = Modifier.width(SCHEDULE_PATTERN_SELECTOR_WIDTH),
                        )
                    }
                },
                supportingContent = scheduleSupportingContent,
                control = {},
            )
        }

        AppVerticalScrollbar(
            scrollState = scrollState,
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .offset(x = AppTheme.spacing.md - AppTheme.strokeWidths.hairline),
        )
    }
}

@Composable
private fun AgentLaunchUnavailableState(
    title: String,
    message: String,
    onClose: () -> Unit,
) {
    val strings = LocalStrings.current
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
    ) {
        Spacer(Modifier.height(AppTheme.spacing.xs))
        EditorHeaderRow(
            title = title,
            onBack = onClose,
            actions = {
                AppSecondaryButton(
                    onClick = onClose,
                    modifier = Modifier.height(ACTION_ROW_HEIGHT),
                ) {
                    Text(strings.cancel, style = MaterialTheme.typography.labelSmall)
                }
            },
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun schedulePatternLabel(
    pattern: SchedulePattern,
    strings: AppStrings,
): String =
    when (pattern) {
        SchedulePattern.EVERY_N_MINUTES -> strings.schedulePatternEveryMinutes
        SchedulePattern.EVERY_N_HOURS -> strings.schedulePatternEveryHours
        SchedulePattern.DAILY -> strings.schedulePatternDaily
        SchedulePattern.WEEKDAYS -> strings.schedulePatternWeekdays
        SchedulePattern.WEEKLY -> strings.schedulePatternWeekly
        SchedulePattern.MONTHLY -> strings.schedulePatternMonthly
    }

private fun weeklyDaysInDisplayOrder(): List<Weekday> =
    listOf(
        Weekday.MONDAY,
        Weekday.TUESDAY,
        Weekday.WEDNESDAY,
        Weekday.THURSDAY,
        Weekday.FRIDAY,
        Weekday.SATURDAY,
        Weekday.SUNDAY,
    )

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod", "LongParameterList")
@Composable
private fun SchedulePatternSelector(
    runOnSchedule: Boolean,
    useAdvancedCron: Boolean,
    schedulePattern: SchedulePattern,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelectDisabled: () -> Unit,
    onSelectPattern: (SchedulePattern) -> Unit,
    onSelectAdvanced: () -> Unit,
    strings: AppStrings,
    modifier: Modifier = Modifier,
) {
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { onExpandedChange(!expanded) },
        modifier = modifier,
    ) {
        val fieldShape = dropdownFieldShape(expanded)
        val menuShape = dropdownMenuShape(expanded)
        SchedulePatternDropdownField(
            text =
                if (!runOnSchedule) {
                    strings.schedulePatternDisabled
                } else if (useAdvancedCron) {
                    strings.advancedCronToggle
                } else {
                    schedulePatternLabel(schedulePattern, strings)
                },
            expanded = expanded,
            shape = fieldShape,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(
                        type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                        enabled = true,
                    ),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier =
                Modifier
                    .background(color = MaterialTheme.colorScheme.surface, shape = menuShape)
                    .border(
                        BorderStroke(
                            AppTheme.strokeWidths.thin,
                            MaterialTheme.colorScheme.outline,
                        ),
                        menuShape,
                    ),
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        strings.schedulePatternDisabled,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                contentPadding =
                    PaddingValues(
                        horizontal = AppTheme.spacing.md,
                        vertical = AppTheme.spacing.xxs,
                    ),
                onClick = onSelectDisabled,
            )
            SchedulePattern.entries.forEach { pattern ->
                DropdownMenuItem(
                    text = {
                        Text(
                            schedulePatternLabel(pattern, strings),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    contentPadding =
                        PaddingValues(
                            horizontal = AppTheme.spacing.md,
                            vertical = AppTheme.spacing.xxs,
                        ),
                    onClick = { onSelectPattern(pattern) },
                )
            }
            DropdownMenuItem(
                text = {
                    Text(
                        strings.advancedCronToggle,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                contentPadding =
                    PaddingValues(
                        horizontal = AppTheme.spacing.md,
                        vertical = AppTheme.spacing.xxs,
                    ),
                onClick = onSelectAdvanced,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SchedulePatternDropdownField(
    text: String,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    shape: Shape = AppTheme.shapes.input,
) {
    Surface(
        modifier = modifier.height(SETTING_CONTROL_HEIGHT_DP.dp),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(AppTheme.strokeWidths.thin, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = AppTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
        }
    }
}

private fun UiAgentProviderSettings.modelsFor(provider: UiLlmProvider): List<String> =
    when (provider) {
        UiLlmProvider.OPENAI -> modelCache.openAi
        UiLlmProvider.ANTHROPIC -> modelCache.anthropic
        UiLlmProvider.LM_STUDIO -> modelCache.lmStudio
    }

private fun defaultModelForProvider(
    provider: UiLlmProvider,
    settings: UiAgentProviderSettings,
): String =
    settings.modelsFor(provider).firstOrNull().orEmpty().ifBlank {
        when (provider) {
            UiLlmProvider.OPENAI -> "gpt-5-nano"
            UiLlmProvider.ANTHROPIC -> ""
            UiLlmProvider.LM_STUDIO -> ""
        }
    }

private fun codexReasoningEffortLabel(
    effort: UiAgentCodexReasoningEffort,
    strings: AppStrings,
): String =
    when (effort) {
        UiAgentCodexReasoningEffort.LOW -> strings.codexReasoningLow
        UiAgentCodexReasoningEffort.MEDIUM -> strings.codexReasoningMedium
        UiAgentCodexReasoningEffort.HIGH -> strings.codexReasoningHigh
    }

private fun normalizeFileSystemAccessForRuntime(
    runtime: UiAgentRuntime,
    access: UiAgentFileSystemAccess,
): UiAgentFileSystemAccess =
    when {
        runtime == UiAgentRuntime.CODEX_CLI && access == UiAgentFileSystemAccess.NONE ->
            UiAgentFileSystemAccess.READ_ONLY
        else -> access
    }

private fun availableFileSystemAccessesForRuntime(runtime: UiAgentRuntime): List<UiAgentFileSystemAccess> =
    when (runtime) {
        UiAgentRuntime.LANGCHAIN -> UiAgentFileSystemAccess.entries
        UiAgentRuntime.CODEX_CLI ->
            listOf(
                UiAgentFileSystemAccess.READ_ONLY,
                UiAgentFileSystemAccess.READ_WRITE,
            )
    }

private fun fileSystemAccessLabel(
    access: UiAgentFileSystemAccess,
    strings: AppStrings,
): String =
    when (access) {
        UiAgentFileSystemAccess.NONE -> strings.filesystemNoAccess
        UiAgentFileSystemAccess.READ_ONLY -> strings.filesystemReadOnly
        UiAgentFileSystemAccess.READ_WRITE -> strings.filesystemReadWrite
    }

@Composable
private fun dropdownFieldShape(expanded: Boolean) =
    if (expanded) {
        AppTheme.shapes.input.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp))
    } else {
        AppTheme.shapes.input
    }

@Composable
private fun dropdownMenuShape(expanded: Boolean) =
    if (expanded) {
        AppTheme.shapes.input.copy(topStart = CornerSize(0.dp), topEnd = CornerSize(0.dp))
    } else {
        AppTheme.shapes.input
    }

@Composable
private fun ScheduleInputRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = AppTheme.spacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        SettingControlBox {
            CompactTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
