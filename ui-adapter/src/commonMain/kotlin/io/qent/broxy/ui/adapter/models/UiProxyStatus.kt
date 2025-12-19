package io.qent.broxy.ui.adapter.models

sealed class UiProxyStatus {
    data object Starting : UiProxyStatus()

    data object Running : UiProxyStatus()

    data object Stopping : UiProxyStatus()

    data object Stopped : UiProxyStatus()

    data class Error(val message: String) : UiProxyStatus()
}
