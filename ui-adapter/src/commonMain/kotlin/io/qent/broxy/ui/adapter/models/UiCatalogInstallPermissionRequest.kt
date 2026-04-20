package io.qent.broxy.ui.adapter.models

data class UiCatalogInstallPermissionRequest(
    val serverId: String,
    val serverName: String,
    val serverDescription: String,
    val iconUrl: String?,
)
