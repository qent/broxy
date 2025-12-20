package io.qent.broxy.cloud.api

data class CloudRemoteState(
    val serverIdentifier: String,
    val email: String?,
    val hasCredentials: Boolean,
    val status: CloudRemoteStatus,
    val message: String? = null,
)

enum class CloudRemoteStatus {
    NotAuthorized,
    Authorizing,
    Registering,
    Registered,
    WsConnecting,
    WsOnline,
    WsOffline,
    Error,
}
