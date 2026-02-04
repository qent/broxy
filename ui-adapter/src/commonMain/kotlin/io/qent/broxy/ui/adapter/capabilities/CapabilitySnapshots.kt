package io.qent.broxy.ui.adapter.capabilities

import io.qent.broxy.core.mcp.PromptDescriptor
import io.qent.broxy.core.mcp.ResourceDescriptor
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.ToolDescriptor
import io.qent.broxy.core.models.McpServerConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class ServerCapsSnapshot(
    val serverId: String,
    val name: String,
    val tools: List<ToolSummary> = emptyList(),
    val prompts: List<PromptSummary> = emptyList(),
    val resources: List<ResourceSummary> = emptyList(),
)

@Serializable
data class ToolSummary(
    val name: String,
    val description: String,
    val arguments: List<CapabilityArgument> = emptyList(),
)

@Serializable
data class PromptSummary(
    val name: String,
    val description: String,
    val arguments: List<CapabilityArgument> = emptyList(),
)

@Serializable
data class ResourceSummary(
    val key: String,
    val name: String,
    val description: String,
    val arguments: List<CapabilityArgument> = emptyList(),
)

@Serializable
data class CapabilityArgument(
    val name: String,
    val type: String = "unspecified",
    val required: Boolean = false,
)

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
    val schema = inputSchema
    val properties = schema?.properties.orEmpty()
    return if (properties.isEmpty()) {
        emptyList()
    } else {
        val requiredKeys = schema?.required.orEmpty().toSet()
        properties.mapNotNull { (propertyName, schemaElement) ->
            val typeLabel = schemaElement.schemaTypeLabel() ?: "unspecified"
            CapabilityArgument(
                name = propertyName,
                type = typeLabel,
                required = propertyName in requiredKeys,
            )
        }
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
    val placeholders =
        if (uri.isNullOrBlank()) {
            emptyList()
        } else {
            "\\{([^}]+)}".toRegex().findAll(uri).map { it.groupValues[1] }.toList()
        }
    return if (placeholders.isEmpty()) {
        emptyList()
    } else {
        placeholders.map { placeholder ->
            CapabilityArgument(
                name = placeholder,
                type = "string",
                required = true,
            )
        }
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
    val baseType =
        (this["type"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf { it.isNotBlank() }
    val arrayType =
        (this["type"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.joinToString(" | ")
            ?.takeIf { it.isNotBlank() }
    val itemsLabel =
        this["items"]
            ?.schemaTypeLabel()
            ?.let { itemType -> "array<$itemType>" }
            ?: if (this["items"] != null) "array" else null
    val anyOfLabel =
        (this["anyOf"] as? JsonArray)
            ?.mapNotNull { it.schemaTypeLabel() }
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(" | ")
    val oneOfLabel =
        (this["oneOf"] as? JsonArray)
            ?.mapNotNull { it.schemaTypeLabel() }
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(" | ")
    val allOfLabel =
        (this["allOf"] as? JsonArray)
            ?.mapNotNull { it.schemaTypeLabel() }
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(" & ")
    val enumLabel = if (this["enum"] is JsonArray) "enum" else null
    val label =
        listOf(baseType, arrayType, itemsLabel, anyOfLabel, oneOfLabel, allOfLabel, enumLabel)
            .firstOrNull { !it.isNullOrBlank() }
    return label?.withFormatSuffix(this)
}

private fun String.withFormatSuffix(schema: JsonObject): String {
    val format = (schema["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    return if (format.isNullOrBlank()) this else "$this ($format)"
}
