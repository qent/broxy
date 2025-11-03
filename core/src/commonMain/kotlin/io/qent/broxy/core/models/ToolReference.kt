package io.qent.broxy.core.models

import kotlinx.serialization.Serializable

@Serializable
data class ToolReference(
    val serverId: String,
    val toolName: String,
    val enabled: Boolean = true
)
