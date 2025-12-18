@file:OptIn(ExperimentalSerializationApi::class)

package io.qent.broxy.core.utils

import kotlinx.datetime.Clock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*

private object JsonLogFormatter {
    private val json = Json {
        prettyPrint = false
        encodeDefaults = false
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun format(event: String, payload: JsonObject): String {
        val body = buildJsonObject {
            put("timestamp", JsonPrimitive(Clock.System.now().toString()))
            put("event", JsonPrimitive(event))
            put("payload", payload)
        }
        return json.encodeToString(JsonObject.serializer(), body)
    }
}

private inline fun buildPayload(builder: JsonObjectBuilder.() -> Unit): JsonObject =
    buildJsonObject(builder)

fun Logger.debugJson(event: String, builder: JsonObjectBuilder.() -> Unit) {
    debug(JsonLogFormatter.format(event, buildPayload(builder)))
}

fun Logger.infoJson(event: String, builder: JsonObjectBuilder.() -> Unit) {
    info(JsonLogFormatter.format(event, buildPayload(builder)))
}

fun Logger.warnJson(event: String, throwable: Throwable? = null, builder: JsonObjectBuilder.() -> Unit) {
    warn(JsonLogFormatter.format(event, buildPayload(builder)), throwable)
}

fun Logger.errorJson(event: String, throwable: Throwable? = null, builder: JsonObjectBuilder.() -> Unit) {
    error(JsonLogFormatter.format(event, buildPayload(builder)), throwable)
}

fun JsonObjectBuilder.putIfNotNull(key: String, element: JsonElement?) {
    if (element != null) put(key, element)
}
