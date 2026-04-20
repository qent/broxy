package io.qent.broxy.core.config

import io.qent.broxy.core.models.AuthConfig
import kotlinx.serialization.Serializable

@Serializable
internal data class FileAppConfig(
    val mcpFilePath: String? = null,
    val defaultPresetId: String? = null,
    val inboundHttpPort: Int? = null,
    val requestTimeoutSeconds: Int? = null,
    val capabilitiesTimeoutSeconds: Int? = null,
    val authorizationTimeoutSeconds: Int? = null,
    val connectionRetryCount: Int? = null,
    val ignoreHttpsCertificateErrors: Boolean? = null,
    val capabilitiesRefreshIntervalSeconds: Int? = null,
    val fallbackPromptsAndResourcesToTools: Boolean? = null,
    val adapterMode: Boolean? = null,
)

@Serializable
internal data class FileMcpRoot(
    val mcpServers: Map<String, FileMcpServer> = emptyMap(),
)

@Serializable
internal data class FileMcpServer(
    val name: String? = null,
    val enabled: Boolean? = null,
    val type: String? = null,
    val command: String? = null,
    val args: List<String>? = null,
    val url: String? = null,
    val headers: Map<String, String>? = null,
    val env: Map<String, String>? = null,
    val envFile: String? = null,
    val oauth: AuthConfig? = null,
    val auth: AuthConfig? = null,
    val iconPath: String? = null,
)
