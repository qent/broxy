package io.qent.broxy.ui.adapter.remote.ws

import io.modelcontextprotocol.kotlin.sdk.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.McpJson
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.qent.broxy.core.utils.CollectingLogger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

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
            "[RemoteWsClient] Outbound message session=$sessionId target=$serverIdentifier ${describeJsonRpcPayload(messageElement)}"
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
        hasResult.takeIf { it }?.let { "has_result=true" },
        errorMessage?.let { "error=$it" }
    ).joinToString(" ")
}
