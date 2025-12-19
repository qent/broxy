package io.qent.broxy.core.mcp.errors

sealed class McpError(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
    class ConnectionError(message: String, cause: Throwable? = null) : McpError(message, cause)

    class TransportError(message: String, cause: Throwable? = null) : McpError(message, cause)

    class ProtocolError(message: String, cause: Throwable? = null) : McpError(message, cause)

    class TimeoutError(message: String, cause: Throwable? = null) : McpError(message, cause)
}
