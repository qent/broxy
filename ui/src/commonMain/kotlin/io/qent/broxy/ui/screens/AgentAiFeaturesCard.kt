@file:Suppress("FunctionNaming", "TooManyFunctions")

package io.qent.broxy.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.UiAgentCodexReasoningEffort
import io.qent.broxy.ui.adapter.models.UiAgentProviderSettings
import io.qent.broxy.ui.adapter.models.UiLlmProvider
import io.qent.broxy.ui.adapter.store.AppStore
import io.qent.broxy.ui.components.SettingsLikeItem
import io.qent.broxy.ui.strings.AppStrings
import io.qent.broxy.ui.strings.LocalStrings
import io.qent.broxy.ui.theme.AppTheme
import kotlinx.coroutines.launch

private val AI_RUNTIME_SELECTOR_WIDTH = SettingControlWidth * 2f
private val AI_SIDE_FIELD_WIDTH = 190.dp
private val AI_FIELD_SPACING = 14.dp
private val AI_TEMPERATURE_FIELD_WIDTH = SettingControlWidth / 2f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod")
internal fun AgentAiFeaturesCard(
    store: AppStore,
    providerSettings: UiAgentProviderSettings,
    runtimeSelection: AiFeaturesRuntimeSelection,
    llmProvider: UiLlmProvider,
    llmModel: String,
    llmTemperatureInput: String,
    codexModel: String,
    codexReasoningEffort: UiAgentCodexReasoningEffort,
    codexProviderEnabled: Boolean,
    llmModelError: String?,
    llmTemperatureError: String?,
    codexModelError: String?,
    onRuntimeSelectionChange: (AiFeaturesRuntimeSelection) -> Unit,
    onLlmProviderChange: (UiLlmProvider) -> Unit,
    onLlmModelChange: (String) -> Unit,
    onLlmTemperatureChange: (String) -> Unit,
    onCodexModelChange: (String) -> Unit,
    onCodexReasoningEffortChange: (UiAgentCodexReasoningEffort) -> Unit,
    showDisabledRuntimeOption: Boolean,
    titleOverride: String? = null,
    subtitleOverride: String? = null,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val cardTitle = titleOverride ?: strings.aiFeaturesCardTitle
    val cardSubtitle = subtitleOverride ?: strings.aiFeaturesRuntimeHint

    var runtimeExpanded by rememberSaveable { mutableStateOf(false) }
    var providerExpanded by rememberSaveable { mutableStateOf(false) }
    var llmModelExpanded by rememberSaveable { mutableStateOf(false) }
    var codexModelExpanded by rememberSaveable { mutableStateOf(false) }
    var reasoningExpanded by rememberSaveable { mutableStateOf(false) }

    var models by remember(llmProvider, providerSettings.modelCache) {
        mutableStateOf(providerSettings.modelsFor(llmProvider))
    }
    var codexModels by remember(providerSettings.modelCache.codex) {
        mutableStateOf(providerSettings.modelCache.codex)
    }
    var loadingModels by remember(llmProvider) { mutableStateOf(false) }
    var modelLoadError by remember(llmProvider) { mutableStateOf<String?>(null) }
    var loadingCodexModels by remember { mutableStateOf(false) }
    var codexModelLoadError by remember { mutableStateOf<String?>(null) }
    var isInitialProviderSync by rememberSaveable { mutableStateOf(false) }

    suspend fun loadModels(
        selectedProvider: UiLlmProvider,
        forceRefresh: Boolean,
    ) {
        loadingModels = true
        modelLoadError = null
        val result = store.listProviderModels(selectedProvider, forceRefresh)
        loadingModels = false
        if (llmProvider != selectedProvider) {
            return
        }
        if (result.isSuccess) {
            val loaded = result.getOrThrow()
            models = loaded
            if (llmModel.trim().isBlank()) {
                onLlmModelChange(
                    loaded.firstOrNull().orEmpty().ifBlank {
                        defaultModelForProvider(selectedProvider, providerSettings)
                    },
                )
            }
        } else {
            modelLoadError = result.exceptionOrNull()?.message
            if (models.isEmpty()) {
                models = providerSettings.modelsFor(selectedProvider)
            }
            if (llmModel.trim().isBlank()) {
                onLlmModelChange(
                    models.firstOrNull().orEmpty().ifBlank {
                        defaultModelForProvider(selectedProvider, providerSettings)
                    },
                )
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
                onCodexModelChange(
                    loaded.firstOrNull().orEmpty().ifBlank { providerSettings.aiFeatures.codex.model },
                )
            }
        } else {
            codexModelLoadError = result.exceptionOrNull()?.message
            if (codexModels.isEmpty()) {
                codexModels = providerSettings.modelCache.codex
            }
            if (codexModel.trim().isBlank()) {
                onCodexModelChange(
                    codexModels.firstOrNull().orEmpty().ifBlank { providerSettings.aiFeatures.codex.model },
                )
            }
        }
    }

    LaunchedEffect(llmProvider) {
        val cachedModels = providerSettings.modelsFor(llmProvider)
        models = cachedModels

        if (!isInitialProviderSync) {
            isInitialProviderSync = true
            if (llmModel.trim().isBlank()) {
                onLlmModelChange(
                    cachedModels.firstOrNull().orEmpty().ifBlank {
                        defaultModelForProvider(llmProvider, providerSettings)
                    },
                )
            }
            if (cachedModels.isEmpty()) {
                loadModels(selectedProvider = llmProvider, forceRefresh = false)
            }
            return@LaunchedEffect
        }

        onLlmModelChange(
            cachedModels.firstOrNull().orEmpty().ifBlank {
                defaultModelForProvider(llmProvider, providerSettings)
            },
        )
        llmModelExpanded = false
        loadModels(selectedProvider = llmProvider, forceRefresh = false)
    }

    LaunchedEffect(runtimeSelection) {
        if (runtimeSelection != AiFeaturesRuntimeSelection.CODEX_CLI) {
            codexModelExpanded = false
            return@LaunchedEffect
        }
        codexModels = providerSettings.modelCache.codex
        loadCodexModels(forceRefresh = false)
    }

    SettingsLikeItem(
        title = cardTitle,
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
                        text = cardTitle,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = cardSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(modifier = Modifier.width(AI_RUNTIME_SELECTOR_WIDTH)) {
                    AiRuntimeSelector(
                        selection = runtimeSelection,
                        codexProviderEnabled = codexProviderEnabled,
                        expanded = runtimeExpanded,
                        onExpandedChange = { runtimeExpanded = it },
                        onSelect = {
                            onRuntimeSelectionChange(it)
                            runtimeExpanded = false
                        },
                        showDisabledOption = showDisabledRuntimeOption,
                    )
                }
            }
        },
        supportingContent = {
            when (runtimeSelection) {
                AiFeaturesRuntimeSelection.DISABLED -> Unit

                AiFeaturesRuntimeSelection.LANGCHAIN -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = AppTheme.spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(AI_FIELD_SPACING),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.width(AI_SIDE_FIELD_WIDTH)) {
                            CompactDropdownSelector(
                                value = llmProvider,
                                expanded = providerExpanded,
                                onExpandedChange = { providerExpanded = it },
                                options = UiLlmProvider.entries,
                                optionLabel = { provider ->
                                    when (provider) {
                                        UiLlmProvider.OPENAI -> strings.providerOpenAi
                                        UiLlmProvider.ANTHROPIC -> strings.providerAnthropic
                                        UiLlmProvider.LM_STUDIO -> strings.providerLmStudio
                                    }
                                },
                                onSelect = {
                                    onLlmProviderChange(it)
                                    providerExpanded = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        EditableModelField(
                            value = llmModel,
                            onValueChange = onLlmModelChange,
                            models = models,
                            expanded = llmModelExpanded,
                            onExpandedChange = { llmModelExpanded = it },
                            loading = loadingModels,
                            onRefresh = {
                                llmModelExpanded = false
                                scope.launch {
                                    loadModels(selectedProvider = llmProvider, forceRefresh = true)
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )

                        Box(modifier = Modifier.width(AI_TEMPERATURE_FIELD_WIDTH)) {
                            CompactTextField(
                                value = llmTemperatureInput,
                                onValueChange = { next ->
                                    if (next.isEmpty() || next.toDoubleOrNull() != null) {
                                        onLlmTemperatureChange(next)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    modelLoadError?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = AppTheme.spacing.xs),
                        )
                    }
                    llmModelError?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = AppTheme.spacing.xs),
                        )
                    }
                    llmTemperatureError?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = AppTheme.spacing.xs),
                        )
                    }
                }

                AiFeaturesRuntimeSelection.CODEX_CLI -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = AppTheme.spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(AI_FIELD_SPACING),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EditableModelField(
                            value = codexModel,
                            onValueChange = onCodexModelChange,
                            models = codexModels,
                            expanded = codexModelExpanded,
                            onExpandedChange = { codexModelExpanded = it },
                            loading = loadingCodexModels,
                            onRefresh = {
                                codexModelExpanded = false
                                scope.launch {
                                    loadCodexModels(forceRefresh = true)
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )

                        Box(modifier = Modifier.width(AI_SIDE_FIELD_WIDTH)) {
                            CompactDropdownSelector(
                                value = codexReasoningEffort,
                                expanded = reasoningExpanded,
                                onExpandedChange = { reasoningExpanded = it },
                                options = UiAgentCodexReasoningEffort.entries,
                                optionLabel = { effort -> codexReasoningEffortLabel(effort, strings) },
                                onSelect = {
                                    onCodexReasoningEffortChange(it)
                                    reasoningExpanded = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    codexModelLoadError?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = AppTheme.spacing.xs),
                        )
                    }
                    codexModelError?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = AppTheme.spacing.xs),
                        )
                    }
                    if (!codexProviderEnabled) {
                        Text(
                            text = strings.codexProviderDisabledHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = AppTheme.spacing.xs),
                        )
                    }
                }
            }
        },
        control = {},
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod", "LongParameterList")
private fun AiRuntimeSelector(
    selection: AiFeaturesRuntimeSelection,
    codexProviderEnabled: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (AiFeaturesRuntimeSelection) -> Unit,
    showDisabledOption: Boolean,
) {
    val strings = LocalStrings.current
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { onExpandedChange(!expanded) },
    ) {
        val fieldShape = dropdownFieldShape(expanded)
        val menuShape = dropdownMenuShape(expanded)
        DropdownSelectorField(
            text = aiRuntimeSelectionLabel(selection, strings),
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
            if (showDisabledOption) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = strings.runtimeDisabled,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    contentPadding =
                        PaddingValues(
                            horizontal = AppTheme.spacing.md,
                            vertical = AppTheme.spacing.xxs,
                        ),
                    onClick = { onSelect(AiFeaturesRuntimeSelection.DISABLED) },
                )
            }
            DropdownMenuItem(
                text = {
                    Text(
                        text = strings.runtimeLangChain,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                contentPadding =
                    PaddingValues(
                        horizontal = AppTheme.spacing.md,
                        vertical = AppTheme.spacing.xxs,
                    ),
                onClick = { onSelect(AiFeaturesRuntimeSelection.LANGCHAIN) },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = strings.runtimeCodex,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                enabled = codexProviderEnabled,
                contentPadding =
                    PaddingValues(
                        horizontal = AppTheme.spacing.md,
                        vertical = AppTheme.spacing.xxs,
                    ),
                onClick = { onSelect(AiFeaturesRuntimeSelection.CODEX_CLI) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList")
private fun <T> CompactDropdownSelector(
    value: T,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { onExpandedChange(!expanded) },
        modifier = modifier,
    ) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(SETTING_CONTROL_HEIGHT_DP.dp)
                    .menuAnchor(
                        type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                        enabled = true,
                    ),
            shape = AppTheme.shapes.input,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(AppTheme.strokeWidths.thin, MaterialTheme.colorScheme.outline),
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = AppTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = optionLabel(value),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            }
        }
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option), style = MaterialTheme.typography.bodySmall) },
                    onClick = { onSelect(option) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList", "LongMethod")
private fun EditableModelField(
    value: String,
    onValueChange: (String) -> Unit,
    models: List<String>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    loading: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { next ->
            onExpandedChange(next && models.isNotEmpty())
        },
        modifier = modifier,
    ) {
        Surface(
            modifier =
                Modifier
                    .height(SETTING_CONTROL_HEIGHT_DP.dp)
                    .fillMaxWidth()
                    .menuAnchor(
                        type = ExposedDropdownMenuAnchorType.PrimaryEditable,
                        enabled = false,
                    ),
            shape = AppTheme.shapes.input,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(AppTheme.strokeWidths.thin, MaterialTheme.colorScheme.outline),
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = AppTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier =
                        Modifier
                            .weight(1f)
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused) {
                                    onExpandedChange(false)
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
                        onClick = onRefresh,
                        enabled = !loading,
                        modifier = Modifier.size(24.dp),
                    ) {
                        if (loading) {
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
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    }
                }
            }
        }
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier =
                Modifier
                    .background(
                        color = MaterialTheme.colorScheme.surface,
                        shape = dropdownMenuShape(expanded),
                    ).border(
                        BorderStroke(
                            AppTheme.strokeWidths.thin,
                            MaterialTheme.colorScheme.outline,
                        ),
                        dropdownMenuShape(expanded),
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
                        onValueChange(modelId)
                        onExpandedChange(false)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownSelectorField(
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

@Composable
private fun dropdownFieldShape(expanded: Boolean) =
    if (expanded) {
        AppTheme.shapes.input.copy(
            bottomStart = CornerSize(0.dp),
            bottomEnd = CornerSize(0.dp),
        )
    } else {
        AppTheme.shapes.input
    }

@Composable
private fun dropdownMenuShape(expanded: Boolean) =
    if (expanded) {
        AppTheme.shapes.input.copy(
            topStart = CornerSize(0.dp),
            topEnd = CornerSize(0.dp),
        )
    } else {
        AppTheme.shapes.input
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

private fun aiRuntimeSelectionLabel(
    selection: AiFeaturesRuntimeSelection,
    strings: AppStrings,
): String =
    when (selection) {
        AiFeaturesRuntimeSelection.DISABLED -> strings.runtimeDisabled
        AiFeaturesRuntimeSelection.LANGCHAIN -> strings.runtimeLangChain
        AiFeaturesRuntimeSelection.CODEX_CLI -> strings.runtimeCodex
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
