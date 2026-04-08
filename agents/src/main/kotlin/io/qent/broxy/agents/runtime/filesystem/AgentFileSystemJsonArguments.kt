package io.qent.broxy.agents.runtime.filesystem

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

internal object AgentFileSystemJsonArguments {
    fun string(
        source: JsonObject,
        key: String,
        required: Boolean = false,
        defaultValue: String? = null,
    ): String {
        val raw = stringOrNull(source, key)?.trim()
        if (!raw.isNullOrEmpty()) {
            return raw
        }
        if (required) {
            throw AgentFileSystemException(code = "invalid_argument", message = "Missing argument: $key")
        }
        return defaultValue.orEmpty()
    }

    fun stringOrNull(
        source: JsonObject,
        key: String,
    ): String? = (source[key] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }

    fun rawStringOrNull(
        source: JsonObject,
        key: String,
    ): String? = (source[key] as? JsonPrimitive)?.content

    fun int(
        source: JsonObject,
        key: String,
        defaultValue: Int,
    ): Int = intOrNull(source, key) ?: defaultValue

    fun intOrNull(
        source: JsonObject,
        key: String,
    ): Int? {
        val value = source[key] as? JsonPrimitive ?: return null
        return value.content.toIntOrNull() ?: throw AgentFileSystemException(
            code = "invalid_argument",
            message = "Argument '$key' must be an integer",
        )
    }

    fun boolean(
        source: JsonObject,
        key: String,
        defaultValue: Boolean,
    ): Boolean {
        val value = source[key] as? JsonPrimitive ?: return defaultValue
        val parsed = value.booleanOrNull
        return parsed ?: when (value.content.lowercase()) {
            "true" -> true
            "false" -> false
            else ->
                throw AgentFileSystemException(
                    code = "invalid_argument",
                    message = "Argument '$key' must be a boolean",
                )
        }
    }
}
