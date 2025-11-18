package io.qent.broxy.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import io.qent.broxy.ui.adapter.models.UiPromptRef
import io.qent.broxy.ui.adapter.models.UiResourceRef
import io.qent.broxy.ui.adapter.models.UiServerCapsSnapshot
import io.qent.broxy.ui.adapter.models.UiToolRef
import io.qent.broxy.ui.adapter.store.AppStore
import io.qent.broxy.ui.theme.AppTheme

@Composable
fun PresetSelector(
    store: AppStore,
    initialToolRefs: List<UiToolRef> = emptyList(),
    initialPromptRefs: List<UiPromptRef> = emptyList(),
    initialResourceRefs: List<UiResourceRef> = emptyList(),
    promptsConfigured: Boolean = true,
    resourcesConfigured: Boolean = true,
    onSelectionChanged: (
        tools: List<UiToolRef>,
        prompts: List<UiPromptRef>,
        resources: List<UiResourceRef>
    ) -> Unit = { _, _, _ -> },
    onPromptsConfiguredChange: (Boolean) -> Unit = {},
    onResourcesConfiguredChange: (Boolean) -> Unit = {}
) {
    var loading by remember { mutableStateOf(true) }
    val snaps = remember { mutableStateOf<List<UiServerCapsSnapshot>>(emptyList()) }
    val serverNames = remember { mutableStateMapOf<String, String>() }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

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
        val tools = selectedTools.flatMap { (sid, names) ->
            names.map { name -> UiToolRef(serverId = sid, toolName = name, enabled = true) }
        }
        val prompts = selectedPrompts.flatMap { (sid, names) ->
            names.map { name -> UiPromptRef(serverId = sid, promptName = name, enabled = true) }
        }
        val resources = selectedResources.flatMap { (sid, keys) ->
            keys.map { key -> UiResourceRef(serverId = sid, resourceKey = key, enabled = true) }
        }
        onSelectionChanged(tools, prompts, resources)
    }

    LaunchedEffect(Unit) {
        loading = true
        val data = store.listEnabledServerCaps()
        snaps.value = data
        val availableServers = data.map { it.serverId }.toSet()
        data.forEach { snap ->
            serverNames[snap.serverId] = snap.name
            expanded[snap.serverId] = false
        }
        // Initialize selection from provided refs
        initialToolRefs.filter { it.enabled && it.serverId in availableServers }.forEach { ref ->
            val prev = selectedTools[ref.serverId] ?: emptySet()
            selectedTools[ref.serverId] = prev + ref.toolName
        }
        initialPromptRefs.filter { it.enabled && it.serverId in availableServers }.forEach { ref ->
            val prev = selectedPrompts[ref.serverId] ?: emptySet()
            selectedPrompts[ref.serverId] = prev + ref.promptName
        }
        initialResourceRefs.filter { it.enabled && it.serverId in availableServers }.forEach { ref ->
            val prev = selectedResources[ref.serverId] ?: emptySet()
            selectedResources[ref.serverId] = prev + ref.resourceKey
        }
        val preselectedServers = mutableSetOf<String>().apply {
            addAll(selectedTools.keys)
            addAll(selectedPrompts.keys)
            addAll(selectedResources.keys)
        }
        if (!promptsConfigured) {
            data.forEach { snap ->
                val serverId = snap.serverId
                if (serverId in preselectedServers && snap.prompts.isNotEmpty()) {
                    selectedPrompts[serverId] = snap.prompts.map { it.name }.toSet()
                }
            }
        }
        if (!resourcesConfigured) {
            data.forEach { snap ->
                val serverId = snap.serverId
                if (serverId in preselectedServers && snap.resources.isNotEmpty()) {
                    selectedResources[serverId] = snap.resources.map { it.key }.toSet()
                }
            }
        }
        preselectedServers += selectedPrompts.keys
        preselectedServers += selectedResources.keys
        preselectedServers.forEach { serverId ->
            if (serverId in availableServers) {
                updateServerSelection(serverId)
            }
        }
        loading = false
        emitSelection()
    }

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)
    ) {
        if (loading) {
            Text("Loading server capabilities...", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        if (snaps.value.isEmpty()) {
            Text("No connected servers available", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        snaps.value.forEach { snap ->
            val serverId = snap.serverId
            val serverSelected = selectedServers[serverId] == true
            val cardColor = if (serverSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
            val contentColor = if (serverSelected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            }
            val borderColor = if (serverSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
            val metaLabelColor = if (serverSelected) {
                MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = AppTheme.shapes.surfaceSm,
                tonalElevation = if (serverSelected) AppTheme.elevation.level2 else AppTheme.elevation.level1,
                color = cardColor,
                contentColor = contentColor,
                border = BorderStroke(
                    width = AppTheme.strokeWidths.hairline,
                    color = borderColor
                )
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = AppTheme.spacing.md,
                                vertical = AppTheme.spacing.sm
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = serverSelected,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    selectedTools[serverId] = snap.tools.map { it.name }.toSet()
                                    selectedPrompts[serverId] = snap.prompts.map { it.name }.toSet()
                                    selectedResources[serverId] = snap.resources.map { it.key }.toSet()
                                    updateServerSelection(serverId)
                                } else {
                                    selectedTools.remove(serverId)
                                    selectedPrompts.remove(serverId)
                                    selectedResources.remove(serverId)
                                    selectedServers[serverId] = false
                                }
                                emitSelection()
                            }
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = AppTheme.spacing.sm)
                        ) {
                            Text(
                                serverNames[serverId] ?: serverId,
                                style = MaterialTheme.typography.titleSmall,
                                color = contentColor
                            )
                            Text(
                                "${snap.tools.size} tools · ${snap.prompts.size} prompts · ${snap.resources.size} resources",
                                style = MaterialTheme.typography.bodySmall,
                                color = metaLabelColor
                            )
                        }
                        TextButton(onClick = { expanded[serverId] = !(expanded[serverId] ?: false) }) {
                            Text(if (expanded[serverId] == true) "Hide" else "Show")
                        }
                    }
                    if (expanded[serverId] == true) {
                        CapabilitySection(
                            label = "Tools",
                            isEmpty = snap.tools.isEmpty(),
                            emptyMessage = "No tools available"
                        ) {
                            snap.tools.forEachIndexed { index, tool ->
                                val checked = selectedTools[serverId]?.contains(tool.name) == true
                                val description = tool.description?.takeIf { it.isNotBlank() }
                                    ?: "No description provided"
                                CapabilitySelectionRow(
                                    title = tool.name,
                                    description = description,
                                    checked = checked,
                                    onCheckedChange = { c ->
                                        val prev = selectedTools[serverId] ?: emptySet()
                                        val next = if (c) prev + tool.name else prev - tool.name
                                        selectedTools[serverId] = next
                                        updateServerSelection(serverId)
                                        emitSelection()
                                    }
                                ) {
                                    CapabilityArgumentList(
                                        arguments = tool.arguments,
                                        modifier = Modifier.padding(top = AppTheme.spacing.xs)
                                    )
                                }
                                if (index < snap.tools.lastIndex) {
                                    CapabilityDivider()
                                }
                            }
                        }
                        CapabilitySection(
                            label = "Prompts",
                            isEmpty = snap.prompts.isEmpty(),
                            emptyMessage = "No prompts available"
                        ) {
                            snap.prompts.forEachIndexed { index, prompt ->
                                val checked = selectedPrompts[serverId]?.contains(prompt.name) == true
                                val description = prompt.description?.takeIf { it.isNotBlank() }
                                    ?: "No description provided"
                                CapabilitySelectionRow(
                                    title = prompt.name,
                                    description = description,
                                    checked = checked,
                                    onCheckedChange = { c ->
                                        val prev = selectedPrompts[serverId] ?: emptySet()
                                        val next = if (c) prev + prompt.name else prev - prompt.name
                                        selectedPrompts[serverId] = next
                                        updateServerSelection(serverId)
                                        onPromptsConfiguredChange(true)
                                        emitSelection()
                                    }
                                ) {
                                    CapabilityArgumentList(
                                        arguments = prompt.arguments,
                                        modifier = Modifier.padding(top = AppTheme.spacing.xs)
                                    )
                                }
                                if (index < snap.prompts.lastIndex) {
                                    CapabilityDivider()
                                }
                            }
                        }
                        CapabilitySection(
                            label = "Resources",
                            isEmpty = snap.resources.isEmpty(),
                            emptyMessage = "No resources available"
                        ) {
                            snap.resources.forEachIndexed { index, resource ->
                                val checked = selectedResources[serverId]?.contains(resource.key) == true
                                val description = resource.description?.takeIf { it.isNotBlank() }
                                    ?: resource.key
                                CapabilitySelectionRow(
                                    title = resource.name,
                                    description = description,
                                    checked = checked,
                                    onCheckedChange = { c ->
                                        val prev = selectedResources[serverId] ?: emptySet()
                                        val next = if (c) prev + resource.key else prev - resource.key
                                        selectedResources[serverId] = next
                                        updateServerSelection(serverId)
                                        onResourcesConfiguredChange(true)
                                        emitSelection()
                                    }
                                ) {
                                    CapabilityArgumentList(
                                        arguments = resource.arguments,
                                        modifier = Modifier.padding(top = AppTheme.spacing.xs)
                                    )
                                }
                                if (index < snap.resources.lastIndex) {
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

@Composable
private fun CapabilitySection(
    label: String,
    isEmpty: Boolean,
    emptyMessage: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        CapabilityDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        if (isEmpty) {
            Text(
                emptyMessage,
                modifier = Modifier
                    .padding(horizontal = AppTheme.spacing.md)
                    .padding(bottom = AppTheme.spacing.sm),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
    }
}

@Composable
private fun CapabilitySelectionRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    metaContent: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.xs),
        verticalAlignment = Alignment.Top
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
        Column(
            modifier = Modifier
                .padding(start = AppTheme.spacing.sm)
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            metaContent?.invoke()
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CapabilityDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = AppTheme.spacing.md),
        thickness = AppTheme.strokeWidths.hairline,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}
