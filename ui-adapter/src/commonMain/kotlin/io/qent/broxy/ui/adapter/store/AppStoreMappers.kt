package io.qent.broxy.ui.adapter.store

import io.qent.broxy.ui.adapter.models.UiHttpDraft
import io.qent.broxy.ui.adapter.models.UiHttpTransport
import io.qent.broxy.ui.adapter.models.UiPreset
import io.qent.broxy.ui.adapter.models.UiPresetCore
import io.qent.broxy.ui.adapter.models.UiPresetDraft
import io.qent.broxy.ui.adapter.models.UiResourceRef
import io.qent.broxy.ui.adapter.models.UiStdioDraft
import io.qent.broxy.ui.adapter.models.UiStdioTransport
import io.qent.broxy.ui.adapter.models.UiStreamableHttpDraft
import io.qent.broxy.ui.adapter.models.UiStreamableHttpTransport
import io.qent.broxy.ui.adapter.models.UiToolRef
import io.qent.broxy.ui.adapter.models.UiTransportConfig
import io.qent.broxy.ui.adapter.models.UiTransportDraft
import io.qent.broxy.ui.adapter.models.UiWebSocketDraft
import io.qent.broxy.ui.adapter.models.UiWebSocketTransport

internal fun UiPresetCore.toUiPresetSummary(): UiPreset {
    val enabledToolsCount = tools.count { it.enabled }
    val enabledPromptsCount = prompts?.count { it.enabled } ?: 0
    val enabledResourcesCount = resources?.count { it.enabled } ?: 0
    val totalCapabilitiesCount = tools.size + (prompts?.size ?: 0) + (resources?.size ?: 0)
    val enabledCapabilitiesCount = enabledToolsCount + enabledPromptsCount + enabledResourcesCount

    return UiPreset(
        id = id,
        name = name,
        toolsCount = enabledToolsCount,
        allCapabilitiesDisabled = totalCapabilitiesCount > 0 && enabledCapabilitiesCount == 0,
        promptsCount = enabledPromptsCount,
        resourcesCount = enabledResourcesCount,
        toolsServerIds =
            tools
                .asSequence()
                .filter { it.enabled }
                .map { it.serverId }
                .toSet(),
        promptsServerIds =
            prompts
                ?.asSequence()
                ?.filter { it.enabled }
                ?.map { it.serverId }
                ?.toSet()
                .orEmpty(),
        resourcesServerIds =
            resources
                ?.asSequence()
                ?.filter { it.enabled }
                ?.map { it.serverId }
                ?.toSet()
                .orEmpty(),
    )
}

internal fun UiPresetDraft.toPresetCore(): UiPresetCore =
    UiPresetCore(
        id = id,
        name = name,
        tools =
            tools.map { tool ->
                UiToolRef(serverId = tool.serverId, toolName = tool.toolName, enabled = tool.enabled)
            },
        prompts =
            if (promptsConfigured) {
                prompts.map { prompt ->
                    prompt
                }
            } else {
                null
            },
        resources =
            if (resourcesConfigured) {
                resources.map { resource ->
                    UiResourceRef(
                        serverId = resource.serverId,
                        resourceKey = resource.resourceKey,
                        enabled = resource.enabled,
                    )
                }
            } else {
                null
            },
        orderIndex = orderIndex,
    )

internal fun UiTransportDraft.toTransportConfig(): UiTransportConfig =
    when (this) {
        is UiStdioDraft -> UiStdioTransport(command = command, args = args)
        is UiHttpDraft -> UiHttpTransport(url = url, headers = headers)
        is UiStreamableHttpDraft -> UiStreamableHttpTransport(url = url, headers = headers)
        is UiWebSocketDraft -> UiWebSocketTransport(url = url, headers = headers)
    }
