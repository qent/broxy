@file:Suppress("FunctionNaming")

package io.qent.broxy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.UiAgentDraft
import io.qent.broxy.ui.adapter.models.UiAgentToolRef
import io.qent.broxy.ui.adapter.models.UiPromptRef
import io.qent.broxy.ui.adapter.models.UiResourceRef
import io.qent.broxy.ui.adapter.models.UiServerCapsSnapshot
import io.qent.broxy.ui.adapter.models.UiToolRef
import io.qent.broxy.ui.adapter.store.AppStore
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.components.AgentsSelector
import io.qent.broxy.ui.components.AppPrimaryButton
import io.qent.broxy.ui.components.AppSecondaryButton
import io.qent.broxy.ui.components.AppVerticalScrollbar
import io.qent.broxy.ui.components.CapabilitiesCard
import io.qent.broxy.ui.components.EditorHeaderRow
import io.qent.broxy.ui.components.FormCard
import io.qent.broxy.ui.components.PresetSelector
import io.qent.broxy.ui.components.SearchField
import io.qent.broxy.ui.components.SearchFieldFabAlignedBottomPadding
import io.qent.broxy.ui.components.SettingsLikeItem
import io.qent.broxy.ui.strings.LocalStrings
import io.qent.broxy.ui.theme.AppTheme
import io.qent.broxy.ui.viewmodels.AgentEditorState
import kotlinx.coroutines.launch

