package io.qent.bro.core.models

import kotlinx.serialization.Serializable

@Serializable
data class McpServersConfig(
    val servers: List<McpServerConfig> = emptyList(),
    val requestTimeoutSeconds: Int = 60,
    val showTrayIcon: Boolean = true
)
