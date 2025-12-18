package io.qent.broxy.ui.adapter.remote.ws

import io.modelcontextprotocol.kotlin.sdk.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.McpJson
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.qent.broxy.core.utils.CollectingLogger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class McpProxyRequestPayload(
    @SerialName("session_identifier")
    val sessionIdentifier: String,
    val message: JsonElement
)

@Serializable
data class McpProxyResponsePayload(
    @SerialName("session_identifier")
    val sessionIdentifier: String,
    @SerialName("target_server_identifier")
    val targetServerIdentifier: String,
    val message: JsonElement
)

/**
 * Transport adapter that bridges MCP JSON-RPC messages to the backend proxy envelope.
 */
class ProxyWebSocketTransport(
    private val serverIdentifier: String,
    private val logger: CollectingLogger,
    private val sender: suspend (McpProxyResponsePayload) -> Unit
) : AbstractTransport() {

    @Volatile
    private var sessionIdentifier: String? = null

    override suspend fun start() {
        // No-op: managed by the WebSocket manager.
    }

    suspend fun handleIncoming(message: JSONRPCMessage, sessionId: String) {
        if (sessionIdentifier == null || sessionIdentifier != sessionId) {
            sessionIdentifier = sessionId
        }
        _onMessage.invoke(message)
    }

    override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
        val sessionId = sessionIdentifier
            ?: error("session identifier is not available; cannot send response")
        val messageElement = McpJson.encodeToJsonElement(JSONRPCMessage.serializer(), message)
        val payload = McpProxyResponsePayload(
            sessionIdentifier = sessionId,
            targetServerIdentifier = serverIdentifier,
            message = messageElement
        )
        logger.info(
            "[RemoteWsClient] Outbound message session=$sessionId target=$serverIdentifier ${
                describeJsonRpcPayload(
                    messageElement
                )
            }"
        )
        sender(payload)
    }

    override suspend fun close() {
        _onClose.invoke()
    }
}

internal fun describeJsonRpcPayload(element: JsonElement): String {
    val obj = element as? JsonObject ?: return "payload=non-object"
    val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: obj["id"]?.toString()
    val method = obj["method"]?.jsonPrimitive?.contentOrNull
    val hasResult = obj.containsKey("result")
    val errorMessage = (obj["error"] as? JsonObject)
        ?.get("message")
        ?.jsonPrimitive
        ?.contentOrNull
    val paramsKeys = (obj["params"] as? JsonObject)
        ?.keys
        ?.takeIf { it.isNotEmpty() }
        ?.joinToString(",")
    val type = when {
        method != null && id != null -> "request"
        method != null -> "notification"
        hasResult -> "response"
        errorMessage != null -> "error"
        else -> "message"
    }
    return listOfNotNull(
        "type=$type",
        id?.let { "id=$it" },
        method?.let { "method=$it" },
        paramsKeys?.let { "params=$it" },
        describeParams(obj["params"] as? JsonObject),
        describeResult(obj["result"]),
        hasResult.takeIf { it }?.let { "has_result=true" },
        errorMessage?.let { "error=$it" }
    ).joinToString(" ")
}

private fun describeParams(params: JsonObject?): String? {
    if (params == null) return null
    val target = params.field("name", "uri")?.jsonPrimitive?.contentOrNull
    val argumentKeys = (params["arguments"] as? JsonObject)
        ?.keys
        ?.takeIf { it.isNotEmpty() }
        ?.toList()
    val metaKeys = (params["meta"] as? JsonObject)
        ?.keys
        ?.takeIf { it.isNotEmpty() }
        ?.toList()
    val parts = mutableListOf<String>()
    target?.let { parts += "target=$it" }
    argumentKeys?.let { parts += "args=${preview(it)}" }
    metaKeys?.let { parts += "meta=${preview(it)}" }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(prefix = "params{", postfix = "}")
}

private fun describeResult(result: JsonElement?): String? {
    val obj = result as? JsonObject ?: return null
    describeCapabilities(obj)?.let { return it }
    return describeCallResult(obj)
}

