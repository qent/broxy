package io.qent.broxy.ui.adapter.store

import io.qent.broxy.core.models.PromptReference
import io.qent.broxy.core.models.ResourceReference
import io.qent.broxy.core.models.ToolReference
import io.qent.broxy.ui.adapter.models.*

internal fun UiPresetCore.toUiPresetSummary(): UiPreset = UiPreset(
    id = id,
    name = name,
    toolsCount = tools.count { it.enabled },
    promptsCount = prompts?.count { it.enabled } ?: 0,
    resourcesCount = resources?.count { it.enabled } ?: 0
)

internal fun UiPresetDraft.toCorePreset(): UiPresetCore = UiPresetCore(
    id = id,
    name = name,
    tools = tools.map { tool ->
        ToolReference(serverId = tool.serverId, toolName = tool.toolName, enabled = tool.enabled)
    },
    prompts = if (promptsConfigured) {
        prompts.map { prompt ->
            PromptReference(serverId = prompt.serverId, promptName = prompt.promptName, enabled = prompt.enabled)
        }
    } else {
        null
    },
    resources = if (resourcesConfigured) {
        resources.map { resource ->
            ResourceReference(
                serverId = resource.serverId,
                resourceKey = resource.resourceKey,
                enabled = resource.enabled
            )
        }
    } else {
        null
    }
)

internal fun UiTransportDraft.toTransportConfig(): UiTransportConfig = when (this) {
    is UiStdioDraft -> UiStdioTransport(command = command, args = args)
    is UiHttpDraft -> UiHttpTransport(url = url, headers = headers)
    is UiStreamableHttpDraft -> UiStreamableHttpTransport(url = url, headers = headers)
    is UiWebSocketDraft -> UiWebSocketTransport(url = url)
}
