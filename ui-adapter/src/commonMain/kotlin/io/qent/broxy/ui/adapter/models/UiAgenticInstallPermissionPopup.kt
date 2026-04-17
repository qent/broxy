package io.qent.broxy.ui.adapter.models

data class UiAgenticInstallPermissionPopup(
    val requestId: Long,
    val serverId: String,
    val serverName: String,
    val serverDescription: String,
    val iconUrl: String? = null,
)
