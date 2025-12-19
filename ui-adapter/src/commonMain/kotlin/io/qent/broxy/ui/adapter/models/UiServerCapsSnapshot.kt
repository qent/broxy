package io.qent.broxy.ui.adapter.models

import io.qent.broxy.core.capabilities.CapabilityArgument
import io.qent.broxy.core.capabilities.PromptSummary
import io.qent.broxy.core.capabilities.ResourceSummary
import io.qent.broxy.core.capabilities.ServerCapsSnapshot
import io.qent.broxy.core.capabilities.ToolSummary

data class UiServerCapsSnapshot(
    val serverId: String,
    val name: String,
    val tools: List<UiToolSummary> = emptyList(),
    val prompts: List<UiPromptSummary> = emptyList(),
    val resources: List<UiResourceSummary> = emptyList(),
)

data class UiToolSummary(
    val name: String,
    val description: String,
    val arguments: List<UiCapabilityArgument> = emptyList(),
)

data class UiPromptSummary(
    val name: String,
    val description: String,
    val arguments: List<UiCapabilityArgument> = emptyList(),
)

data class UiResourceSummary(
    val key: String,
    val name: String,
    val description: String,
    val arguments: List<UiCapabilityArgument> = emptyList(),
)

data class UiCapabilityArgument(
    val name: String,
    val type: String = "unspecified",
    val required: Boolean = false,
)

internal fun ServerCapsSnapshot.toUiModel(): UiServerCapsSnapshot =
    UiServerCapsSnapshot(
        serverId = serverId,
        name = name,
        tools = tools.map { it.toUiModel() },
        prompts = prompts.map { it.toUiModel() },
        resources = resources.map { it.toUiModel() },
    )

private fun ToolSummary.toUiModel(): UiToolSummary =
    UiToolSummary(
        name = name,
        description = description,
        arguments = arguments.map { it.toUiModel() },
    )

private fun PromptSummary.toUiModel(): UiPromptSummary =
    UiPromptSummary(
        name = name,
        description = description,
        arguments = arguments.map { it.toUiModel() },
    )

private fun ResourceSummary.toUiModel(): UiResourceSummary =
    UiResourceSummary(
        key = key,
        name = name,
        description = description,
        arguments = arguments.map { it.toUiModel() },
    )

private fun CapabilityArgument.toUiModel(): UiCapabilityArgument =
    UiCapabilityArgument(
        name = name,
        type = type,
        required = required,
    )