private const val CREATE_PRESET_OPTION_ID = "__create_preset__"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
fun AgentEditorScreen(
    ui: UIState,
    store: AppStore,
    editor: AgentEditorState,
    onClose: () -> Unit,
) {
    val strings = LocalStrings.current
    val readyUi = ui as? UIState.Ready
    val initialDraft =
        remember(editor) {
            when (editor) {
                AgentEditorState.Create ->
                    UiAgentDraft(
                        id = "",
                        name = "",
                        systemPrompt = "",
                        tools = emptyList(),
                        agentTools = emptyList(),
                        prompts = emptyList(),
                        resources = emptyList(),
                        promptsConfigured = true,
                        resourcesConfigured = true,
                        originalId = null,
                        orderIndex = readyUi?.agents?.size ?: 0,
                    )

                is AgentEditorState.Edit -> store.getAgentDraft(editor.agentId)
            }
        }

    if (initialDraft == null) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
        ) {
            EditorHeaderRow(
                title = strings.editAgent,
                onBack = onClose,
            )
            Text(
                text = strings.agentNotFound,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    val isCreate = editor is AgentEditorState.Create
    val title = if (isCreate) strings.createAgent else strings.editAgent
    val primaryActionLabel = if (isCreate) strings.add else strings.save

    var name by remember(editor) { mutableStateOf(initialDraft.name) }
    var systemPrompt by remember(editor) { mutableStateOf(initialDraft.systemPrompt) }
    var description by remember(editor) { mutableStateOf(initialDraft.description.orEmpty()) }
    var selectedTools by remember(editor) { mutableStateOf<List<UiToolRef>>(initialDraft.tools) }
    var selectedAgentTools by remember(editor) { mutableStateOf<List<UiAgentToolRef>>(initialDraft.agentTools) }
    var selectedPrompts by remember(editor) { mutableStateOf<List<UiPromptRef>>(initialDraft.prompts) }
    var selectedResources by remember(editor) { mutableStateOf<List<UiResourceRef>>(initialDraft.resources) }
    var promptsConfigured by remember(editor) { mutableStateOf(initialDraft.promptsConfigured) }
    var resourcesConfigured by remember(editor) { mutableStateOf(initialDraft.resourcesConfigured) }
    var capabilitySearch by rememberSaveable(editor) { mutableStateOf("") }
    var isGeneratingDescription by remember(editor) { mutableStateOf(false) }
    var descriptionGenerationError by remember(editor) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val availablePresetIds = readyUi?.presets?.map { it.id }.orEmpty()
    var presetSourceId by remember(editor, availablePresetIds) {
        mutableStateOf(
            if (isCreate && availablePresetIds.isNotEmpty()) {
                availablePresetIds.first()
            } else {
                CREATE_PRESET_OPTION_ID
            },
        )
    }

    val resolvedName = name.trim()
    val resolvedSystemPrompt = systemPrompt.trim()
    val baseGeneratedId = generateAgentId(resolvedName)
    val existingAgentIds =
        readyUi
            ?.agents
            ?.map { it.id }
            ?.toSet()
            .orEmpty()
    val occupiedIds = if (isCreate) existingAgentIds else existingAgentIds - initialDraft.id
    val resolvedId = generateUniqueAgentId(baseGeneratedId, occupiedIds)
    val canSubmit =
        readyUi != null &&
            resolvedName.isNotBlank() &&
            resolvedSystemPrompt.isNotBlank() &&
            resolvedId.isNotBlank()

    val selectedPresetDraft =
        remember(presetSourceId, readyUi?.presets) {
            if (presetSourceId == CREATE_PRESET_OPTION_ID) {
                null
            } else {
                store.getPresetDraft(presetSourceId)
            }
        }
    val effectiveTools = selectedPresetDraft?.tools ?: selectedTools
    val presetAgentTools = selectedPresetDraft?.agentTools.orEmpty()
    val effectiveAgentTools = mergeAgentToolRefs(presetAgentTools, selectedAgentTools)
    val effectivePrompts = selectedPresetDraft?.prompts ?: selectedPrompts
    val effectiveResources = selectedPresetDraft?.resources ?: selectedResources
    val effectivePromptsConfigured = selectedPresetDraft?.promptsConfigured ?: promptsConfigured
    val effectiveResourcesConfigured = selectedPresetDraft?.resourcesConfigured ?: resourcesConfigured
    val aiFeaturesEnabled = readyUi?.agentProviderSettings?.aiFeatures?.enabled == true
    val canGenerateDescription =
        readyUi != null &&
            !isGeneratingDescription &&
            resolvedName.isNotBlank() &&
            resolvedSystemPrompt.isNotBlank()

    val serverNamesById =
        remember(readyUi) {
            readyUi?.servers?.associate { it.id to it.name }.orEmpty()
        }
    val serverEnabledById =
        remember(readyUi) {
            readyUi?.servers?.associate { it.id to it.enabled }.orEmpty()
        }
    val serverCapsSnapshots = remember { mutableStateOf<List<UiServerCapsSnapshot>>(emptyList()) }
    LaunchedEffect(editor) {
        serverCapsSnapshots.value = store.listSelectableServerCaps()
    }
    val serverCapsById =
        remember(serverCapsSnapshots.value) {
            serverCapsSnapshots.value.associateBy { it.serverId }
        }
    val trimmedCapabilityQuery = capabilitySearch.trim()
    val displayContext =
        remember(serverNamesById, serverCapsById, serverEnabledById, trimmedCapabilityQuery) {
            CapabilityDisplayContext(
                serverNames = serverNamesById,
                serverCapsById = serverCapsById,
                serverEnabledById = serverEnabledById,
                searchQuery = trimmedCapabilityQuery,
            )
        }

    val toolItems =
        remember(effectiveTools, displayContext, strings) {
            buildToolCapabilityItems(
                effectiveTools,
                displayContext,
                strings,
            )
        }
    val promptItems =
        remember(effectivePrompts, displayContext, strings) {
            buildPromptCapabilityItems(
                effectivePrompts,
                displayContext,
                strings,
            )
        }
    val resourceItems =
        remember(effectiveResources, displayContext) {
            buildResourceCapabilityItems(
                effectiveResources,
                displayContext,
            )
        }

    val scrollState = rememberScrollState()
    val actionRowHeight = 40.dp

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
        ) {
            Spacer(Modifier.height(AppTheme.spacing.xs))

            EditorHeaderRow(
                title = title,
                onBack = onClose,
                actions = {
                    AppSecondaryButton(
                        onClick = onClose,
                        modifier = Modifier.height(actionRowHeight),
                    ) {
                        Text(strings.cancel, style = MaterialTheme.typography.labelSmall)
                    }
                    AppPrimaryButton(
                        onClick = {
                            val intents = readyUi?.intents ?: return@AppPrimaryButton
                            intents.upsertAgent(
                                UiAgentDraft(
                                    id = resolvedId,
                                    name = resolvedName,
                                    systemPrompt = resolvedSystemPrompt,
                                    description = description.trim().ifBlank { null },
                                    tools = effectiveTools,
                                    agentTools = effectiveAgentTools,
                                    prompts = effectivePrompts,
                                    resources = effectiveResources,
                                    promptsConfigured = effectivePromptsConfigured,
                                    resourcesConfigured = effectiveResourcesConfigured,
                                    originalId = if (isCreate) null else (initialDraft.originalId ?: initialDraft.id),
                                    orderIndex = initialDraft.orderIndex,
                                    schedule = initialDraft.schedule,
                                    manualLaunchDefaults = initialDraft.manualLaunchDefaults,
                                ),
                            )
                            onClose()
                        },
                        enabled = canSubmit,
                        modifier = Modifier.height(actionRowHeight),
                    ) {
                        Text(primaryActionLabel, style = MaterialTheme.typography.labelSmall)
                    }
                },
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(strings.nameLabel) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            val descriptionSectionState =
                remember(description, aiFeaturesEnabled, resolvedName, resolvedSystemPrompt, isGeneratingDescription) {
                    resolveAgentDescriptionSectionState(
                        description = description,
                        aiFeaturesEnabled = aiFeaturesEnabled,
                        isInputValid = resolvedName.isNotBlank() && resolvedSystemPrompt.isNotBlank(),
                        isGenerating = isGeneratingDescription,
                    )
                }
            AgentDescriptionSection(
                state = descriptionSectionState,
                generationError = descriptionGenerationError,
                onGenerate = {
                    if (!canGenerateDescription || !aiFeaturesEnabled) return@AgentDescriptionSection
                    descriptionGenerationError = null
                    isGeneratingDescription = true
                    val draftForGeneration =
                        UiAgentDraft(
                            id = resolvedId,
                            name = resolvedName,
                            systemPrompt = resolvedSystemPrompt,
                            description = description.trim().ifBlank { null },
                            tools = effectiveTools,
                            agentTools = effectiveAgentTools,
                            prompts = effectivePrompts,
                            resources = effectiveResources,
                            promptsConfigured = effectivePromptsConfigured,
                            resourcesConfigured = effectiveResourcesConfigured,
                            originalId = initialDraft.originalId,
                            orderIndex = initialDraft.orderIndex,
                            schedule = initialDraft.schedule,
                            manualLaunchDefaults = initialDraft.manualLaunchDefaults,
                        )
                    scope.launch {
                        val generated = store.generateAgentDescription(draftForGeneration)
                        generated
                            .onSuccess { value ->
                                description = value
                                descriptionGenerationError = null
                            }.onFailure { failure ->
                                descriptionGenerationError = strings.agentDescriptionGenerationFailed(failure.message)
                            }
                        isGeneratingDescription = false
                    }
                },
            )
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                label = { Text(strings.systemPromptLabel) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
                minLines = 5,
                maxLines = 10,
            )

            FormCard(title = strings.agentPresetSourceSection) {
                val presets = readyUi?.presets.orEmpty()
                var expanded by remember(editor, presets) { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded },
                ) {
                    val selectedLabel =
                        if (presetSourceId == CREATE_PRESET_OPTION_ID) {
                            strings.customAgentPreset
                        } else {
                            presets.firstOrNull { it.id == presetSourceId }?.name ?: strings.customAgentPreset
                        }
                    OutlinedTextField(
                        value = selectedLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(strings.presetSourceLabel) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                        },
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
                        onDismissRequest = { expanded = false },
                    ) {
                        presets.forEach { preset ->
                            DropdownMenuItem(
                                text = { Text(preset.name) },
                                onClick = {
                                    presetSourceId = preset.id
                                    expanded = false
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(strings.customAgentPreset) },
                            onClick = {
                                presetSourceId = CREATE_PRESET_OPTION_ID
                                expanded = false
                            },
                        )
                    }
                }

                if (presetSourceId == CREATE_PRESET_OPTION_ID) {
                    Text(
                        text = strings.selectCapabilitiesHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PresetSelector(
                        store = store,
                        serverEnabledById = serverEnabledById,
                        initialToolRefs = selectedTools,
                        initialPromptRefs = selectedPrompts,
                        initialResourceRefs = selectedResources,
                        searchQuery = trimmedCapabilityQuery,
                        promptsConfigured = promptsConfigured,
                        resourcesConfigured = resourcesConfigured,
                        onSelectionChanged = { tools, prompts, resources ->
                            selectedTools = tools
                            selectedPrompts = prompts
                            selectedResources = resources
                        },
                        onPromptsConfiguredChange = { promptsConfigured = it },
                        onResourcesConfiguredChange = { resourcesConfigured = it },
                    )
                }
            }

            FormCard(title = strings.navAgents) {
                val selectedPresetAgentIds = presetAgentTools.map { it.agentId }.toSet()
                AgentsSelector(
                    availableAgents = readyUi?.agents.orEmpty(),
                    initialRefs = effectiveAgentTools,
                    excludeAgentId = if (isCreate) null else (initialDraft.originalId ?: initialDraft.id),
                    onSelectionChanged = { refs ->
                        selectedAgentTools =
                            if (presetSourceId == CREATE_PRESET_OPTION_ID) {
                                refs
                            } else {
                                refs.filter { it.agentId !in selectedPresetAgentIds }
                            }
                    },
                )
            }

            CapabilitiesCard(
                title = strings.toolsLabel,
                items = toolItems,
                icon = Icons.Outlined.Construction,
                highlightQuery = trimmedCapabilityQuery,
            )
            CapabilitiesCard(
                title = strings.promptsLabel,
                items = promptItems,
                icon = Icons.Outlined.ChatBubbleOutline,
                highlightQuery = trimmedCapabilityQuery,
            )
            CapabilitiesCard(
                title = strings.resourcesLabel,
                items = resourceItems,
                icon = Icons.Outlined.Description,
                highlightQuery = trimmedCapabilityQuery,
            )
            Spacer(Modifier.height(AppTheme.spacing.fab))
        }

        AppVerticalScrollbar(
            scrollState = scrollState,
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .offset(x = AppTheme.spacing.md - AppTheme.strokeWidths.hairline),
        )

        SearchField(
            value = capabilitySearch,
            onValueChange = { capabilitySearch = it },
            placeholder = strings.searchCapabilities,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = SearchFieldFabAlignedBottomPadding),
        )
    }
}

