@file:Suppress("FunctionNaming")

package io.qent.broxy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.UiAgent
import io.qent.broxy.ui.adapter.models.UiServerCapsSnapshot
import io.qent.broxy.ui.adapter.store.AppStore
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.components.AppVerticalScrollbar
import io.qent.broxy.ui.components.CapabilitiesCard
import io.qent.broxy.ui.components.CapabilitiesInlineSummary
import io.qent.broxy.ui.components.FormCard
import io.qent.broxy.ui.components.SearchField
import io.qent.broxy.ui.components.SearchFieldFabAlignedBottomPadding
import io.qent.broxy.ui.strings.LocalStrings
import io.qent.broxy.ui.theme.AppTheme

private val SYSTEM_PROMPT_MAX_HEIGHT = 400.dp

@Composable
fun AgentDetailsScreen(
    ui: UIState,
    store: AppStore,
    agentId: String,
    onClose: () -> Unit,
) {
    val strings = LocalStrings.current
    val readyUi = ui as? UIState.Ready
    val agent = readyUi?.agents?.firstOrNull { it.id == agentId }

    if (agent == null) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
        ) {
            Spacer(Modifier.height(AppTheme.spacing.xs))
            AgentDetailsHeaderRow(agentName = agentId, agent = null, onBack = onClose)
            Text(
                text = strings.agentNotFound,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        return
    }

    var query by rememberSaveable(agentId) { mutableStateOf("") }
    val capabilitiesState =
        rememberAgentCapabilitiesLoadState(
            store = store,
            agentId = agentId,
            errorMessageFallback = strings.couldNotLoadCapabilities,
        )

    AgentDetailsCapabilitiesSection(
        state = capabilitiesState,
        agent = agent,
        readyUi = readyUi,
        searchQuery = query,
        onQueryChange = { query = it },
        onClose = onClose,
    )
}

@Composable
@Suppress("LongParameterList")
private fun AgentDetailsCapabilitiesSection(
    state: AgentCapabilitiesLoadState,
    agent: UiAgent,
    readyUi: UIState.Ready?,
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    when (state) {
        AgentCapabilitiesLoadState.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        is AgentCapabilitiesLoadState.Error -> {
            AgentDetailsContent(
                agent = agent,
                readyUi = readyUi,
                capabilities = emptyList(),
                errorMessage = state.message,
                searchQuery = searchQuery,
                onQueryChange = onQueryChange,
                onClose = onClose,
            )
        }
        is AgentCapabilitiesLoadState.Ready -> {
            AgentDetailsContent(
                agent = agent,
                readyUi = readyUi,
                capabilities = state.capabilities,
                errorMessage = null,
                searchQuery = searchQuery,
                onQueryChange = onQueryChange,
                onClose = onClose,
            )
        }
    }
}

@Composable
private fun rememberAgentCapabilitiesLoadState(
    store: AppStore,
    agentId: String,
    errorMessageFallback: String,
): AgentCapabilitiesLoadState {
    val capabilitiesState by
        produceState<AgentCapabilitiesLoadState>(initialValue = AgentCapabilitiesLoadState.Loading, agentId) {
            value = AgentCapabilitiesLoadState.Loading
            value =
                runCatching { store.listSelectableServerCaps() }
                    .fold(
                        onSuccess = { AgentCapabilitiesLoadState.Ready(it) },
                        onFailure = {
                            AgentCapabilitiesLoadState.Error(
                                it.message ?: errorMessageFallback,
                            )
                        },
                    )
        }
    return capabilitiesState
}

@Composable
@Suppress("LongMethod", "LongParameterList")
private fun AgentDetailsContent(
    agent: UiAgent,
    readyUi: UIState.Ready?,
    capabilities: List<UiServerCapsSnapshot>,
    errorMessage: String?,
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val strings = LocalStrings.current
    val scrollState = rememberScrollState()
    val promptScrollState = rememberScrollState()
    val trimmedQuery = searchQuery.trim()
    val serverNamesById =
        remember(readyUi?.servers) {
            readyUi?.servers?.associate { it.id to it.name }.orEmpty()
        }
    val serverEnabledById =
        remember(readyUi?.servers) {
            readyUi?.servers?.associate { it.id to it.enabled }.orEmpty()
        }
    val serverCapsById = remember(capabilities) { capabilities.associateBy { it.serverId } }
    val displayContext =
        remember(serverNamesById, serverCapsById, serverEnabledById, trimmedQuery) {
            CapabilityDisplayContext(
                serverNames = serverNamesById,
                serverCapsById = serverCapsById,
                serverEnabledById = serverEnabledById,
                searchQuery = trimmedQuery,
            )
        }

    val toolItems =
        remember(agent.tools, displayContext, strings) {
            buildToolCapabilityItems(
                tools = agent.tools,
                context = displayContext,
                strings = strings,
            )
        }
    val promptItems =
        remember(agent.prompts, displayContext, strings) {
            buildPromptCapabilityItems(
                prompts = agent.prompts,
                context = displayContext,
                strings = strings,
            )
        }
    val resourceItems =
        remember(agent.resources, displayContext) {
            buildResourceCapabilityItems(
                resources = agent.resources,
                context = displayContext,
            )
        }

    val hasMatches = toolItems.isNotEmpty() || promptItems.isNotEmpty() || resourceItems.isNotEmpty()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
        ) {
            Spacer(Modifier.height(AppTheme.spacing.xs))

            AgentDetailsHeaderRow(agentName = agent.name, agent = agent, onBack = onClose)

            FormCard(title = strings.agentDescriptionLabel) {
                Text(
                    text = agent.description?.takeIf { it.isNotBlank() } ?: strings.noDescriptionProvided,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FormCard(title = strings.systemPromptLabel) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = SYSTEM_PROMPT_MAX_HEIGHT)
                            .verticalScroll(promptScrollState),
                ) {
                    Text(
                        text = agent.systemPrompt.ifBlank { strings.noDescriptionProvided },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            when {
                errorMessage != null ->
                    Text(
                        text = strings.errorMessage(errorMessage),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = AppTheme.spacing.md),
                    )

                !hasMatches && trimmedQuery.isBlank() ->
                    Text(
                        text = strings.noCapabilitiesExposed,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = AppTheme.spacing.md),
                    )

                hasMatches -> {
                    CapabilitiesCard(
                        title = strings.toolsLabel,
                        items = toolItems,
                        icon = Icons.Outlined.Construction,
                        highlightQuery = trimmedQuery,
                    )
                    CapabilitiesCard(
                        title = strings.promptsLabel,
                        items = promptItems,
                        icon = Icons.Outlined.ChatBubbleOutline,
                        highlightQuery = trimmedQuery,
                    )
                    CapabilitiesCard(
                        title = strings.resourcesLabel,
                        items = resourceItems,
                        icon = Icons.Outlined.Description,
                        highlightQuery = trimmedQuery,
                    )
                }
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

        if (errorMessage == null) {
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
}

@Composable
private fun AgentDetailsHeaderRow(
    agentName: String,
    agent: UiAgent?,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = strings.back)
        }
        Text(
            text = agentName,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (agent != null) {
            CapabilitiesInlineSummary(
                toolsCount = agent.toolsCount,
                promptsCount = agent.promptsCount,
                resourcesCount = agent.resourcesCount,
            )
        }
    }
}

private sealed interface AgentCapabilitiesLoadState {
    data object Loading : AgentCapabilitiesLoadState

    data class Error(
        val message: String,
    ) : AgentCapabilitiesLoadState

    data class Ready(
        val capabilities: List<UiServerCapsSnapshot>,
    ) : AgentCapabilitiesLoadState
}
