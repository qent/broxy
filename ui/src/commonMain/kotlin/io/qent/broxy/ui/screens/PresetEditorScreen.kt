package io.qent.broxy.ui.screens

import AppPrimaryButton
import AppSecondaryButton
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.*
import io.qent.broxy.ui.adapter.store.AppStore
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.components.CapabilitiesCard
import io.qent.broxy.ui.components.CapabilityDisplayItem
import io.qent.broxy.ui.components.EditorHeaderRow
import io.qent.broxy.ui.components.FormCard
import io.qent.broxy.ui.components.PresetSelector
import io.qent.broxy.ui.theme.AppTheme
import io.qent.broxy.ui.viewmodels.PresetEditorState

@Composable
fun PresetEditorScreen(
    ui: UIState,
    store: AppStore,
    editor: PresetEditorState,
    onClose: () -> Unit,
) {
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
                title = "Edit preset",
                onBack = onClose,
            )
            Text(
                text = "Preset not found.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    val isCreate = editor is PresetEditorState.Create
    val title = if (isCreate) "Create preset" else "Edit preset"
    val primaryActionLabel = if (isCreate) "Add" else "Save"

    var name by remember(editor) { mutableStateOf(initialDraft.name) }
    var selectedTools by remember(editor) { mutableStateOf<List<UiToolRef>>(initialDraft.tools) }
    var selectedPrompts by remember(editor) { mutableStateOf<List<UiPromptRef>>(initialDraft.prompts) }
    var selectedResources by remember(editor) { mutableStateOf<List<UiResourceRef>>(initialDraft.resources) }
    var promptsConfigured by remember(editor) { mutableStateOf(initialDraft.promptsConfigured) }
    var resourcesConfigured by remember(editor) { mutableStateOf(initialDraft.resourcesConfigured) }

    val resolvedName = name.trim()
    val baseGeneratedId = generatePresetId(resolvedName)
    val existingPresetIds = (ui as? UIState.Ready)?.presets?.asSequence()?.map { it.id }?.toSet().orEmpty()
    val occupiedIds = if (isCreate) existingPresetIds else existingPresetIds - initialDraft.id
    val resolvedId = generateUniquePresetId(baseGeneratedId, occupiedIds)

    val canSubmit = ui is UIState.Ready && resolvedName.isNotBlank() && resolvedId.isNotBlank()

    val scrollState = rememberScrollState()
    val actionRowHeight = 40.dp
    val serverNamesById =
        remember(ui) {
            (ui as? UIState.Ready)?.servers?.associate { it.id to it.name }.orEmpty()
        }
    val serverCapsSnapshots = remember { mutableStateOf<List<UiServerCapsSnapshot>>(emptyList()) }

    LaunchedEffect(editor) {
        serverCapsSnapshots.value = store.listEnabledServerCaps()
    }

    val serverCapsById =
        remember(serverCapsSnapshots.value) {
            serverCapsSnapshots.value.associateBy { it.serverId }
        }
    val toolItems =
        remember(selectedTools, serverCapsById, serverNamesById) {
            buildToolCapabilityItems(selectedTools, serverNamesById, serverCapsById)
        }
    val promptItems =
        remember(selectedPrompts, serverCapsById, serverNamesById) {
            buildPromptCapabilityItems(selectedPrompts, serverNamesById, serverCapsById)
        }
    val resourceItems =
        remember(selectedResources, serverCapsById, serverNamesById) {
            buildResourceCapabilityItems(selectedResources, serverNamesById, serverCapsById)
        }

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
                    Text("Cancel", style = MaterialTheme.typography.labelSmall)
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
            resolvedId = resolvedId,
        )

        FormCard(title = "MCP servers") {
            Text(
                "Select tools/prompts/resources from connected servers",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(AppTheme.spacing.xs))
            PresetSelector(
                store = store,
                initialToolRefs = initialDraft.tools,
                initialPromptRefs = initialDraft.prompts,
                initialResourceRefs = initialDraft.resources,
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
            title = "Tools",
            items = toolItems,
            icon = Icons.Outlined.Construction,
        )
        CapabilitiesCard(
            title = "Prompts",
            items = promptItems,
            icon = Icons.Outlined.ChatBubbleOutline,
        )
        CapabilitiesCard(
            title = "Resources",
            items = resourceItems,
            icon = Icons.Outlined.Description,
        )
        Spacer(Modifier.height(AppTheme.spacing.md))
    }
}

@Composable
private fun PresetIdentityCard(
    name: String,
    onNameChange: (String) -> Unit,
    resolvedId: String,
) {
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text("Name") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

private fun buildToolCapabilityItems(
    tools: List<UiToolRef>,
    serverNames: Map<String, String>,
    serverCapsById: Map<String, UiServerCapsSnapshot>,
): List<CapabilityDisplayItem> {
    return tools.filter { it.enabled }.map { ref ->
        val summary = serverCapsById[ref.serverId]?.tools?.firstOrNull { it.name == ref.toolName }
        val serverName = serverNames[ref.serverId] ?: ref.serverId
        val capabilityName = summary?.name ?: ref.toolName
        val description = summary?.description?.takeIf { it.isNotBlank() } ?: "No description provided"
        CapabilityDisplayItem(serverName, capabilityName, description, summary?.arguments.orEmpty())
    }
}

private fun buildPromptCapabilityItems(
    prompts: List<UiPromptRef>,
    serverNames: Map<String, String>,
    serverCapsById: Map<String, UiServerCapsSnapshot>,
): List<CapabilityDisplayItem> {
    return prompts.filter { it.enabled }.map { ref ->
        val summary = serverCapsById[ref.serverId]?.prompts?.firstOrNull { it.name == ref.promptName }
        val serverName = serverNames[ref.serverId] ?: ref.serverId
        val capabilityName = summary?.name ?: ref.promptName
        val description = summary?.description?.takeIf { it.isNotBlank() } ?: "No description provided"
        CapabilityDisplayItem(serverName, capabilityName, description, summary?.arguments.orEmpty())
    }
}

private fun buildResourceCapabilityItems(
    resources: List<UiResourceRef>,
    serverNames: Map<String, String>,
    serverCapsById: Map<String, UiServerCapsSnapshot>,
): List<CapabilityDisplayItem> {
    return resources.filter { it.enabled }.map { ref ->
        val summary = serverCapsById[ref.serverId]?.resources?.firstOrNull { it.key == ref.resourceKey }
        val displayName = summary?.name?.ifBlank { ref.resourceKey } ?: ref.resourceKey
        val serverName = serverNames[ref.serverId] ?: ref.serverId
        val description =
            summary?.description?.takeIf { it.isNotBlank() }
                ?: summary?.key
                ?: ref.resourceKey
        CapabilityDisplayItem(serverName, displayName, description, summary?.arguments.orEmpty())
    }
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
    if (baseId.isBlank()) return ""
    if (baseId !in occupiedIds) return baseId

    var suffix = 2
    while (true) {
        val candidate = "$baseId-$suffix"
        if (candidate !in occupiedIds) return candidate
        suffix++
    }
}
