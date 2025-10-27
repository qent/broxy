package io.qent.bro.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
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
import androidx.compose.ui.unit.dp
import io.qent.bro.ui.adapter.models.UiServerCapsSnapshot
import io.qent.bro.ui.adapter.models.UiToolRef
import io.qent.bro.ui.adapter.store.AppStore

data class PresetSelectionState(
    val selectedServers: Set<String> = emptySet(),
    val selectedTools: Set<Pair<String, String>> = emptySet(), // (serverId, toolName)
    val selectedPrompts: Set<Pair<String, String>> = emptySet(), // (serverId, promptName)
    val selectedResources: Set<Pair<String, String>> = emptySet() // (serverId, resourceKey)
)

@Composable
fun PresetSelector(
    store: AppStore,
    initialToolRefs: List<UiToolRef> = emptyList(),
    onToolsChanged: (List<UiToolRef>) -> Unit = {}
) {
    var loading by remember { mutableStateOf(true) }
    val snaps = remember { mutableStateOf<List<UiServerCapsSnapshot>>(emptyList()) }
    val serverNames = remember { mutableStateMapOf<String, String>() }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    // Selection state
    val selectedServers = remember { mutableStateMapOf<String, Boolean>() }
    val selectedTools = remember { mutableStateMapOf<String, MutableSet<String>>() } // per server tool names
    val selectedPrompts = remember { mutableStateMapOf<String, MutableSet<String>>() }
    val selectedResources = remember { mutableStateMapOf<String, MutableSet<String>>() }

    LaunchedEffect(Unit) {
        loading = true
        val data = store.listEnabledServerCaps()
        snaps.value = data
        data.forEach { snap -> serverNames[snap.serverId] = snap.name; expanded[snap.serverId] = false }
        // Initialize selection from initialToolRefs (tools only)
        initialToolRefs.filter { it.enabled }.forEach { ref ->
            selectedServers[ref.serverId] = true
            val set = selectedTools.getOrPut(ref.serverId) { mutableSetOf() }
            set += ref.toolName
        }
        loading = false
        // Push initial mapping to consumer
        onToolsChanged(selectedTools.flatMap { (sid, tools) -> tools.map { t -> UiToolRef(serverId = sid, toolName = t, enabled = true) } })
    }

    fun recomputeAndEmit() {
        val refs = selectedTools.flatMap { (sid, tools) -> tools.map { t -> UiToolRef(serverId = sid, toolName = t, enabled = true) } }
        onToolsChanged(refs)
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row {
                        val srvChecked = selectedServers[serverId] == true
                        Checkbox(
                            checked = srvChecked,
                            onCheckedChange = { checked ->
                                selectedServers[serverId] = checked
                                if (checked) {
                                    // Select all items for this server
                                    selectedTools[serverId] = snap.tools.toMutableSet()
                                    selectedPrompts[serverId] = snap.prompts.toMutableSet()
                                    selectedResources[serverId] = snap.resources.toMutableSet()
                                } else {
                                    selectedTools.remove(serverId)
                                    selectedPrompts.remove(serverId)
                                    selectedResources.remove(serverId)
                                }
                                recomputeAndEmit()
                            }
                        )
                        Text(serverNames[serverId] ?: serverId, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
                    }
                    TextButton(onClick = { expanded[serverId] = !(expanded[serverId] ?: false) }) {
                        Text(if (expanded[serverId] == true) "Hide" else "Show")
                    }
                }
                if (expanded[serverId] == true) {
                    Spacer(Modifier.height(4.dp))
                    // Tools
                    Text("Tools", style = MaterialTheme.typography.labelLarge)
                    snap.tools.forEach { t ->
                        val checked = selectedTools[serverId]?.contains(t) == true
                        Row(Modifier.fillMaxWidth().padding(start = 8.dp)) {
                            Checkbox(checked = checked, onCheckedChange = { c ->
                                val set = selectedTools.getOrPut(serverId) { mutableSetOf() }
                                if (c) set += t else set -= t
                                // Maintain server checkbox if any item selected
                                selectedServers[serverId] = (selectedTools[serverId]?.isNotEmpty() == true)
                                recomputeAndEmit()
                            })
                            Text(t, modifier = Modifier.padding(top = 10.dp))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    // Prompts
                    Text("Prompts", style = MaterialTheme.typography.labelLarge)
                    snap.prompts.forEach { p ->
                        val checked = selectedPrompts[serverId]?.contains(p) == true
                        Row(Modifier.fillMaxWidth().padding(start = 8.dp)) {
                            Checkbox(checked = checked, onCheckedChange = { c ->
                                val set = selectedPrompts.getOrPut(serverId) { mutableSetOf() }
                                if (c) set += p else set -= p
                            })
                            Text(p, modifier = Modifier.padding(top = 10.dp))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    // Resources
                    Text("Resources", style = MaterialTheme.typography.labelLarge)
                    snap.resources.forEach { key ->
                        val checked = selectedResources[serverId]?.contains(key) == true
                        Row(Modifier.fillMaxWidth().padding(start = 8.dp)) {
                            Checkbox(checked = checked, onCheckedChange = { c ->
                                val set = selectedResources.getOrPut(serverId) { mutableSetOf() }
                                if (c) set += key else set -= key
                            })
                            Text(key, modifier = Modifier.padding(top = 10.dp))
                        }
                    }
                }
                Divider(Modifier.padding(top = 8.dp))
            }
        }
    }
}
