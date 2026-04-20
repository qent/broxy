@file:Suppress("FunctionNaming")

package io.qent.broxy.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.UiPromptRef
import io.qent.broxy.ui.adapter.models.UiResourceRef
import io.qent.broxy.ui.adapter.models.UiServerCapsSnapshot
import io.qent.broxy.ui.adapter.models.UiToolRef
import io.qent.broxy.ui.adapter.store.AppStore
import io.qent.broxy.ui.strings.LocalStrings
import io.qent.broxy.ui.theme.AppTheme

internal data class PresetSelectorSelection(
    val toolsByServer: Map<String, Set<String>>,
    val promptsByServer: Map<String, Set<String>>,
    val resourcesByServer: Map<String, Set<String>>,
)

internal data class PresetSelectorRefs(
    val tools: List<UiToolRef>,
    val prompts: List<UiPromptRef>,
    val resources: List<UiResourceRef>,
)

internal data class PresetSelectorInitializationInput(
    val initialToolRefs: List<UiToolRef>,
    val initialPromptRefs: List<UiPromptRef>,
    val initialResourceRefs: List<UiResourceRef>,
    val serverCapsSnapshots: List<UiServerCapsSnapshot>,
    val promptsConfigured: Boolean,
    val resourcesConfigured: Boolean,
)

internal fun toggleVisibleCapabilitySelection(
    currentSelection: Set<String>,
    visibleNames: Set<String>,
    checked: Boolean,
): Set<String> = if (checked) currentSelection + visibleNames else currentSelection - visibleNames

internal fun initializePresetSelectorSelection(input: PresetSelectorInitializationInput): PresetSelectorSelection {
    val toolsByServer = linkedMapOf<String, MutableSet<String>>()
    val promptsByServer = linkedMapOf<String, MutableSet<String>>()
    val resourcesByServer = linkedMapOf<String, MutableSet<String>>()

    input.initialToolRefs
        .filter { it.enabled }
        .forEach { ref ->
            toolsByServer.getOrPut(ref.serverId) { linkedSetOf() }.add(ref.toolName)
        }
    input.initialPromptRefs
        .filter { it.enabled }
        .forEach { ref ->
            promptsByServer.getOrPut(ref.serverId) { linkedSetOf() }.add(ref.promptName)
        }
    input.initialResourceRefs
        .filter { it.enabled }
        .forEach { ref ->
            resourcesByServer.getOrPut(ref.serverId) { linkedSetOf() }.add(ref.resourceKey)
        }

    val preselectedServers =
        mutableSetOf<String>().apply {
            addAll(toolsByServer.keys)
            addAll(promptsByServer.keys)
            addAll(resourcesByServer.keys)
        }

    if (!input.promptsConfigured) {
        input.serverCapsSnapshots.forEach { snapshot ->
            val serverId = snapshot.serverId
            if (serverId in preselectedServers && snapshot.prompts.isNotEmpty()) {
                promptsByServer[serverId] = snapshot.prompts.mapTo(linkedSetOf()) { it.name }
            }
        }
    }
    if (!input.resourcesConfigured) {
        input.serverCapsSnapshots.forEach { snapshot ->
            val serverId = snapshot.serverId
            if (serverId in preselectedServers && snapshot.resources.isNotEmpty()) {
                resourcesByServer[serverId] = snapshot.resources.mapTo(linkedSetOf()) { it.key }
            }
        }
    }

    return PresetSelectorSelection(
        toolsByServer = toolsByServer.mapValues { (_, names) -> names.toSet() },
        promptsByServer = promptsByServer.mapValues { (_, names) -> names.toSet() },
        resourcesByServer = resourcesByServer.mapValues { (_, names) -> names.toSet() },
    )
}

internal fun buildPresetSelectorRefs(selection: PresetSelectorSelection): PresetSelectorRefs {
    val tools =
        selection.toolsByServer.flatMap { (serverId, names) ->
            names.map { toolName -> UiToolRef(serverId = serverId, toolName = toolName, enabled = true) }
        }
    val prompts =
        selection.promptsByServer.flatMap { (serverId, names) ->
            names.map { promptName -> UiPromptRef(serverId = serverId, promptName = promptName, enabled = true) }
        }
    val resources =
        selection.resourcesByServer.flatMap { (serverId, keys) ->
            keys.map { resourceKey -> UiResourceRef(serverId = serverId, resourceKey = resourceKey, enabled = true) }
        }
    return PresetSelectorRefs(
        tools = tools,
        prompts = prompts,
        resources = resources,
    )
}

