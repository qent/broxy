package io.qent.broxy.core.models

import kotlinx.serialization.Serializable

@Serializable
data class McpServersConfig(
    val servers: List<McpServerConfig> = emptyList(),
    val requestTimeoutSeconds: Int = 60,
    val capabilitiesTimeoutSeconds: Int = 30,
    val showTrayIcon: Boolean = true,
    val capabilitiesRefreshIntervalSeconds: Int = 300
)
