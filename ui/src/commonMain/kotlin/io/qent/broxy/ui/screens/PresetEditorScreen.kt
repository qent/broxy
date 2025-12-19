package io.qent.broxy.ui.screens

import AppPrimaryButton
import AppSecondaryButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.UiPresetDraft
import io.qent.broxy.ui.adapter.models.UiPromptRef
import io.qent.broxy.ui.adapter.models.UiResourceRef
import io.qent.broxy.ui.adapter.models.UiToolRef
import io.qent.broxy.ui.adapter.store.AppStore
import io.qent.broxy.ui.adapter.store.UIState

import io.qent.broxy.ui.components.PresetSelector
import io.qent.broxy.ui.theme.AppTheme
import io.qent.broxy.ui.viewmodels.PresetEditorState

@Composable
fun PresetEditorScreen(
    ui: UIState,
    store: AppStore,
    editor: PresetEditorState,
    onClose: () -> Unit
) {
    val initialDraft = remember(editor) {
        when (editor) {
            PresetEditorState.Create -> UiPresetDraft(
                id = "",
                name = "",
                tools = emptyList(),
                prompts = emptyList(),
                resources = emptyList(),
                promptsConfigured = true,
                resourcesConfigured = true,
                originalId = null
            )

            is PresetEditorState.Edit -> store.getPresetDraft(editor.presetId)
        }
    }

    if (initialDraft == null) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md)
        ) {
            HeaderRow(
                title = "Edit preset",
                onBack = onClose
            )
            Text(
                text = "Preset not found.",
                style = MaterialTheme.typography.bodyMedium
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
    val serverNamesById = remember(ui) {
        (ui as? UIState.Ready)?.servers?.associate { it.id to it.name }.orEmpty()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md)
    ) {
        Spacer(Modifier.height(AppTheme.spacing.xs))

        HeaderRow(
            title = title,
            onBack = onClose,
            actions = {
                AppSecondaryButton(
                    onClick = onClose,
                    modifier = Modifier.height(actionRowHeight)
                ) {
                    Text("Cancel", style = MaterialTheme.typography.labelSmall)
                }
                AppPrimaryButton(
                    onClick = {
                        val readyUi = ui as? UIState.Ready ?: return@AppPrimaryButton
                        val draft = UiPresetDraft(
                            id = resolvedId,
                            name = resolvedName,
                            tools = selectedTools,
                            prompts = selectedPrompts,
                            resources = selectedResources,
                            promptsConfigured = promptsConfigured,
                            resourcesConfigured = resourcesConfigured,
                            originalId = if (isCreate) null else (initialDraft.originalId ?: initialDraft.id)
                        )
                        readyUi.intents.upsertPreset(draft)
                        onClose()
                    },
                    enabled = canSubmit,
                    modifier = Modifier.height(actionRowHeight)
                ) {
                    Text(primaryActionLabel, style = MaterialTheme.typography.labelSmall)
                }
            }
        )

        PresetIdentityCard(
            name = name,
            onNameChange = { name = it },
            resolvedId = resolvedId
        )

        FormCard(title = "MCP servers") {
            Text(
                "Select tools/prompts/resources from connected servers",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                onResourcesConfiguredChange = { resourcesConfigured = it }
            )
        }

        FormCard(title = "Preset capabilities") {
            PresetCapabilitiesSummary(
                tools = selectedTools,
                prompts = selectedPrompts,
                resources = selectedResources,
                serverNames = serverNamesById
            )
        }
    }
}

@Composable
private fun PresetIdentityCard(
    name: String,
    onNameChange: (String) -> Unit,
    resolvedId: String
) {
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text("Name") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}

@Composable
private fun HeaderRow(
    title: String,
    onBack: () -> Unit,
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (actions != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                content = actions
            )
        }
    }
}

@Composable
private fun FormCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = AppTheme.shapes.card
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppTheme.spacing.lg, vertical = AppTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content(this)
        }
    }
}

@Composable
private fun PresetCapabilitiesSummary(
    tools: List<UiToolRef>,
    prompts: List<UiPromptRef>,
    resources: List<UiResourceRef>,
    serverNames: Map<String, String>
) {
    val toolItems = tools.filter { it.enabled }.map { ref ->
        formatCapabilityLabel(serverNames, ref.serverId, ref.toolName)
    }
    val promptItems = prompts.filter { it.enabled }.map { ref ->
        formatCapabilityLabel(serverNames, ref.serverId, ref.promptName)
    }
    val resourceItems = resources.filter { it.enabled }.map { ref ->
        formatCapabilityLabel(serverNames, ref.serverId, ref.resourceKey)
    }

    val sections = listOf(
        "Tools" to toolItems,
        "Prompts" to promptItems,
        "Resources" to resourceItems
    ).filter { it.second.isNotEmpty() }

    if (sections.isEmpty()) {
        Text(
            "No capabilities selected yet",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
        sections.forEachIndexed { index, (label, items) ->
            if (index > 0) {
                HorizontalDivider(
                    thickness = AppTheme.strokeWidths.hairline,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
            PresetCapabilitiesSection(label = label, items = items)
        }
    }
}

@Composable
private fun PresetCapabilitiesSection(
    label: String,
    items: List<String>
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xxs)) {
            items.forEach { item ->
                Text(
                    text = "- $item",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatCapabilityLabel(
    serverNames: Map<String, String>,
    serverId: String,
    capabilityName: String
): String {
    val serverName = serverNames[serverId] ?: serverId
    return "$serverName: $capabilityName"
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

private fun generateUniquePresetId(baseId: String, occupiedIds: Set<String>): String {
    if (baseId.isBlank()) return ""
    if (baseId !in occupiedIds) return baseId

    var suffix = 2
    while (true) {
        val candidate = "$baseId-$suffix"
        if (candidate !in occupiedIds) return candidate
        suffix++
    }
}
