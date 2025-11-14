package io.qent.broxy.ui.adapter.store

import io.qent.broxy.core.mcp.PromptDescriptor
import io.qent.broxy.core.mcp.ResourceDescriptor
import io.qent.broxy.core.mcp.ToolDescriptor
import io.qent.broxy.core.models.PromptReference
import io.qent.broxy.core.models.ResourceReference
import io.qent.broxy.core.models.ToolReference
import io.qent.broxy.core.utils.LogEvent
import io.qent.broxy.ui.adapter.models.UiCapabilityArgument
import io.qent.broxy.ui.adapter.models.UiHttpDraft
import io.qent.broxy.ui.adapter.models.UiHttpTransport
import io.qent.broxy.ui.adapter.models.UiLogEntry
import io.qent.broxy.ui.adapter.models.UiLogLevel
import io.qent.broxy.ui.adapter.models.UiMcpServerConfig
import io.qent.broxy.ui.adapter.models.UiPreset
import io.qent.broxy.ui.adapter.models.UiPresetCore
import io.qent.broxy.ui.adapter.models.UiPresetDraft
import io.qent.broxy.ui.adapter.models.UiPromptRef
import io.qent.broxy.ui.adapter.models.UiPromptSummary
import io.qent.broxy.ui.adapter.models.UiResourceRef
import io.qent.broxy.ui.adapter.models.UiResourceSummary
import io.qent.broxy.ui.adapter.models.UiServerCapabilities
import io.qent.broxy.ui.adapter.models.UiServerCapsSnapshot
import io.qent.broxy.ui.adapter.models.UiStdioDraft
import io.qent.broxy.ui.adapter.models.UiStdioTransport
import io.qent.broxy.ui.adapter.models.UiStreamableHttpDraft
import io.qent.broxy.ui.adapter.models.UiStreamableHttpTransport
import io.qent.broxy.ui.adapter.models.UiToolRef
import io.qent.broxy.ui.adapter.models.UiToolSummary
import io.qent.broxy.ui.adapter.models.UiTransportConfig
import io.qent.broxy.ui.adapter.models.UiTransportDraft
import io.qent.broxy.ui.adapter.models.UiWebSocketDraft
import io.qent.broxy.ui.adapter.models.UiWebSocketTransport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun UiPresetCore.toUiPresetSummary(): UiPreset = UiPreset(
    id = id,
    name = name,
    description = description.ifBlank { null },
    toolsCount = tools.count { it.enabled },
    promptsCount = prompts?.count { it.enabled } ?: 0,
    resourcesCount = resources?.count { it.enabled } ?: 0
)

internal fun UiPresetCore.toUiPresetSummary(descriptionOverride: String?): UiPreset = UiPreset(
    id = id,
    name = name,
    description = descriptionOverride ?: description.ifBlank { null },
    toolsCount = tools.count { it.enabled },
    promptsCount = prompts?.count { it.enabled } ?: 0,
    resourcesCount = resources?.count { it.enabled } ?: 0
)

