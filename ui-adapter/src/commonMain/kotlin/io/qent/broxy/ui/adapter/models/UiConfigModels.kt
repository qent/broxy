package io.qent.broxy.ui.adapter.models

data class UiMcpServerConfig(
    val id: String,
    val name: String,
    val transport: UiTransportConfig,
    val env: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val auth: UiAuthConfig? = null,
)

data class UiMcpServersConfig(
    val servers: List<UiMcpServerConfig> = emptyList(),
    val defaultPresetId: String? = null,
    val inboundSsePort: Int = 3335,
    val requestTimeoutSeconds: Int = 60,
    val capabilitiesTimeoutSeconds: Int = 30,
    val authorizationTimeoutSeconds: Int = 120,
    val connectionRetryCount: Int = 3,
    val capabilitiesRefreshIntervalSeconds: Int = 300,
    val fallbackPromptsAndResourcesToTools: Boolean = false,
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
        val clientIdMetadataUrl: String? = null,
        val redirectUri: String? = null,
        val clientName: String? = null,
        val tokenEndpointAuthMethod: String? = null,
        val authorizationServer: String? = null,
        val scopes: List<String>? = null,
        val allowDynamicRegistration: Boolean = true,
    ) : UiAuthConfig
}

data class UiPresetCore(
    val id: String,
    val name: String,
    val tools: List<UiToolRef> = emptyList(),
    val prompts: List<UiPromptRef>? = null,
    val resources: List<UiResourceRef>? = null,
    val createdAtEpochMillis: Long? = null,
) {
    companion object {
        const val EMPTY_PRESET_ID: String = "__empty__"

        fun empty(): UiPresetCore =
            UiPresetCore(
                id = EMPTY_PRESET_ID,
                name = "No preset",
                tools = emptyList(),
                prompts = emptyList(),
                resources = emptyList(),
            )
    }
}
