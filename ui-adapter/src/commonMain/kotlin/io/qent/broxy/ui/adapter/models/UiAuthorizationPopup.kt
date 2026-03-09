package io.qent.broxy.ui.adapter.models

data class UiAuthorizationPopup(
    val serverId: String,
    val serverName: String,
    val resourceUrl: String,
    val authorizationUrl: String,
    val redirectUri: String,
    val status: UiAuthorizationPopupStatus = UiAuthorizationPopupStatus.Pending,
)

enum class UiAuthorizationPopupStatus {
    Pending,
    Success,
}
