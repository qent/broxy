package io.qent.broxy.core.config

import io.qent.broxy.core.models.AuthConfig
import kotlinx.serialization.Serializable

@Serializable
internal data class FileMcpRoot(
    val defaultPresetId: String? = null,
    val inboundHttpPort: Int? = null,
    val requestTimeoutSeconds: Int? = null,
    val capabilitiesTimeoutSeconds: Int? = null,
    val authorizationTimeoutSeconds: Int? = null,
    val connectionRetryCount: Int? = null,
    val capabilitiesRefreshIntervalSeconds: Int? = null,
    val fallbackPromptsAndResourcesToTools: Boolean? = null,
    val adapterMode: Boolean? = null,
    val mcpServers: Map<String, FileMcpServer>,
)

@Serializable
internal data class FileMcpServer(
    val name: String? = null,
    val enabled: Boolean? = null,
    val transport: String,
    val command: String? = null,
    val args: List<String>? = null,
    val url: String? = null,
    val headers: Map<String, String>? = null,
    val env: Map<String, String>? = null,
    val auth: AuthConfig? = null,
    val iconPath: String? = null,
)
