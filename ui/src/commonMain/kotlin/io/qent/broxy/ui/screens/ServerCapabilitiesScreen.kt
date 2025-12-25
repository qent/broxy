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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import io.qent.broxy.ui.adapter.models.UiServerCapsSnapshot
import io.qent.broxy.ui.adapter.store.AppStore
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.components.AppVerticalScrollbar
import io.qent.broxy.ui.components.CapabilitiesCard
import io.qent.broxy.ui.components.CapabilitiesInlineSummary
import io.qent.broxy.ui.components.CapabilityDisplayItem
import io.qent.broxy.ui.components.SearchField
import io.qent.broxy.ui.components.SearchFieldFabAlignedBottomPadding
import io.qent.broxy.ui.components.matchesCapabilityQuery
import io.qent.broxy.ui.components.matchesResourceQuery
import io.qent.broxy.ui.strings.LocalStrings
import io.qent.broxy.ui.theme.AppTheme

@Composable
fun ServerCapabilitiesScreen(
    store: AppStore,
    serverId: String,
    onClose: () -> Unit,
) {
    val strings = LocalStrings.current
    val ui = store.state.collectAsState().value
    val serverName =
        (ui as? UIState.Ready)?.servers?.find { it.id == serverId }?.name ?: strings.serverFallbackName

    var capabilities by remember { mutableStateOf<UiServerCapsSnapshot?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var query by rememberSaveable { mutableStateOf("") }

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
                    Text(strings.couldNotLoadCapabilities, color = MaterialTheme.colorScheme.error)
                }
            }
        } else {
            CapabilitiesContent(caps, serverName, query, onQueryChange = { query = it }, onClose = onClose)
        }
    }
}

@Composable
private fun CapabilitiesContent(
    caps: UiServerCapsSnapshot,
    serverName: String,
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val strings = LocalStrings.current
    val scrollState = rememberScrollState()
    val trimmedQuery = searchQuery.trim()

    val toolsItems =
        remember(caps.tools, trimmedQuery) {
            caps.tools.filter { tool ->
                matchesCapabilityQuery(trimmedQuery, tool.name, tool.description, tool.arguments)
            }.map { tool ->
                CapabilityDisplayItem(
                    serverName = serverName,
                    capabilityName = tool.name,
                    description = tool.description,
                    arguments = tool.arguments,
                )
            }
        }

    val promptsItems =
        remember(caps.prompts, trimmedQuery) {
            caps.prompts.filter { prompt ->
                matchesCapabilityQuery(trimmedQuery, prompt.name, prompt.description, prompt.arguments)
            }.map { prompt ->
                CapabilityDisplayItem(
                    serverName = serverName,
                    capabilityName = prompt.name,
                    description = prompt.description,
                    arguments = prompt.arguments,
                )
            }
        }

    val resourcesItems =
        remember(caps.resources, trimmedQuery) {
            caps.resources.filter { res ->
                matchesResourceQuery(trimmedQuery, res.name, res.key, res.description, res.arguments)
            }.map { res ->
                CapabilityDisplayItem(
                    serverName = serverName,
                    capabilityName = res.name.ifBlank { res.key },
                    description = res.description.ifBlank { res.key },
                    arguments = res.arguments,
                )
            }
        }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
        ) {
            Spacer(Modifier.height(AppTheme.spacing.xs))

            HeaderRow(
                title = serverName,
                onBack = onClose,
                caps = caps,
            )

            val hasMatches = toolsItems.isNotEmpty() || promptsItems.isNotEmpty() || resourcesItems.isNotEmpty()
            if (!hasMatches && trimmedQuery.isBlank()) {
                Text(
                    strings.noCapabilitiesExposed,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = AppTheme.spacing.sm, start = AppTheme.spacing.md),
                )
            } else if (hasMatches) {
                CapabilitiesCard(
                    title = strings.toolsLabel,
                    items = toolsItems,
                    icon = Icons.Outlined.Construction,
                    showServerName = false,
                    highlightQuery = trimmedQuery,
                )
                CapabilitiesCard(
                    title = strings.promptsLabel,
                    items = promptsItems,
                    icon = Icons.Outlined.ChatBubbleOutline,
                    showServerName = false,
                    highlightQuery = trimmedQuery,
                )
                CapabilitiesCard(
                    title = strings.resourcesLabel,
                    items = resourcesItems,
                    icon = Icons.Outlined.Description,
                    showServerName = false,
                    highlightQuery = trimmedQuery,
                )
            }

            Spacer(Modifier.height(AppTheme.spacing.fab))
        }

        AppVerticalScrollbar(
            scrollState = scrollState,
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .offset(x = AppTheme.spacing.md - AppTheme.strokeWidths.hairline),
        )

        SearchField(
            value = searchQuery,
            onValueChange = onQueryChange,
            placeholder = strings.searchCapabilities,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = SearchFieldFabAlignedBottomPadding),
        )
    }
}

@Composable
private fun HeaderRow(
    title: String,
    onBack: () -> Unit,
    caps: UiServerCapsSnapshot? = null,
) {
    val strings = LocalStrings.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = strings.back)
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        if (caps != null) {
            CapabilitiesInlineSummary(
                toolsCount = caps.tools.size,
                promptsCount = caps.prompts.size,
                resourcesCount = caps.resources.size,
            )
        }
    }
}
