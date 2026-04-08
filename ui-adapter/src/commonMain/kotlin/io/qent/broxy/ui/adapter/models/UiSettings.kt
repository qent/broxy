package io.qent.broxy.ui.adapter.models

import kotlinx.serialization.Serializable

@Serializable
data class UiSettings(
    val showTrayIcon: Boolean = true,
    val agentRunNotificationsEnabled: Boolean = true,
)
