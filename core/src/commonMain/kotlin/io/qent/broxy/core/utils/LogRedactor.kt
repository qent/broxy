package io.qent.broxy.core.utils

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal object LogRedactor {
    private val sensitiveKeyPattern = Regex("(?i)(token|secret|password|key)")
    private val redactedValue = JsonPrimitive("***")

    fun redact(element: JsonElement?): JsonElement? =
        when (element) {
            null -> null
            is JsonObject -> redactObject(element)
            is JsonArray -> JsonArray(element.map { redact(it) ?: JsonNull })
            else -> element
        }

    private fun redactObject(obj: JsonObject): JsonObject =
        buildJsonObject {
            obj.forEach { (key, value) ->
                val redacted =
                    if (sensitiveKeyPattern.containsMatchIn(key)) {
                        redactedValue
                    } else {
                        redact(value) ?: JsonNull
                    }
                put(key, redacted)
            }
        }
}
