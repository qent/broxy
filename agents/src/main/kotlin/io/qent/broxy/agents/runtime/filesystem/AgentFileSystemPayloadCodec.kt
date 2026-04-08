package io.qent.broxy.agents.runtime.filesystem

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal class AgentFileSystemPayloadCodec(
    private val json: Json = Json { prettyPrint = false },
) {
    fun success(data: JsonObject): String =
        json.encodeToString(
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("data", data)
            },
        )

    fun error(
        code: String,
        message: String,
        hint: String?,
    ): String =
        json.encodeToString(
            buildJsonObject {
                put("ok", JsonPrimitive(false))
                put("code", JsonPrimitive(code))
                put("message", JsonPrimitive(message))
                if (!hint.isNullOrBlank()) {
                    put("hint", JsonPrimitive(hint))
                }
            },
        )

    fun normalizeFailure(error: Throwable): AgentFileSystemException =
        when (error) {
            is AgentFileSystemException -> error
            is IllegalArgumentException ->
                AgentFileSystemException(
                    code = "invalid_argument",
                    message = error.message ?: "Invalid argument",
                    cause = error,
                )
            else ->
                AgentFileSystemException(
                    code = "io_error",
                    message = error.message ?: "Filesystem operation failed",
                    cause = error,
                )
        }
}
