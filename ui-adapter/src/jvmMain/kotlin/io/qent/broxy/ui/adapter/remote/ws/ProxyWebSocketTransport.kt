package io.qent.broxy.ui.adapter.remote.ws

import io.modelcontextprotocol.kotlin.sdk.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.McpJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

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

    override suspend fun send(message: JSONRPCMessage) {
        val sessionId = sessionIdentifier
            ?: error("session identifier is not available; cannot send response")
        val payload = McpProxyResponsePayload(
            sessionIdentifier = sessionId,
            targetServerIdentifier = serverIdentifier,
            message = McpJson.encodeToJsonElement(JSONRPCMessage.serializer(), message)
        )
        sender(payload)
    }

    override suspend fun close() {
        _onClose.invoke()
    }
}
