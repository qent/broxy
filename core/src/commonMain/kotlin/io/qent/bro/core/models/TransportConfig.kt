package io.qent.bro.core.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class TransportConfig {
    @Serializable
    @SerialName("stdio")
    data class StdioTransport(
        val command: String,
        val args: List<String> = emptyList()
    ) : TransportConfig()

    @Serializable
    @SerialName("http")
    data class HttpTransport(
        val url: String,
        val headers: Map<String, String> = emptyMap()
    ) : TransportConfig()

    @Serializable
    @SerialName("streamable-http")
    data class StreamableHttpTransport(
        val url: String,
        val headers: Map<String, String> = emptyMap()
    ) : TransportConfig()

    @Serializable
    @SerialName("websocket")
    data class WebSocketTransport(
        val url: String
    ) : TransportConfig()
}
