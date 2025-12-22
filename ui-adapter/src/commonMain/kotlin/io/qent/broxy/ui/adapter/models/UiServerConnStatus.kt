package io.qent.broxy.ui.adapter.models

import io.qent.broxy.core.capabilities.ServerConnectionStatus

enum class UiServerConnStatus {
    Disabled,
    Authorization,
    Connecting,
    Available,
    Error,
}

internal fun ServerConnectionStatus.toUiStatus(): UiServerConnStatus =
    when (this) {
        ServerConnectionStatus.Disabled -> UiServerConnStatus.Disabled
        ServerConnectionStatus.Authorization -> UiServerConnStatus.Authorization
        ServerConnectionStatus.Connecting -> UiServerConnStatus.Connecting
        ServerConnectionStatus.Available -> UiServerConnStatus.Available
        ServerConnectionStatus.Error -> UiServerConnStatus.Error
    }
