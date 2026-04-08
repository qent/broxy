package io.qent.broxy.core.proxy.inbound

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ContentTypes
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

internal fun decodeCallToolResult(
    json: Json,
    element: JsonElement,
): CallToolResult =
    decodeWithNormalization(
        json = json,
        element = element,
        serializer = CallToolResult.serializer(),
        normalize = ::normalizeCallToolResult,
    )

internal fun decodePromptResult(
    json: Json,
    element: JsonElement,
): GetPromptResult =
    decodeWithNormalization(
        json = json,
        element = element,
        serializer = GetPromptResult.serializer(),
        normalize = ::normalizePromptResult,
    )

internal fun fallbackCallToolResult(raw: JsonElement): CallToolResult {
    val rawObject = raw as? JsonObject
    val structured =
        when (val sc = rawObject?.get("structuredContent")) {
            is JsonObject -> sc
            else -> rawObject ?: JsonObject(mapOf("raw" to raw))
        }
    val meta =
        (rawObject?.get("_meta") as? JsonObject)
            ?: (rawObject?.get("meta") as? JsonObject)
            ?: JsonObject(emptyMap())
    val isError = rawObject?.get("isError")?.jsonPrimitive?.booleanOrNull ?: false
    val contentArray = rawObject?.get("content") as? JsonArray
    val fallbackContent =
        contentArray
            ?.mapNotNull { it.toTextContentOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(TextContent(text = raw.toString()))
    return CallToolResult(
        content = fallbackContent,
        structuredContent = structured,
        isError = isError,
        meta = meta,
    )
}

private fun normalizeCallToolResult(original: JsonObject): JsonObject? {
    val content = original["content"] as? JsonArray ?: return null
    var changed = false
    val normalizedContent =
        JsonArray(
            content.map { item ->
                val normalized = normalizeContentElement(item)
                if (normalized != null) {
                    changed = true
                    normalized
                } else {
                    item
                }
            },
        )
    return if (changed) JsonObject(original + ("content" to normalizedContent)) else null
}

private fun normalizePromptResult(original: JsonObject): JsonObject? {
    val messages = original["messages"] as? JsonArray ?: return null
    var changed = false
    val normalizedMessages =
        JsonArray(
            messages.map { messageElement ->
                val messageObject = messageElement as? JsonObject ?: return@map messageElement
                val currentContent = messageObject["content"]
                val normalizedContent = currentContent?.let { normalizeContentElement(it) }
                if (normalizedContent != null) {
                    changed = true
                    JsonObject(messageObject + ("content" to normalizedContent))
                } else {
                    messageElement
                }
            },
        )
    return if (changed) JsonObject(original + ("messages" to normalizedMessages)) else null
}

private fun normalizeContentElement(element: JsonElement): JsonElement? =
    when (element) {
        is JsonObject -> addTypeIfMissing(element)
        is JsonArray -> {
            var changed = false
            val normalizedItems =
                element.map { entry ->
                    val obj = entry as? JsonObject ?: return@map entry
                    val normalized = addTypeIfMissing(obj)
                    if (normalized != null) {
                        changed = true
                        normalized
                    } else {
                        obj
                    }
                }
            if (changed) JsonArray(normalizedItems) else null
        }

        else -> null
    }

private fun addTypeIfMissing(obj: JsonObject): JsonObject? {
    val inferredType =
        if ("type" in obj) {
            null
        } else {
            inferContentType(obj)
        }
    return if (inferredType == null) {
        null
    } else {
        JsonObject(obj + ("type" to JsonPrimitive(inferredType.value)))
    }
}

private fun <T> decodeWithNormalization(
    json: Json,
    element: JsonElement,
    serializer: KSerializer<T>,
    normalize: (JsonObject) -> JsonObject?,
): T =
    try {
        json.decodeFromJsonElement(serializer, element)
    } catch (original: SerializationException) {
        val normalized = (element as? JsonObject)?.let(normalize)
        if (normalized != null) {
            json.decodeFromJsonElement(serializer, normalized)
        } else {
            throw original
        }
    } catch (original: IllegalArgumentException) {
        val normalized = (element as? JsonObject)?.let(normalize)
        if (normalized != null) {
            json.decodeFromJsonElement(serializer, normalized)
        } else {
            throw original
        }
    }

private fun inferContentType(obj: JsonObject): ContentTypes? =
    when {
        "type" in obj ->
            obj["type"]?.jsonPrimitive?.content?.let { typeValue ->
                ContentTypes.entries.firstOrNull { it.value == typeValue }
            }

        "text" in obj -> ContentTypes.TEXT
        "image" in obj -> ContentTypes.IMAGE
        "data" in obj && obj["mimeType"]?.jsonPrimitive?.content?.startsWith("image/") == true -> ContentTypes.IMAGE
        "audio" in obj -> ContentTypes.AUDIO
        "data" in obj && obj["mimeType"]?.jsonPrimitive?.content?.startsWith("audio/") == true -> ContentTypes.AUDIO
        "resource" in obj -> ContentTypes.EMBEDDED_RESOURCE
        else -> null
    }

private fun JsonElement.toTextContentOrNull(): TextContent? =
    when (this) {
        is JsonPrimitive -> TextContent(text = if (isString) content else toString())
        is JsonObject -> {
            val textNode = this["text"]
            val textValue =
                when (textNode) {
                    is JsonPrimitive -> if (textNode.isString) textNode.content else textNode.toString()
                    null -> toString()
                    else -> textNode.toString()
                }
            TextContent(text = textValue)
        }

        else -> null
    }
