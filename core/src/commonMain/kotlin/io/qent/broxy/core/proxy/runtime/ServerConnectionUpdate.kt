package io.qent.broxy.core.proxy.runtime

enum class ServerConnectionStatus {
    Disabled,
    Authorization,
    Connecting,
    Available,
    Error,
}

data class ServerConnectionUpdate(
    val serverId: String,
    val status: ServerConnectionStatus,
    val errorMessage: String? = null,
)
