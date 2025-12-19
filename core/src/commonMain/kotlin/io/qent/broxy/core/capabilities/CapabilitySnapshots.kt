package io.qent.broxy.core.capabilities

import io.qent.broxy.core.mcp.PromptDescriptor
import io.qent.broxy.core.mcp.ResourceDescriptor
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.ToolDescriptor
import io.qent.broxy.core.models.McpServerConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ServerCapsSnapshot(
    val serverId: String,
    val name: String,
    val tools: List<ToolSummary> = emptyList(),
    val prompts: List<PromptSummary> = emptyList(),
    val resources: List<ResourceSummary> = emptyList(),
)

data class ToolSummary(
    val name: String,
    val description: String,
    val arguments: List<CapabilityArgument> = emptyList(),
)

data class PromptSummary(
    val name: String,
    val description: String,
    val arguments: List<CapabilityArgument> = emptyList(),
)

data class ResourceSummary(
    val key: String,
    val name: String,
    val description: String,
    val arguments: List<CapabilityArgument> = emptyList(),
)

data class CapabilityArgument(
    val name: String,
    val type: String = "unspecified",
    val required: Boolean = false,
)

enum class ServerConnectionStatus {
    Disabled,
    Connecting,
    Available,
    Error,
}

fun ServerCapabilities.toSnapshot(config: McpServerConfig): ServerCapsSnapshot =
    ServerCapsSnapshot(
        serverId = config.id,
        name = config.name,
        tools = tools.map { it.toToolSummary() },
        prompts = prompts.map { it.toPromptSummary() },
        resources = resources.map { it.toResourceSummary() },
    )

private fun ToolDescriptor.toToolSummary(): ToolSummary {
    val descriptionText = description.orNullIfBlank() ?: title.orNullIfBlank()
    val arguments = extractToolArguments()
    return ToolSummary(
        name = name,
        description = descriptionText ?: "",
        arguments = arguments,
    )
}

private fun ToolDescriptor.extractToolArguments(): List<CapabilityArgument> {
    val schema = inputSchema ?: return emptyList()
    val properties = schema.properties ?: return emptyList()
    if (properties.isEmpty()) return emptyList()
    val requiredKeys = schema.required.orEmpty().toSet()
    return properties.mapNotNull { (propertyName, schemaElement) ->
        val typeLabel = schemaElement.schemaTypeLabel() ?: "unspecified"
        CapabilityArgument(
            name = propertyName,
            type = typeLabel,
            required = propertyName in requiredKeys,
        )
    }
}

private fun PromptDescriptor.toPromptSummary(): PromptSummary {
    val argumentSummaries =
        arguments.orEmpty().map { promptArg ->
            CapabilityArgument(
                name = promptArg.name,
                type = "string",
                required = promptArg.required == true,
            )
        }
    return PromptSummary(
        name = name,
        description = description ?: "",
        arguments = argumentSummaries,
    )
}

private fun ResourceDescriptor.toResourceSummary(): ResourceSummary {
    val argumentSummaries = inferResourceArguments(uri)
    return ResourceSummary(
        key = uri ?: name,
        name = name,
        description =
            description.orNullIfBlank()
                ?: title.orNullIfBlank()
                ?: uri.orNullIfBlank()
                ?: "",
        arguments = argumentSummaries,
    )
}

private fun String?.orNullIfBlank(): String? = this?.takeIf { it.isNotBlank() }

private fun inferResourceArguments(uri: String?): List<CapabilityArgument> {
    if (uri.isNullOrBlank()) return emptyList()
    val placeholders = "\\{([^}]+)}".toRegex().findAll(uri).map { it.groupValues[1] }.toList()
    if (placeholders.isEmpty()) return emptyList()
    return placeholders.map { placeholder ->
        CapabilityArgument(
            name = placeholder,
            type = "string",
            required = true,
        )
    }
}

private fun JsonElement.schemaTypeLabel(): String? =
    when (this) {
        is JsonObject -> this.schemaTypeLabel()
        is JsonArray ->
            mapNotNull { it.schemaTypeLabel() }
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
        val combined =
            array
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
    return null
}

private fun String.withFormatSuffix(schema: JsonObject): String {
    val format = (schema["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    return if (format.isNullOrBlank()) this else "$this ($format)"
}
