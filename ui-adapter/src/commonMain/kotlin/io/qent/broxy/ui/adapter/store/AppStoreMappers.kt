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

internal fun UiPresetCore.toUiPresetSummary(): UiPreset =
    UiPreset(
        id = id,
        name = name,
        toolsCount = tools.count { it.enabled },
        promptsCount = prompts?.count { it.enabled } ?: 0,
        resourcesCount = resources?.count { it.enabled } ?: 0,
    )

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
        createdAtEpochMillis = createdAtEpochMillis,
    )

internal fun UiTransportDraft.toTransportConfig(): UiTransportConfig =
    when (this) {
        is UiStdioDraft -> UiStdioTransport(command = command, args = args)
        is UiHttpDraft -> UiHttpTransport(url = url, headers = headers)
        is UiStreamableHttpDraft -> UiStreamableHttpTransport(url = url, headers = headers)
        is UiWebSocketDraft -> UiWebSocketTransport(url = url, headers = headers)
    }
