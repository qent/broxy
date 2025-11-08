package io.qent.broxy.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(AppTheme.spacing.sm)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row {
                        val srvChecked = selectedServers[serverId] == true
                        Checkbox(
                            checked = srvChecked,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    // FIX: присваиваем новые set'ы
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
                        Text(
                            serverNames[serverId] ?: serverId,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = AppTheme.spacing.md)
                        )
                    }
                    TextButton(onClick = { expanded[serverId] = !(expanded[serverId] ?: false) }) {
                        Text(if (expanded[serverId] == true) "Hide" else "Show")
                    }
                }
                if (expanded[serverId] == true) {
                    Spacer(Modifier.height(AppTheme.spacing.xs))
                    // Tools
                    Text("Tools", style = MaterialTheme.typography.labelLarge)
                    snap.tools.forEach { t ->
                        val checked = selectedTools[serverId]?.contains(t.name) == true
                        val description = t.description?.takeIf { it.isNotBlank() } ?: "No description provided"
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = AppTheme.spacing.sm)
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { c ->
                                    // FIX: создаём новое множество и присваиваем в map
                                    val prev = selectedTools[serverId] ?: emptySet()
                                    val next = if (c) prev + t.name else prev - t.name
                                    selectedTools[serverId] = next
                                    // Maintain server checkbox if any item selected
                                    updateServerSelection(serverId)
                                    emitSelection()
                                }
                            )
                            Column(modifier = Modifier.padding(top = AppTheme.spacing.sm)) {
                                Text(t.name, style = MaterialTheme.typography.bodyMedium)
                                CapabilityArgumentList(
                                    arguments = t.arguments,
                                    modifier = Modifier.padding(top = AppTheme.spacing.xs)
                                )
                                Text(
                                    description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(AppTheme.spacing.xs))
                    // Prompts
                    Text("Prompts", style = MaterialTheme.typography.labelLarge)
                    snap.prompts.forEach { p ->
                        val checked = selectedPrompts[serverId]?.contains(p.name) == true
                        val description = p.description?.takeIf { it.isNotBlank() } ?: "No description provided"
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = AppTheme.spacing.sm)
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { c ->
                                    val prev = selectedPrompts[serverId] ?: emptySet()
                                    val next = if (c) prev + p.name else prev - p.name
                                    selectedPrompts[serverId] = next
                                    updateServerSelection(serverId)
                                    onPromptsConfiguredChange(true)
                                    emitSelection()
                                }
                            )
                            Column(modifier = Modifier.padding(top = AppTheme.spacing.sm)) {
                                Text(p.name, style = MaterialTheme.typography.bodyMedium)
                                CapabilityArgumentList(
                                    arguments = p.arguments,
                                    modifier = Modifier.padding(top = AppTheme.spacing.xs)
                                )
                                Text(
                                    description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(AppTheme.spacing.xs))
                    // Resources
                    Text("Resources", style = MaterialTheme.typography.labelLarge)
                    snap.resources.forEach { resource ->
                        val checked = selectedResources[serverId]?.contains(resource.key) == true
                        val description = resource.description?.takeIf { it.isNotBlank() } ?: resource.key
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = AppTheme.spacing.sm)
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { c ->
                                    val prev = selectedResources[serverId] ?: emptySet()
                                    val next = if (c) prev + resource.key else prev - resource.key
                                    selectedResources[serverId] = next
                                    updateServerSelection(serverId)
                                    onResourcesConfiguredChange(true)
                                    emitSelection()
                                }
                            )
                            Column(modifier = Modifier.padding(top = AppTheme.spacing.sm)) {
                                Text(resource.name, style = MaterialTheme.typography.bodyMedium)
                                CapabilityArgumentList(
                                    arguments = resource.arguments,
                                    modifier = Modifier.padding(top = AppTheme.spacing.xs)
                                )
                                Text(
                                    description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(top = AppTheme.spacing.sm))
            }
        }
    }
}
