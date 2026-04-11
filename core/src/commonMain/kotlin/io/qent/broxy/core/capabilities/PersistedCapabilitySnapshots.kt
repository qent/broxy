package io.qent.broxy.core.capabilities

import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.ToolDescriptor
import io.qent.broxy.core.models.McpServerConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class PersistedCapabilityCacheEntry(
    val serverId: String,
    val timestampMillis: Long,
    val snapshot: PersistedServerCapsSnapshot,
)

@Serializable
data class PersistedServerCapsSnapshot(
    val serverId: String,
    val name: String,
    val tools: List<PersistedToolSummary> = emptyList(),
    val prompts: List<PersistedPromptSummary> = emptyList(),
    val resources: List<PersistedResourceSummary> = emptyList(),
)

@Serializable
data class PersistedToolSummary(
    val name: String,
    val description: String,
    val arguments: List<PersistedCapabilityArgument> = emptyList(),
)

@Serializable
data class PersistedPromptSummary(
    val name: String,
    val description: String,
    val arguments: List<PersistedCapabilityArgument> = emptyList(),
)

@Serializable
data class PersistedResourceSummary(
    val key: String,
    val name: String,
    val description: String,
    val arguments: List<PersistedCapabilityArgument> = emptyList(),
)

@Serializable
data class PersistedCapabilityArgument(
    val name: String,
    val type: String = "unspecified",
    val required: Boolean = false,
)

fun ServerCapabilities.toPersistedSnapshot(config: McpServerConfig): PersistedServerCapsSnapshot =
    toPersistedSnapshot(
        serverId = config.id,
        serverName = config.name,
    )

fun ServerCapabilities.toPersistedSnapshot(
    serverId: String,
    serverName: String,
): PersistedServerCapsSnapshot =
    PersistedServerCapsSnapshot(
        serverId = serverId,
        name = serverName,
        tools = tools.map { it.toPersistedToolSummary() },
        prompts =
            prompts.map { prompt ->
                PersistedPromptSummary(
                    name = prompt.name,
                    description = prompt.description.orNullIfBlank() ?: "",
                    arguments =
                        prompt
                            .arguments
                            .orEmpty()
                            .map { argument ->
                                PersistedCapabilityArgument(
                                    name = argument.name,
                                    type = "string",
                                    required = argument.required == true,
                                )
                            },
                )
            },
        resources =
            resources.map { resource ->
                PersistedResourceSummary(
                    key = resource.uri ?: resource.name,
                    name = resource.name,
                    description =
                        resource.description.orNullIfBlank()
                            ?: resource.title.orNullIfBlank()
                            ?: resource.uri.orNullIfBlank()
                            ?: "",
                    arguments = inferResourceArguments(resource.uri),
                )
            },
    )

private fun ToolDescriptor.toPersistedToolSummary(): PersistedToolSummary {
    val descriptionText = description.orNullIfBlank() ?: title.orNullIfBlank()
    val arguments = extractToolArguments()
    return PersistedToolSummary(
        name = name,
        description = descriptionText ?: "",
        arguments = arguments,
    )
}

private fun ToolDescriptor.extractToolArguments(): List<PersistedCapabilityArgument> {
    val schema = inputSchema
    val properties = schema?.properties.orEmpty()
    return if (properties.isEmpty()) {
        emptyList()
    } else {
        val requiredKeys = schema?.required.orEmpty().toSet()
        properties.mapNotNull { (propertyName, schemaElement) ->
            val typeLabel = schemaElement.schemaTypeLabel() ?: "unspecified"
            PersistedCapabilityArgument(
                name = propertyName,
                type = typeLabel,
                required = propertyName in requiredKeys,
            )
        }
    }
}

private fun String?.orNullIfBlank(): String? = this?.takeIf { it.isNotBlank() }

private fun inferResourceArguments(uri: String?): List<PersistedCapabilityArgument> {
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
            PersistedCapabilityArgument(
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
