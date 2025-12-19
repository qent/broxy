package io.qent.broxy.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import io.qent.broxy.ui.adapter.models.UiServerCapsSnapshot
import io.qent.broxy.ui.adapter.store.AppStore
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.components.CapabilitiesCard
import io.qent.broxy.ui.components.CapabilitiesInlineSummary
import io.qent.broxy.ui.components.CapabilityDisplayItem
import io.qent.broxy.ui.theme.AppTheme

@Composable
fun ServerCapabilitiesScreen(
    store: AppStore,
    serverId: String,
    onClose: () -> Unit
) {
    val ui = store.state.collectAsState().value
    val serverName = (ui as? UIState.Ready)?.servers?.find { it.id == serverId }?.name ?: "Server"

    var capabilities by remember { mutableStateOf<UiServerCapsSnapshot?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(serverId) {
        val caps = store.getServerCaps(serverId)
        capabilities = caps
        isLoading = false
    }

    if (isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        val caps = capabilities
        if (caps == null) {
            Column(Modifier.fillMaxSize()) {
                HeaderRow(title = serverName, onBack = onClose)
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Could not load capabilities", color = MaterialTheme.colorScheme.error)
                }
            }
        } else {
            CapabilitiesContent(caps, serverName, onClose)
        }
    }
}

@Composable
private fun CapabilitiesContent(
    caps: UiServerCapsSnapshot,
    serverName: String,
    onClose: () -> Unit
) {
    val scrollState = rememberScrollState()

    val toolsItems = remember(caps.tools) {
        caps.tools.map { tool ->
            CapabilityDisplayItem(
                serverName = serverName,
                capabilityName = tool.name,
                description = tool.description,
                arguments = tool.arguments
            )
        }
    }

    val promptsItems = remember(caps.prompts) {
        caps.prompts.map { prompt ->
            CapabilityDisplayItem(
                serverName = serverName,
                capabilityName = prompt.name,
                description = prompt.description,
                arguments = prompt.arguments
            )
        }
    }

    val resourcesItems = remember(caps.resources) {
        caps.resources.map { res ->
            CapabilityDisplayItem(
                serverName = serverName,
                capabilityName = res.name.ifBlank { res.key },
                description = res.description.ifBlank { res.key },
                arguments = listOf()
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md)
    ) {
        Spacer(Modifier.height(AppTheme.spacing.xs))

        HeaderRow(
            title = serverName,
            onBack = onClose,
            caps = caps
        )

        if (toolsItems.isEmpty() && promptsItems.isEmpty() && resourcesItems.isEmpty()) {
            Text(
                "No capabilities exposed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = AppTheme.spacing.sm, start = AppTheme.spacing.md)
            )
        } else {
            CapabilitiesCard(
                title = "Tools",
                items = toolsItems,
                icon = Icons.Outlined.Construction,
                showServerName = false
            )
            CapabilitiesCard(
                title = "Prompts",
                items = promptsItems,
                icon = Icons.Outlined.ChatBubbleOutline,
                showServerName = false
            )
            CapabilitiesCard(
                title = "Resources",
                items = resourcesItems,
                icon = Icons.Outlined.Description,
                showServerName = false
            )
        }
        Spacer(Modifier.height(AppTheme.spacing.xl))
    }
}

@Composable
private fun HeaderRow(
    title: String,
    onBack: () -> Unit,
    caps: UiServerCapsSnapshot? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
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
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        if (caps != null) {
            CapabilitiesInlineSummary(
                toolsCount = caps.tools.size,
                promptsCount = caps.prompts.size,
                resourcesCount = caps.resources.size
            )
        }
    }
}