@Composable
@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod")
fun PresetSelector(
    store: AppStore,
    serverEnabledById: Map<String, Boolean> = emptyMap(),
    initialToolRefs: List<UiToolRef> = emptyList(),
    initialPromptRefs: List<UiPromptRef> = emptyList(),
    initialResourceRefs: List<UiResourceRef> = emptyList(),
    searchQuery: String = "",
    promptsConfigured: Boolean = true,
    resourcesConfigured: Boolean = true,
    onSelectionChanged: (
        tools: List<UiToolRef>,
        prompts: List<UiPromptRef>,
        resources: List<UiResourceRef>,
    ) -> Unit = { _, _, _ -> },
    onServerWholeCapabilitiesToggle: (
        serverId: String,
        serverName: String,
        enabled: Boolean,
    ) -> Unit = { _, _, _ -> },
    onPromptsConfiguredChange: (Boolean) -> Unit = {},
    onResourcesConfiguredChange: (Boolean) -> Unit = {},
) {
    val strings = LocalStrings.current
    var loading by remember { mutableStateOf(true) }
    val snaps = remember { mutableStateOf<List<UiServerCapsSnapshot>>(emptyList()) }
    val serverNames = remember { mutableStateMapOf<String, String>() }
    var expandedServerId by remember { mutableStateOf<String?>(null) }

    // Selection state
    val selectedServers = remember { mutableStateMapOf<String, Boolean>() }
    val selectedTools = remember { mutableStateMapOf<String, Set<String>>() }
    val selectedPrompts = remember { mutableStateMapOf<String, Set<String>>() }
    val selectedResources = remember { mutableStateMapOf<String, Set<String>>() }

    fun hasSelection(serverId: String): Boolean {
        val toolsSelected = selectedTools[serverId]?.isNotEmpty() == true
        val promptsSelected = selectedPrompts[serverId]?.isNotEmpty() == true
        val resourcesSelected = selectedResources[serverId]?.isNotEmpty() == true
        return toolsSelected || promptsSelected || resourcesSelected
    }

    fun updateServerSelection(serverId: String) {
        selectedServers[serverId] = hasSelection(serverId)
    }

    fun emitSelection() {
        val refs =
            buildPresetSelectorRefs(
                PresetSelectorSelection(
                    toolsByServer = selectedTools.toMap(),
                    promptsByServer = selectedPrompts.toMap(),
                    resourcesByServer = selectedResources.toMap(),
                ),
            )
        onSelectionChanged(refs.tools, refs.prompts, refs.resources)
    }

    fun updateServerCategorySelection(
        target: MutableMap<String, Set<String>>,
        serverId: String,
        visibleNames: Set<String>,
        checked: Boolean,
    ) {
        val next = toggleVisibleCapabilitySelection(target[serverId].orEmpty(), visibleNames, checked)
        if (next.isEmpty()) {
            target.remove(serverId)
        } else {
            target[serverId] = next
        }
    }

    LaunchedEffect(Unit) {
        loading = true
        val data = store.listSelectableServerCaps()
        selectedServers.clear()
        selectedTools.clear()
        selectedPrompts.clear()
        selectedResources.clear()
        snaps.value = data
        data.forEach { snap ->
            serverNames[snap.serverId] = snap.name
        }
        expandedServerId = null
        val initializedSelection =
            initializePresetSelectorSelection(
                PresetSelectorInitializationInput(
                    initialToolRefs = initialToolRefs,
                    initialPromptRefs = initialPromptRefs,
                    initialResourceRefs = initialResourceRefs,
                    serverCapsSnapshots = data,
                    promptsConfigured = promptsConfigured,
                    resourcesConfigured = resourcesConfigured,
                ),
            )
        selectedTools.putAll(initializedSelection.toolsByServer)
        selectedPrompts.putAll(initializedSelection.promptsByServer)
        selectedResources.putAll(initializedSelection.resourcesByServer)
        val preselectedServers =
            mutableSetOf<String>().apply {
                addAll(selectedTools.keys)
                addAll(selectedPrompts.keys)
                addAll(selectedResources.keys)
            }
        preselectedServers.forEach { serverId ->
            updateServerSelection(serverId)
        }
        loading = false
        emitSelection()
    }

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        if (loading) {
            Text(strings.loadingServerCapabilities, style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        if (snaps.value.isEmpty()) {
            Text(strings.noConnectedServersAvailable, style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        snaps.value.forEach { snap ->
            val serverId = snap.serverId
            val isServerEnabled = serverEnabledById[serverId] ?: false
            val filteredTools =
                snap.tools.filter {
                    matchesCapabilityQuery(searchQuery, it.name, it.description, it.arguments)
                }
            val filteredPrompts =
                snap.prompts.filter {
                    matchesCapabilityQuery(searchQuery, it.name, it.description, it.arguments)
                }
            val filteredResources =
                snap.resources.filter {
                    matchesResourceQuery(searchQuery, it.name, it.key, it.description, it.arguments)
                }
            val visibleToolNames = filteredTools.mapTo(linkedSetOf()) { it.name }
            val visiblePromptNames = filteredPrompts.mapTo(linkedSetOf()) { it.name }
            val visibleResourceKeys = filteredResources.mapTo(linkedSetOf()) { it.key }
            val shouldShowServer =
                searchQuery.isBlank() ||
                    filteredTools.isNotEmpty() ||
                    filteredPrompts.isNotEmpty() ||
                    filteredResources.isNotEmpty()
            if (!shouldShowServer) return@forEach
            val isExpanded = expandedServerId == serverId
            val serverSelected = selectedServers[serverId] == true
            val cardColor =
                if (serverSelected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            val contentColor =
                if (serverSelected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            val borderColor =
                if (serverSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                }
            val metaLabelColor =
                if (serverSelected) {
                    MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            val selectedToolsCount = selectedTools[serverId]?.size ?: 0
            val selectedPromptsCount = selectedPrompts[serverId]?.size ?: 0
            val selectedResourcesCount = selectedResources[serverId]?.size ?: 0
            val arrowRotation by animateFloatAsState(
                targetValue = if (isExpanded) 180f else 0f,
                label = "serverCapabilitiesArrow",
            )
            val toggleExpanded = { expandedServerId = if (isExpanded) null else serverId }
            val showToolsSection = searchQuery.isBlank() || filteredTools.isNotEmpty()
            val showPromptsSection = searchQuery.isBlank() || filteredPrompts.isNotEmpty()
            val showResourcesSection = searchQuery.isBlank() || filteredResources.isNotEmpty()
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = AppTheme.shapes.item,
                colors = CardDefaults.cardColors(containerColor = cardColor),
                border = BorderStroke(1.dp, borderColor),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(onClick = toggleExpanded)
                                .padding(
                                    horizontal = AppTheme.spacing.md,
                                    vertical = AppTheme.spacing.sm,
                                ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = serverSelected,
                            onCheckedChange = { checked ->
                                updateServerCategorySelection(
                                    target = selectedTools,
                                    serverId = serverId,
                                    visibleNames = visibleToolNames,
                                    checked = checked,
                                )
                                updateServerCategorySelection(
                                    target = selectedPrompts,
                                    serverId = serverId,
                                    visibleNames = visiblePromptNames,
                                    checked = checked,
                                )
                                updateServerCategorySelection(
                                    target = selectedResources,
                                    serverId = serverId,
                                    visibleNames = visibleResourceKeys,
                                    checked = checked,
                                )
                                updateServerSelection(serverId)
                                val selectedWholeServer =
                                    checked &&
                                        visibleToolNames.size == snap.tools.size &&
                                        visiblePromptNames.size == snap.prompts.size &&
                                        visibleResourceKeys.size == snap.resources.size
                                onServerWholeCapabilitiesToggle(serverId, snap.name, selectedWholeServer)
                                emitSelection()
                            },
                        )
                        Column(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .padding(start = AppTheme.spacing.sm),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    serverNames[serverId] ?: serverId,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = contentColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (!isServerEnabled) {
                                    ServerDisabledBadge(modifier = Modifier.padding(end = AppTheme.spacing.xs))
                                }
                                CapabilitiesInlineSummary(
                                    toolsCount = selectedToolsCount,
                                    promptsCount = selectedPromptsCount,
                                    resourcesCount = selectedResourcesCount,
                                    tint = metaLabelColor,
                                    iconSize = 12.dp,
                                    textStyle = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        IconButton(
                            onClick = toggleExpanded,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ExpandMore,
                                contentDescription = if (isExpanded) strings.hideDetails else strings.showDetails,
                                tint = contentColor,
                                modifier = Modifier.rotate(arrowRotation),
                            )
                        }
                    }
                    if (isExpanded) {
                        if (showToolsSection) {
                            CapabilitySection(
                                label = strings.toolsLabel,
                                isEmpty = filteredTools.isEmpty(),
                                emptyMessage = strings.noToolsAvailable,
                            ) {
                                filteredTools.forEachIndexed { index, tool ->
                                    val checked = selectedTools[serverId]?.contains(tool.name) == true
                                    val description =
                                        tool.description.takeIf { it.isNotBlank() }
                                            ?: strings.noDescriptionProvided
                                    CapabilitySelectionRow(
                                        title = tool.name,
                                        description = description,
                                        checked = checked,
                                        highlightQuery = searchQuery,
                                        onCheckedChange = { c ->
                                            val prev = selectedTools[serverId] ?: emptySet()
                                            val next = if (c) prev + tool.name else prev - tool.name
                                            selectedTools[serverId] = next
                                            updateServerSelection(serverId)
                                            emitSelection()
                                        },
                                    ) {
                                        CapabilityArgumentList(
                                            arguments = tool.arguments,
                                            modifier = Modifier.padding(top = AppTheme.spacing.xs),
                                            highlightQuery = searchQuery,
                                        )
                                    }
                                    if (index < filteredTools.lastIndex) {
                                        CapabilityDivider()
                                    }
                                }
                            }
                        }
                        if (showPromptsSection) {
                            CapabilitySection(
                                label = strings.promptsLabel,
                                isEmpty = filteredPrompts.isEmpty(),
                                emptyMessage = strings.noPromptsAvailable,
                            ) {
                                filteredPrompts.forEachIndexed { index, prompt ->
                                    val checked = selectedPrompts[serverId]?.contains(prompt.name) == true
                                    val description =
                                        prompt.description.takeIf { it.isNotBlank() }
                                            ?: strings.noDescriptionProvided
                                    CapabilitySelectionRow(
                                        title = prompt.name,
                                        description = description,
                                        checked = checked,
                                        highlightQuery = searchQuery,
                                        onCheckedChange = { c ->
                                            val prev = selectedPrompts[serverId] ?: emptySet()
                                            val next = if (c) prev + prompt.name else prev - prompt.name
                                            selectedPrompts[serverId] = next
                                            updateServerSelection(serverId)
                                            onPromptsConfiguredChange(true)
                                            emitSelection()
                                        },
                                    ) {
                                        CapabilityArgumentList(
                                            arguments = prompt.arguments,
                                            modifier = Modifier.padding(top = AppTheme.spacing.xs),
                                            highlightQuery = searchQuery,
                                        )
                                    }
                                    if (index < filteredPrompts.lastIndex) {
                                        CapabilityDivider()
                                    }
                                }
                            }
                        }
                        if (showResourcesSection) {
                            CapabilitySection(
                                label = strings.resourcesLabel,
                                isEmpty = filteredResources.isEmpty(),
                                emptyMessage = strings.noResourcesAvailable,
                            ) {
                                filteredResources.forEachIndexed { index, resource ->
                                    val checked = selectedResources[serverId]?.contains(resource.key) == true
                                    val description =
                                        resource.description.takeIf { it.isNotBlank() }
                                            ?: resource.key
                                    CapabilitySelectionRow(
                                        title = resource.name,
                                        description = description,
                                        checked = checked,
                                        highlightQuery = searchQuery,
                                        onCheckedChange = { c ->
                                            val prev = selectedResources[serverId] ?: emptySet()
                                            val next = if (c) prev + resource.key else prev - resource.key
                                            selectedResources[serverId] = next
                                            updateServerSelection(serverId)
                                            onResourcesConfiguredChange(true)
                                            emitSelection()
                                        },
                                    ) {
                                        CapabilityArgumentList(
                                            arguments = resource.arguments,
                                            modifier = Modifier.padding(top = AppTheme.spacing.xs),
                                            highlightQuery = searchQuery,
                                        )
                                    }
                                    if (index < filteredResources.lastIndex) {
                                        CapabilityDivider()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CapabilitySection(
    label: String,
    isEmpty: Boolean,
    emptyMessage: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        CapabilityDivider()
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (isEmpty) {
            Text(
                emptyMessage,
                modifier =
                    Modifier
                        .padding(horizontal = AppTheme.spacing.md)
                        .padding(bottom = AppTheme.spacing.sm),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun CapabilitySelectionRow(
    title: String,
    description: String,
    checked: Boolean,
    highlightQuery: String = "",
    onCheckedChange: (Boolean) -> Unit,
    metaContent: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.xs),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
        Column(
            modifier =
                Modifier
                    .padding(start = AppTheme.spacing.sm)
                    .weight(1f),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
        ) {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
                    HighlightedText(
                        text = title,
                        query = highlightQuery,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    metaContent?.invoke()
                    HighlightedText(
                        text = description,
                        query = highlightQuery,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CapabilityDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = AppTheme.spacing.md),
        thickness = AppTheme.strokeWidths.hairline,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
