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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.UiPresetDraft
import io.qent.broxy.ui.adapter.models.UiPromptRef
import io.qent.broxy.ui.adapter.models.UiResourceRef
import io.qent.broxy.ui.adapter.models.UiServerCapsSnapshot
import io.qent.broxy.ui.adapter.models.UiToolRef
import io.qent.broxy.ui.adapter.store.AppStore
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.components.AppPrimaryButton
import io.qent.broxy.ui.components.AppSecondaryButton
import io.qent.broxy.ui.components.AppVerticalScrollbar
import io.qent.broxy.ui.components.CapabilitiesCard
import io.qent.broxy.ui.components.EditorHeaderRow
import io.qent.broxy.ui.components.FormCard
import io.qent.broxy.ui.components.PresetSelector
import io.qent.broxy.ui.components.SearchField
import io.qent.broxy.ui.components.SearchFieldFabAlignedBottomPadding
import io.qent.broxy.ui.strings.LocalStrings
import io.qent.broxy.ui.theme.AppTheme
import io.qent.broxy.ui.viewmodels.PresetEditorState

@Composable
@Suppress("LongMethod")
fun PresetEditorScreen(
    ui: UIState,
    store: AppStore,
    editor: PresetEditorState,
    onClose: () -> Unit,
) {
    val strings = LocalStrings.current
    val initialDraft =
        remember(editor) {
            when (editor) {
                PresetEditorState.Create ->
                    UiPresetDraft(
                        id = "",
                        name = "",
                        tools = emptyList(),
                        prompts = emptyList(),
                        resources = emptyList(),
                        promptsConfigured = true,
                        resourcesConfigured = true,
                        originalId = null,
                        createdAtEpochMillis = null,
                    )

                is PresetEditorState.Edit -> store.getPresetDraft(editor.presetId)
            }
        }

    if (initialDraft == null) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
        ) {
            EditorHeaderRow(
                title = strings.editPreset,
                onBack = onClose,
            )
            Text(
                text = strings.presetNotFound,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    val isCreate = editor is PresetEditorState.Create
    val title = if (isCreate) strings.createPreset else strings.editPreset
    val primaryActionLabel = if (isCreate) strings.add else strings.save
    var searchQuery by rememberSaveable { mutableStateOf("") }

    var name by remember(editor) { mutableStateOf(initialDraft.name) }
    var selectedTools by remember(editor) { mutableStateOf<List<UiToolRef>>(initialDraft.tools) }
    var selectedPrompts by remember(editor) { mutableStateOf<List<UiPromptRef>>(initialDraft.prompts) }
    var selectedResources by remember(editor) { mutableStateOf<List<UiResourceRef>>(initialDraft.resources) }
    var promptsConfigured by remember(editor) { mutableStateOf(initialDraft.promptsConfigured) }
    var resourcesConfigured by remember(editor) { mutableStateOf(initialDraft.resourcesConfigured) }

    val resolvedName = name.trim()
    val baseGeneratedId = generatePresetId(resolvedName)
    val existingPresetIds =
        (ui as? UIState.Ready)
            ?.presets
            ?.asSequence()
            ?.map { it.id }
            ?.toSet()
            .orEmpty()
    val occupiedIds = if (isCreate) existingPresetIds else existingPresetIds - initialDraft.id
    val resolvedId = generateUniquePresetId(baseGeneratedId, occupiedIds)

    val canSubmit = ui is UIState.Ready && resolvedName.isNotBlank() && resolvedId.isNotBlank()

    val scrollState = rememberScrollState()
    val actionRowHeight = 40.dp
    val serverNamesById =
        remember(ui) {
            (ui as? UIState.Ready)?.servers?.associate { it.id to it.name }.orEmpty()
        }
    val serverEnabledById =
        remember(ui) {
            (ui as? UIState.Ready)?.servers?.associate { it.id to it.enabled }.orEmpty()
        }
    val serverCapsSnapshots = remember { mutableStateOf<List<UiServerCapsSnapshot>>(emptyList()) }

    LaunchedEffect(editor) {
        serverCapsSnapshots.value = store.listSelectableServerCaps()
    }

    val serverCapsById =
        remember(serverCapsSnapshots.value) {
            serverCapsSnapshots.value.associateBy { it.serverId }
        }
    val trimmedQuery = searchQuery.trim()
    val displayContext =
        remember(serverNamesById, serverCapsById, serverEnabledById, trimmedQuery) {
            CapabilityDisplayContext(
                serverNames = serverNamesById,
                serverCapsById = serverCapsById,
                serverEnabledById = serverEnabledById,
                searchQuery = trimmedQuery,
            )
        }
    val toolItems =
        remember(selectedTools, displayContext, strings) {
            buildToolCapabilityItems(
                selectedTools,
                displayContext,
                strings,
            )
        }
    val promptItems =
        remember(selectedPrompts, displayContext, strings) {
            buildPromptCapabilityItems(
                selectedPrompts,
                displayContext,
                strings,
            )
        }
    val resourceItems =
        remember(selectedResources, displayContext) {
            buildResourceCapabilityItems(
                selectedResources,
                displayContext,
            )
        }

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
                            val readyUi = ui as? UIState.Ready ?: return@AppPrimaryButton
                            val draft =
                                UiPresetDraft(
                                    id = resolvedId,
                                    name = resolvedName,
                                    tools = selectedTools,
                                    prompts = selectedPrompts,
                                    resources = selectedResources,
                                    promptsConfigured = promptsConfigured,
                                    resourcesConfigured = resourcesConfigured,
                                    originalId = if (isCreate) null else (initialDraft.originalId ?: initialDraft.id),
                                    createdAtEpochMillis = initialDraft.createdAtEpochMillis,
                                )
                            readyUi.intents.upsertPreset(draft)
                            onClose()
                        },
                        enabled = canSubmit,
                        modifier = Modifier.height(actionRowHeight),
                    ) {
                        Text(primaryActionLabel, style = MaterialTheme.typography.labelSmall)
                    }
                },
            )

            PresetIdentityCard(
                name = name,
                onNameChange = { name = it },
            )

            FormCard(title = strings.mcpServersTitle) {
                Text(
                    strings.selectCapabilitiesHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(AppTheme.spacing.xs))
                PresetSelector(
                    store = store,
                    serverEnabledById = serverEnabledById,
                    initialToolRefs = initialDraft.tools,
                    initialPromptRefs = initialDraft.prompts,
                    initialResourceRefs = initialDraft.resources,
                    searchQuery = trimmedQuery,
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

            CapabilitiesCard(
                title = strings.toolsLabel,
                items = toolItems,
                icon = Icons.Outlined.Construction,
                highlightQuery = trimmedQuery,
            )
            CapabilitiesCard(
                title = strings.promptsLabel,
                items = promptItems,
                icon = Icons.Outlined.ChatBubbleOutline,
                highlightQuery = trimmedQuery,
            )
            CapabilitiesCard(
                title = strings.resourcesLabel,
                items = resourceItems,
                icon = Icons.Outlined.Description,
                highlightQuery = trimmedQuery,
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
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = strings.searchCapabilities,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = SearchFieldFabAlignedBottomPadding),
        )
    }
}

@Composable
private fun PresetIdentityCard(
    name: String,
    onNameChange: (String) -> Unit,
) {
    val strings = LocalStrings.current
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text(strings.nameLabel) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

private fun generatePresetId(name: String): String {
    val normalized = name.trim().lowercase()
    if (normalized.isBlank()) return ""

    val sb = StringBuilder()
    var lastWasDash = false
    for (ch in normalized) {
        val isAllowed = ch.isLetterOrDigit()
        if (isAllowed) {
            sb.append(ch)
            lastWasDash = false
        } else if (!lastWasDash) {
            sb.append('-')
            lastWasDash = true
        }
    }

    return sb.toString().trim('-')
}

private fun generateUniquePresetId(
    baseId: String,
    occupiedIds: Set<String>,
): String {
    if (baseId.isBlank()) {
        return ""
    }
    var candidate = baseId
    var suffix = 2
    while (candidate in occupiedIds) {
        candidate = "$baseId-$suffix"
        suffix++
    }
    return candidate
}
