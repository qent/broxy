package io.qent.broxy.core.capabilities

data class ServerConnectionUpdate(
    val serverId: String,
    val status: ServerConnectionStatus,
    val errorMessage: String? = null,
)
