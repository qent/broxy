package io.qent.broxy.ui.adapter.models

/**
 * UI-facing state of the remote MCP proxy connection (OAuth + WebSocket).
 */
data class UiRemoteConnectionState(
    val serverIdentifier: String,
    val email: String?,
    val status: UiRemoteStatus,
    val message: String? = null
)

enum class UiRemoteStatus {
    NotAuthorized,
    Authorizing,
    Registering,
    Registered,
    WsConnecting,
    WsOnline,
    WsOffline,
    Error
}