private fun describeCapabilities(obj: JsonObject): String? {
    val tools = obj["tools"] as? JsonArray
    val prompts = obj["prompts"] as? JsonArray
    val resources = obj["resources"] as? JsonArray
    if (tools == null && prompts == null && resources == null) return null
    val parts = mutableListOf<String>()
    tools?.let {
        parts += "tools=${it.size}"
        val names = it.mapNotNull { entry -> (entry as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }
        if (names.isNotEmpty()) parts += "tool_names=${preview(names)}"
        val inputFields = it.firstNotNullOfOrNull { entry ->
            (entry as? JsonObject)?.schemaFields("input_schema", "inputSchema")
        }
        val outputFields = it.firstNotNullOfOrNull { entry ->
            (entry as? JsonObject)?.schemaFields("output_schema", "outputSchema")
        }
        inputFields?.let { fields -> parts += "input_schema_fields=$fields" }
        outputFields?.let { fields -> parts += "output_schema_fields=$fields" }
    }
    prompts?.let {
        parts += "prompts=${it.size}"
        val names = it.mapNotNull { entry -> (entry as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }
        if (names.isNotEmpty()) parts += "prompt_names=${preview(names)}"
    }
    resources?.let {
        parts += "resources=${it.size}"
        val names = it.mapNotNull { entry ->
            val objEntry = entry as? JsonObject
            objEntry?.get("uri")?.jsonPrimitive?.contentOrNull ?: objEntry?.get("name")?.jsonPrimitive?.contentOrNull
        }
        if (names.isNotEmpty()) parts += "resource_keys=${preview(names)}"
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(prefix = "result{", postfix = "}")
}

private fun describeCallResult(obj: JsonObject): String? {
    val content = obj.field("content") as? JsonArray
    val structured = obj.field("structured_content", "structuredContent") as? JsonObject
    val meta = obj.field("meta") as? JsonObject
    val isError = obj.field("is_error", "isError")?.jsonPrimitive?.booleanOrNull
    val parts = mutableListOf<String>()
    content?.let {
        val contentTypes =
            it.mapNotNull { entry -> entry.contentTypeLabel() }.distinct().takeIf { types -> types.isNotEmpty() }
        parts += "content=${it.size}"
        contentTypes?.let { types -> parts += "content_types=${preview(types)}" }
    }
    structured?.let {
        val keys = it.keys.takeIf { k -> k.isNotEmpty() }?.toList()
        keys?.let { k -> parts += "structured_keys=${preview(k)}" }
    }
    meta?.let {
        val keys = it.keys.takeIf { k -> k.isNotEmpty() }?.toList()
        keys?.let { k -> parts += "meta_keys=${preview(k)}" }
    }
    isError?.let { parts += "is_error=$it" }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(prefix = "result{", postfix = "}")
}

private fun JsonObject.field(vararg keys: String): JsonElement? =
    keys.firstNotNullOfOrNull { get(it) }

private fun JsonObject.schemaFields(vararg keys: String): String? {
    val schema = field(*keys) as? JsonObject ?: return null
    val properties = schema["properties"] as? JsonObject ?: return null
    val propNames = properties.keys.takeIf { it.isNotEmpty() } ?: return null
    return preview(propNames.toList())
}

private fun preview(values: List<String>, max: Int = 5): String =
    when {
        values.isEmpty() -> ""
        values.size <= max -> values.joinToString(",")
        else -> values.take(max).joinToString(",") + ",..."
    }

private fun JsonElement.contentTypeLabel(): String? = when (this) {
    is JsonObject -> this["type"]?.jsonPrimitive?.contentOrNull
        ?: when {
            "text" in this -> "text"
            "image" in this -> "image"
            "resource" in this -> "embedded_resource"
            "data" in this && this["mimeType"] != null -> this["mimeType"]?.jsonPrimitive?.contentOrNull
            else -> null
        }

    else -> null
}
