package io.qent.broxy.core.models

import kotlinx.serialization.Serializable

@Serializable
data class AppConfig(
    val mcpFilePath: String = "~/.config/broxy/mcp.json",
    val defaultPresetId: String? = null,
    val inboundHttpPort: Int = 3335,
    val requestTimeoutSeconds: Int = 60,
    val capabilitiesTimeoutSeconds: Int = 30,
    val authorizationTimeoutSeconds: Int = 120,
    val connectionRetryCount: Int = 3,
    val ignoreHttpsCertificateErrors: Boolean = false,
    val capabilitiesRefreshIntervalSeconds: Int = 300,
    val fallbackPromptsAndResourcesToTools: Boolean = false,
    val adapterMode: Boolean = false,
)
