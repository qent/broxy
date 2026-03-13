@file:Suppress("MatchingDeclarationName")

package io.qent.broxy.ui.screens

import io.qent.broxy.ui.adapter.models.UiPromptRef
import io.qent.broxy.ui.adapter.models.UiResourceRef
import io.qent.broxy.ui.adapter.models.UiServerCapsSnapshot
import io.qent.broxy.ui.adapter.models.UiToolRef
import io.qent.broxy.ui.components.CapabilityDisplayItem
import io.qent.broxy.ui.components.matchesCapabilityQuery
import io.qent.broxy.ui.components.matchesResourceQuery
import io.qent.broxy.ui.strings.AppStrings

internal data class CapabilityDisplayContext(
    val serverNames: Map<String, String>,
    val serverCapsById: Map<String, UiServerCapsSnapshot>,
    val serverEnabledById: Map<String, Boolean>,
    val searchQuery: String,
)

internal fun buildToolCapabilityItems(
    tools: List<UiToolRef>,
    context: CapabilityDisplayContext,
    strings: AppStrings,
): List<CapabilityDisplayItem> {
    val trimmedQuery = context.searchQuery.trim()
    return tools.filter { it.enabled }.mapNotNull { ref ->
        val summary = context.serverCapsById[ref.serverId]?.tools?.firstOrNull { it.name == ref.toolName }
        val serverName = context.serverNames[ref.serverId] ?: ref.serverId
        val capabilityName = summary?.name ?: ref.toolName
        val description = summary?.description?.takeIf { it.isNotBlank() } ?: strings.noDescriptionProvided
        val arguments = summary?.arguments.orEmpty()
        val matches = matchesCapabilityQuery(trimmedQuery, capabilityName, description, arguments)
        if (!matches && trimmedQuery.isNotBlank()) return@mapNotNull null
        CapabilityDisplayItem(
            serverName = serverName,
            capabilityName = capabilityName,
            description = description,
            arguments = arguments,
            isServerEnabled = context.serverEnabledById[ref.serverId] ?: false,
        )
    }
}

internal fun buildPromptCapabilityItems(
    prompts: List<UiPromptRef>,
    context: CapabilityDisplayContext,
    strings: AppStrings,
): List<CapabilityDisplayItem> {
    val trimmedQuery = context.searchQuery.trim()
    return prompts.filter { it.enabled }.mapNotNull { ref ->
        val summary = context.serverCapsById[ref.serverId]?.prompts?.firstOrNull { it.name == ref.promptName }
        val serverName = context.serverNames[ref.serverId] ?: ref.serverId
        val capabilityName = summary?.name ?: ref.promptName
        val description = summary?.description?.takeIf { it.isNotBlank() } ?: strings.noDescriptionProvided
        val arguments = summary?.arguments.orEmpty()
        val matches = matchesCapabilityQuery(trimmedQuery, capabilityName, description, arguments)
        if (!matches && trimmedQuery.isNotBlank()) return@mapNotNull null
        CapabilityDisplayItem(
            serverName = serverName,
            capabilityName = capabilityName,
            description = description,
            arguments = arguments,
            isServerEnabled = context.serverEnabledById[ref.serverId] ?: false,
        )
    }
}

internal fun buildResourceCapabilityItems(
    resources: List<UiResourceRef>,
    context: CapabilityDisplayContext,
): List<CapabilityDisplayItem> {
    val trimmedQuery = context.searchQuery.trim()
    return resources.filter { it.enabled }.mapNotNull { ref ->
        val summary = context.serverCapsById[ref.serverId]?.resources?.firstOrNull { it.key == ref.resourceKey }
        val displayName = summary?.name?.ifBlank { ref.resourceKey } ?: ref.resourceKey
        val serverName = context.serverNames[ref.serverId] ?: ref.serverId
        val description =
            summary?.description?.takeIf { it.isNotBlank() }
                ?: summary?.key
                ?: ref.resourceKey
        val arguments = summary?.arguments.orEmpty()
        val matches = matchesResourceQuery(trimmedQuery, displayName, ref.resourceKey, description, arguments)
        if (!matches && trimmedQuery.isNotBlank()) return@mapNotNull null
        CapabilityDisplayItem(
            serverName = serverName,
            capabilityName = displayName,
            description = description,
            arguments = arguments,
            isServerEnabled = context.serverEnabledById[ref.serverId] ?: false,
        )
    }
}
