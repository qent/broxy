package io.qent.bro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ArrowRight
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qent.bro.ui.adapter.models.UiMcpServerConfig as McpServerConfig
import io.qent.bro.ui.adapter.models.UiToolReference as ToolReference
import io.qent.bro.ui.adapter.models.UiServerCapabilities as ServerCapabilities
import io.qent.bro.ui.adapter.services.fetchServerCapabilities
import kotlinx.coroutines.launch

private data class ToolItem(
    val name: String,
    val description: String? = null,
    val available: Boolean = true,
    var selected: Boolean = false
)

private data class ServerSection(
    val config: McpServerConfig,
    var expanded: Boolean = true,
    var loading: Boolean = false,
    var error: String? = null,
    val tools: MutableList<ToolItem> = mutableListOf()
)

@Composable
fun ToolSelector(
    servers: List<McpServerConfig>,
    initialSelection: List<ToolReference>,
    modifier: Modifier = Modifier,
    onSelectionChanged: (List<ToolReference>) -> Unit,
    onPreview: ((serverId: String, toolName: String, description: String?) -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    val sections = remember(servers) {
        val selectedMap = initialSelection.filter { it.enabled }.associate { it.serverId to it.toolName }.toList().groupBy({ it.first }, { it.second })
        servers.associate { s ->
            val sec = ServerSection(config = s)
            selectedMap[s.id]?.let { /* pre-selections will be applied after loading tools */ }
            s.id to sec
        }.toMutableMap()
    }

    fun emitSelection() {
        val selected = sections.values.flatMap { sec ->
            sec.tools.filter { it.selected }.map { t -> ToolReference(serverId = sec.config.id, toolName = t.name, enabled = true) }
        }
        onSelectionChanged(selected)
    }

    LaunchedEffect(servers) {
        sections.values.forEach { sec ->
            sec.loading = true
            sec.error = null
            val caps = fetchServerCapabilities(sec.config)
            if (caps.isSuccess) {
                val c: ServerCapabilities = caps.getOrThrow()
                sec.tools.clear()
                sec.tools.addAll(c.tools.map { t ->
                    ToolItem(name = t.name, description = t.description, available = true, selected = false)
                })
                // apply initial selection
                initialSelection.filter { it.serverId == sec.config.id && it.enabled }.forEach { sel ->
                    sec.tools.find { it.name == sel.toolName }?.let { it.selected = true }
                }
                sec.loading = false
            } else {
                sec.loading = false
                sec.error = caps.exceptionOrNull()?.message
                sec.tools.clear()
            }
        }
        emitSelection()
    }

    val totalSelected = sections.values.sumOf { sec -> sec.tools.count { it.selected } }

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { q -> query = q },
                label = { Text("Search tools") },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            Text("Selected: $totalSelected", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(8.dp))

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                sections.values.forEach { sec ->
                    ServerHeader(
                        section = sec,
                        selectedCount = sec.tools.count { it.selected },
                        totalCount = sec.tools.size,
                        onToggleExpand = { sec.expanded = !sec.expanded },
                        onToggleAll = {
                            val allSelected = sec.tools.isNotEmpty() && sec.tools.all { it.selected }
                            sec.tools.forEach { t -> t.selected = !allSelected }
                            emitSelection()
                        }
                    )
                    if (sec.expanded) {
                        Divider()
                        val filtered = if (query.isBlank()) sec.tools else sec.tools.filter { t ->
                            t.name.contains(query, ignoreCase = true) || (t.description?.contains(query, ignoreCase = true) == true)
                        }
                        if (filtered.isEmpty()) {
                            Text("No tools", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth().height(200.dp)) {
                                items(filtered, key = { it.name }) { item ->
                                    ToolRow(
                                        item = item,
                                        enabled = sec.error == null,
                                        onToggle = {
                                            item.selected = !item.selected
                                            emitSelection()
                                        },
                                        onFocus = { onPreview?.invoke(sec.config.id, item.name, item.description) }
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerHeader(
    section: ServerSection,
    selectedCount: Int,
    totalCount: Int,
    onToggleExpand: () -> Unit,
    onToggleAll: () -> Unit
) {
    val unavailable = section.error != null
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        IconButton(onClick = onToggleExpand) {
            if (section.expanded) Icon(Icons.Outlined.ArrowDropDown, contentDescription = null) else Icon(Icons.Outlined.ArrowRight, contentDescription = null)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(section.config.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp))
                Text("(${section.config.id})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (unavailable) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = Color(0xFFD64545))
                    Spacer(Modifier.width(4.dp))
                    Text("Unavailable", color = Color(0xFFD64545), style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text("Selected: $selectedCount / $totalCount", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Checkbox(checked = totalCount > 0 && selectedCount == totalCount, onCheckedChange = { onToggleAll() }, enabled = !unavailable)
    }
}

@Composable
private fun ToolRow(
    item: ToolItem,
    enabled: Boolean,
    onToggle: () -> Unit,
    onFocus: () -> Unit
) {
    val bg = if (item.selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Box(Modifier.background(bg)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                Checkbox(checked = item.selected, onCheckedChange = { onToggle(); onFocus() }, enabled = enabled)
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.bodyMedium)
                    if (!item.description.isNullOrBlank()) {
                        Text(item.description ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
