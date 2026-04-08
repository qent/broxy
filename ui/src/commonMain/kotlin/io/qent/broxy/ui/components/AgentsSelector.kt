@file:Suppress("FunctionNaming")

package io.qent.broxy.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.UiAgent
import io.qent.broxy.ui.adapter.models.UiAgentToolRef
import io.qent.broxy.ui.strings.LocalStrings
import io.qent.broxy.ui.theme.AppTheme

@Composable
@Suppress("LongMethod")
fun AgentsSelector(
    availableAgents: List<UiAgent>,
    initialRefs: List<UiAgentToolRef>,
    excludeAgentId: String? = null,
    onSelectionChanged: (List<UiAgentToolRef>) -> Unit = {},
) {
    val strings = LocalStrings.current
    val selectedById = remember { mutableStateMapOf<String, Boolean>() }
    val visibleAgents =
        remember(availableAgents, excludeAgentId) {
            availableAgents.filter { agent ->
                val excluded = excludeAgentId?.takeIf { it.isNotBlank() }
                excluded == null || agent.id != excluded
            }
        }
    val visibleIds = remember(visibleAgents) { visibleAgents.map { it.id }.toSet() }
    val preservedMissingRefs =
        remember(initialRefs, visibleIds) {
            initialRefs
                .filter { it.enabled && it.agentId !in visibleIds }
                .distinctBy { it.agentId }
        }

    fun emitSelection() {
        val selectedVisibleRefs =
            visibleAgents
                .filter { agent -> selectedById[agent.id] == true }
                .map { agent -> UiAgentToolRef(agentId = agent.id, enabled = true) }
        onSelectionChanged(selectedVisibleRefs + preservedMissingRefs)
    }

    LaunchedEffect(initialRefs, visibleAgents) {
        selectedById.clear()
        val selectedIds = initialRefs.filter { it.enabled }.map { it.agentId }.toSet()
        visibleAgents.forEach { agent ->
            selectedById[agent.id] = agent.id in selectedIds
        }
        emitSelection()
    }

    if (visibleAgents.isEmpty()) {
        Text(
            text = strings.noAgentsAvailableForTools,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        visibleAgents.forEach { agent ->
            val checked = selectedById[agent.id] == true
            val cardColor =
                if (checked) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            val borderColor =
                if (checked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                }
            val contentColor =
                if (checked) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = AppTheme.shapes.item,
                colors = CardDefaults.cardColors(containerColor = cardColor),
                border = BorderStroke(1.dp, borderColor),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = AppTheme.spacing.md,
                                vertical = AppTheme.spacing.sm,
                            ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { value ->
                            selectedById[agent.id] = value
                            emitSelection()
                        },
                    )
                    Column(
                        modifier =
                            Modifier
                                .weight(1f)
                                .padding(start = AppTheme.spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xxs),
                    ) {
                        Text(
                            text = agent.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = contentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text =
                                agent.description
                                    ?.trim()
                                    ?.takeIf { it.isNotBlank() }
                                    ?: strings.noDescriptionProvided,
                            style = MaterialTheme.typography.bodySmall,
                            color = contentColor.copy(alpha = 0.78f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
