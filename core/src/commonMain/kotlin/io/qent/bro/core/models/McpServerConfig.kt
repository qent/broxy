package io.qent.bro.core.models

import kotlinx.serialization.Serializable

@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val transport: TransportConfig,
    val env: Map<String, String> = emptyMap(),
    val enabled: Boolean = true
)
