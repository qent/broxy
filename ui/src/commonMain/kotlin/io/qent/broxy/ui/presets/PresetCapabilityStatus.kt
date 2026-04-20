package io.qent.broxy.ui.presets

import io.qent.broxy.ui.adapter.models.UiPreset

data class PresetCapabilityStatus(
    val hasUnavailableTools: Boolean,
    val hasUnavailablePrompts: Boolean,
    val hasUnavailableResources: Boolean,
    val hasNoAvailableCapabilities: Boolean,
) {
    val hasCapabilityWarning: Boolean
        get() =
            hasUnavailableTools ||
                hasUnavailablePrompts ||
                hasUnavailableResources ||
                hasNoAvailableCapabilities
}

fun resolvePresetCapabilityStatus(
    preset: UiPreset,
    enabledServerIds: Set<String>,
): PresetCapabilityStatus {
    val hasUnavailableTools = preset.toolsServerIds.any { it !in enabledServerIds }
    val hasUnavailablePrompts = preset.promptsServerIds.any { it !in enabledServerIds }
    val hasUnavailableResources = preset.resourcesServerIds.any { it !in enabledServerIds }
    val hasEnabledCapabilities = preset.toolsCount + preset.promptsCount + preset.resourcesCount > 0
    val hasAvailableCapabilities =
        hasAvailableCapabilities(
            count = preset.toolsCount,
            serverIds = preset.toolsServerIds,
            enabledServerIds = enabledServerIds,
        ) ||
            hasAvailableCapabilities(
                count = preset.promptsCount,
                serverIds = preset.promptsServerIds,
                enabledServerIds = enabledServerIds,
            ) ||
            hasAvailableCapabilities(
                count = preset.resourcesCount,
                serverIds = preset.resourcesServerIds,
                enabledServerIds = enabledServerIds,
            )
    val hasNoAvailableCapabilities =
        preset.allCapabilitiesDisabled ||
            !hasEnabledCapabilities ||
            (hasEnabledCapabilities && !hasAvailableCapabilities)
    return PresetCapabilityStatus(
        hasUnavailableTools = hasUnavailableTools,
        hasUnavailablePrompts = hasUnavailablePrompts,
        hasUnavailableResources = hasUnavailableResources,
        hasNoAvailableCapabilities = hasNoAvailableCapabilities,
    )
}

private fun hasAvailableCapabilities(
    count: Int,
    serverIds: Set<String>,
    enabledServerIds: Set<String>,
): Boolean = count > 0 && serverIds.any(enabledServerIds::contains)
