package io.qent.broxy.ui.adapter.models

import io.qent.broxy.core.models.Preset

data class UiMcpServerConfig(
    val id: String,
    val name: String,
    val transport: UiTransportConfig,
    val env: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val auth: UiAuthConfig? = null,
    val envFile: String? = null,
    val iconPath: String? = null,
)

data class UiMcpServersConfig(
    val servers: List<UiMcpServerConfig> = emptyList(),
    val mcpFilePath: String = "mcp.json",
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

sealed interface UiTransportConfig

data class UiStdioTransport(
    val command: String,
    val args: List<String> = emptyList(),
) : UiTransportConfig

data class UiHttpTransport(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
) : UiTransportConfig

data class UiStreamableHttpTransport(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
) : UiTransportConfig

data class UiWebSocketTransport(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
) : UiTransportConfig

sealed interface UiAuthConfig {
    data class OAuth(
        val clientId: String? = null,
        val clientSecret: String? = null,
        val callbackPort: Int? = null,
        val clientIdMetadataUrl: String? = null,
        val authServerMetadataUrl: String? = null,
        val redirectUri: String? = null,
        val clientName: String? = null,
        val tokenEndpointAuthMethod: String? = null,
        val authorizationServer: String? = null,
        val scopes: List<String>? = null,
        val allowDynamicRegistration: Boolean = true,
        val stdioBootstrap: UiStdioBootstrap? = null,
    ) : UiAuthConfig
}

data class UiStdioBootstrap(
    val tool: String,
    val args: Map<String, String> = emptyMap(),
)

data class UiPresetCore(
    val id: String,
    val name: String,
    val tools: List<UiToolRef> = emptyList(),
    val prompts: List<UiPromptRef>? = null,
    val resources: List<UiResourceRef>? = null,
    val orderIndex: Int = 0,
) {
    companion object {
        const val EMPTY_PRESET_ID: String = Preset.EMPTY_PRESET_ID
        const val ALL_ENABLED_PRESET_ID: String = Preset.ALL_ENABLED_PRESET_ID

        fun empty(): UiPresetCore =
            UiPresetCore(
                id = EMPTY_PRESET_ID,
                name = "No preset",
                tools = emptyList(),
                prompts = emptyList(),
                resources = emptyList(),
            )

        fun allEnabled(): UiPresetCore =
            UiPresetCore(
                id = ALL_ENABLED_PRESET_ID,
                name = "All enabled servers",
                tools = emptyList(),
                prompts = null,
                resources = null,
            )
    }
}
