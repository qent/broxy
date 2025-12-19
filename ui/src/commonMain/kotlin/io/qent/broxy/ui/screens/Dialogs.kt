package io.qent.broxy.ui.screens

import AppSecondaryButton
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.UiCapabilityArgument
import io.qent.broxy.ui.adapter.models.UiServer
import io.qent.broxy.ui.adapter.models.UiServerCapsSnapshot
import io.qent.broxy.ui.adapter.store.AppStore
import io.qent.broxy.ui.components.AppDialog
import io.qent.broxy.ui.components.CapabilityArgumentList
import io.qent.broxy.ui.theme.AppTheme
import kotlinx.coroutines.CancellationException

@Composable
fun ServerDetailsDialog(
    server: UiServer,
    store: AppStore,
    onClose: () -> Unit,
) {
    val loadState by produceState<ServerDetailsState>(
        initialValue = ServerDetailsState.Loading,
        key1 = server.id,
    ) {
        value =
            try {
                val snapshot = store.getServerCaps(server.id)
                if (snapshot != null) {
                    ServerDetailsState.Ready(snapshot)
                } else {
                    ServerDetailsState.Error("Capabilities unavailable")
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                ServerDetailsState.Error(t.message ?: "Failed to load capabilities")
            }
    }

    AppDialog(
        title = "Server details",
        onDismissRequest = onClose,
        dismissButton = null,
        confirmButton = { AppSecondaryButton(onClick = onClose) { Text("Close") } },
    ) {
        when (val state = loadState) {
            ServerDetailsState.Loading -> Text("Loading capabilities…")
            is ServerDetailsState.Error -> Text("Failed to load capabilities: ${state.message}")
            is ServerDetailsState.Ready -> ServerDetailsContent(snapshot = state.snapshot)
        }
    }
}

@Composable
private fun ServerDetailsContent(snapshot: UiServerCapsSnapshot) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
            Text(snapshot.name, style = MaterialTheme.typography.titleMedium)
        }
        SectionBlock("Tools") {
            if (snapshot.tools.isEmpty()) {
                SectionEmptyMessage("No tools available")
            } else {
                snapshot.tools.forEachIndexed { index, tool ->
                    CapabilityEntry(
                        title = tool.name,
                        description = tool.description?.takeIf { it.isNotBlank() } ?: "No description provided",
                        arguments = tool.arguments,
                        showDivider = index < snapshot.tools.lastIndex,
                    )
                }
            }
        }
        SectionBlock("Resources") {
            if (snapshot.resources.isEmpty()) {
                SectionEmptyMessage("No resources available")
            } else {
                snapshot.resources.forEachIndexed { index, resource ->
                    CapabilityEntry(
                        title = resource.name,
                        description = resource.description?.takeIf { it.isNotBlank() } ?: resource.key,
                        arguments = resource.arguments,
                        showDivider = index < snapshot.resources.lastIndex,
                    )
                }
            }
        }
        SectionBlock("Prompts") {
            if (snapshot.prompts.isEmpty()) {
                SectionEmptyMessage("No prompts available")
            } else {
                snapshot.prompts.forEachIndexed { index, prompt ->
                    CapabilityEntry(
                        title = prompt.name,
                        description = prompt.description?.takeIf { it.isNotBlank() } ?: "No description provided",
                        arguments = prompt.arguments,
                        showDivider = index < snapshot.prompts.lastIndex,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionBlock(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = AppTheme.shapes.item,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.sm),
                style = MaterialTheme.typography.labelLarge,
            )
            SectionDivider()
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.none),
                content = content,
            )
        }
    }
}

@Composable
private fun SectionEmptyMessage(message: String) {
    Text(
        message,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.sm),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CapabilityEntry(
    title: String,
    description: String,
    arguments: List<UiCapabilityArgument> = emptyList(),
    showDivider: Boolean,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        if (arguments.isNotEmpty()) {
            CapabilityArgumentList(
                arguments = arguments,
                modifier = Modifier.padding(top = AppTheme.spacing.xs),
            )
        }
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (showDivider) {
        SectionDivider()
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = AppTheme.spacing.md),
        thickness = AppTheme.strokeWidths.hairline,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

private sealed interface ServerDetailsState {
    object Loading : ServerDetailsState

    data class Ready(val snapshot: UiServerCapsSnapshot) : ServerDetailsState

    data class Error(val message: String) : ServerDetailsState
}