private fun mergeAgentToolRefs(
    base: List<UiAgentToolRef>,
    additional: List<UiAgentToolRef>,
): List<UiAgentToolRef> {
    val merged = linkedMapOf<String, UiAgentToolRef>()
    (base + additional)
        .asSequence()
        .filter { it.enabled }
        .forEach { ref ->
            merged.putIfAbsent(ref.agentId, UiAgentToolRef(agentId = ref.agentId, enabled = true))
        }
    return merged.values.toList()
}

internal data class AgentDescriptionSectionState(
    val text: String?,
    val showPlaceholder: Boolean,
    val generateEnabled: Boolean,
    val showEnableHint: Boolean,
    val isGenerating: Boolean,
)

internal fun resolveAgentDescriptionSectionState(
    description: String,
    aiFeaturesEnabled: Boolean,
    isInputValid: Boolean,
    isGenerating: Boolean,
): AgentDescriptionSectionState {
    val normalizedText = description.trim().ifBlank { null }
    return AgentDescriptionSectionState(
        text = normalizedText,
        showPlaceholder = normalizedText == null,
        generateEnabled = aiFeaturesEnabled && isInputValid && !isGenerating,
        showEnableHint = !aiFeaturesEnabled,
        isGenerating = isGenerating,
    )
}

@Composable
private fun AgentDescriptionSection(
    state: AgentDescriptionSectionState,
    generationError: String?,
    onGenerate: () -> Unit,
) {
    val strings = LocalStrings.current
    SettingsLikeItem(
        title = strings.agentDescriptionLabel,
        descriptionContent = {
            if (state.showPlaceholder) {
                Text(
                    text = strings.agentDescriptionGenerateTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = strings.agentDescriptionGenerateSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = state.text.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.showEnableHint) {
                Text(
                    text = strings.agentDescriptionEnableHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = AppTheme.spacing.xxs),
                )
            }
            if (!generationError.isNullOrBlank()) {
                Text(
                    text = generationError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = AppTheme.spacing.xxs),
                )
            }
        },
        control = {
            AppSecondaryButton(
                onClick = onGenerate,
                enabled = state.generateEnabled,
            ) {
                Text(
                    text = if (state.isGenerating) strings.generatingAction else strings.generateAction,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
    )
}

private fun generateAgentId(name: String): String {
    val normalized = name.trim().lowercase()
    if (normalized.isBlank()) return ""
    val sb = StringBuilder()
    var lastWasDash = false
    for (ch in normalized) {
        if (ch.isLetterOrDigit()) {
            sb.append(ch)
            lastWasDash = false
        } else if (!lastWasDash) {
            sb.append('-')
            lastWasDash = true
        }
    }
    return sb.toString().trim('-')
}

private fun generateUniqueAgentId(
    baseId: String,
    occupiedIds: Set<String>,
): String {
    if (baseId.isBlank()) return ""
    var candidate = baseId
    var suffix = 2
    while (candidate in occupiedIds) {
        candidate = "$baseId-$suffix"
        suffix++
    }
    return candidate
}