internal fun UiPresetDraft.toCorePreset(): UiPresetCore = UiPresetCore(
    id = id,
    name = name,
    description = description ?: "",
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
            ResourceReference(serverId = resource.serverId, resourceKey = resource.resourceKey, enabled = resource.enabled)
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

internal fun UiServerCapabilities.toSnapshot(config: UiMcpServerConfig): UiServerCapsSnapshot = UiServerCapsSnapshot(
    serverId = config.id,
    name = config.name,
    tools = tools.map { it.toUiToolSummary() },
    prompts = prompts.map { it.toUiPromptSummary() },
    resources = resources.map { it.toUiResourceSummary() }
)

internal fun LogEvent.toUiEntry(): UiLogEntry = UiLogEntry(
    timestampMillis = timestampMillis,
    level = UiLogLevel.valueOf(level.name),
    message = message,
    throwableMessage = throwableMessage
)

private fun String?.orNullIfBlank(): String? = this?.takeIf { it.isNotBlank() }

private fun ToolDescriptor.toUiToolSummary(): UiToolSummary {
    val descriptionText = description.orNullIfBlank() ?: title.orNullIfBlank()
    val arguments = extractToolArguments()
    return UiToolSummary(
        name = name,
        description = descriptionText,
        arguments = arguments
    )
}

private fun PromptDescriptor.toUiPromptSummary(): UiPromptSummary {
    val argumentSummaries = arguments.orEmpty().map { promptArg ->
        UiCapabilityArgument(
            name = promptArg.name,
            type = "string",
            required = promptArg.required == true
        )
    }
    return UiPromptSummary(
        name = name,
        description = description.orNullIfBlank(),
        arguments = argumentSummaries
    )
}

private fun ResourceDescriptor.toUiResourceSummary(): UiResourceSummary {
    val argumentSummaries = inferResourceArguments(uri)
    return UiResourceSummary(
        key = uri.orNullIfBlank() ?: name,
        name = name,
        description = description.orNullIfBlank()
            ?: title.orNullIfBlank()
            ?: uri.orNullIfBlank(),
        arguments = argumentSummaries
    )
}

private fun ToolDescriptor.extractToolArguments(): List<UiCapabilityArgument> {
    val schema = inputSchema ?: return emptyList()
    if (schema.properties.isEmpty()) return emptyList()
    val requiredKeys = schema.required?.toSet().orEmpty()
    return schema.properties.mapNotNull { (propertyName, schemaElement) ->
        val typeLabel = schemaElement.schemaTypeLabel() ?: "unspecified"
        UiCapabilityArgument(
            name = propertyName,
            type = typeLabel,
            required = propertyName in requiredKeys
        )
    }
}

private fun JsonElement.schemaTypeLabel(): String? = when (this) {
    is JsonObject -> this.schemaTypeLabel()
    is JsonArray -> mapNotNull { it.schemaTypeLabel() }
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(" | ")
        .ifBlank { null }
    else -> null
}

private fun JsonObject.schemaTypeLabel(): String? {
    (this["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { baseType ->
        return baseType.withFormatSuffix(this)
    }
    (this["type"] as? JsonArray)?.let { array ->
        val combined = array
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" | ")
        if (combined.isNotBlank()) return combined.withFormatSuffix(this)
    }
    val items = this["items"]
    if (items != null) {
        val itemType = items.schemaTypeLabel()
        val label = if (itemType != null) "array<$itemType>" else "array"
        return label.withFormatSuffix(this)
    }
    val anyOf = this["anyOf"] as? JsonArray
    if (anyOf != null) {
        val combined = anyOf.mapNotNull { it.schemaTypeLabel() }.filter { it.isNotBlank() }
        if (combined.isNotEmpty()) return combined.joinToString(" | ").withFormatSuffix(this)
    }
    val oneOf = this["oneOf"] as? JsonArray
    if (oneOf != null) {
        val combined = oneOf.mapNotNull { it.schemaTypeLabel() }.filter { it.isNotBlank() }
        if (combined.isNotEmpty()) return combined.joinToString(" | ").withFormatSuffix(this)
    }
    val allOf = this["allOf"] as? JsonArray
    if (allOf != null) {
        val combined = allOf.mapNotNull { it.schemaTypeLabel() }.filter { it.isNotBlank() }
        if (combined.isNotEmpty()) return combined.joinToString(" & ").withFormatSuffix(this)
    }
    if (this["enum"] is JsonArray) {
        return "enum".withFormatSuffix(this)
    }
    if (this["const"] != null) {
        return "const".withFormatSuffix(this)
    }
    if (this["properties"] is JsonObject) {
        return "object".withFormatSuffix(this)
    }
    return formatSuffix(this)
}

private fun formatSuffix(node: JsonObject): String? =
    (node["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun String.withFormatSuffix(node: JsonObject): String {
    val format = formatSuffix(node)
    return if (format != null && format.isNotBlank()) "$this<$format>" else this
}

private val URI_TEMPLATE_PARAM_REGEX = Regex("\\{([^{}]+)}")

private fun inferResourceArguments(uri: String?): List<UiCapabilityArgument> {
    if (uri.isNullOrBlank()) return emptyList()
    val seen = mutableSetOf<String>()
    return URI_TEMPLATE_PARAM_REGEX.findAll(uri).mapNotNull { match ->
        val raw = match.groupValues.getOrNull(1)?.trim().orEmpty()
        if (raw.isEmpty()) return@mapNotNull null
        val breakIndex = raw.indexOfAny(charArrayOf(',', ':', '*', '?'))
        val extracted = if (breakIndex >= 0) raw.substring(0, breakIndex) else raw
        val name = extracted.trim().trimStart('+', '#', '/', '.', ';')
        if (name.isEmpty() || !seen.add(name)) return@mapNotNull null
        UiCapabilityArgument(
            name = name,
            type = "string",
            required = true
        )
    }
        .toList()
}
