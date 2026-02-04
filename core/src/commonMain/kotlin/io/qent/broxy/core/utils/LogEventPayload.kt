package io.qent.broxy.core.utils

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive

internal fun JsonObjectBuilder.putIfNotNull(
    key: String,
    element: JsonElement?,
) {
    if (element != null) put(key, element)
}

internal fun JsonObjectBuilder.putRequestIdentity(
    type: LogRequestType,
    name: String,
) {
    put("requestType", JsonPrimitive(type.wireName))
    put("name", JsonPrimitive(name))
    put(type.nameKey, JsonPrimitive(name))
}

internal fun JsonObjectBuilder.putDownstreamName(
    type: LogRequestType,
    downstreamName: String,
) {
    put("downstreamName", JsonPrimitive(downstreamName))
    if (type == LogRequestType.TOOL) {
        put("downstreamTool", JsonPrimitive(downstreamName))
    }
}
