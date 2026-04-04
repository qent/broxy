package io.qent.broxy.core.config

import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.McpServersConfig

internal class DefaultsApplier(
    private val defaults: ConfigDefaults,
) {
    private companion object {
        private const val MIN_PORT = 1
        private const val MAX_PORT = 65535
        private const val MIN_RETRY_COUNT = 1
    }

    fun apply(
        appConfig: FileAppConfig,
        servers: List<McpServerConfig>,
        defaultMcpFilePath: String,
    ): McpServersConfig =
        McpServersConfig(
            servers = servers,
            mcpFilePath = appConfig.mcpFilePath?.takeIf { it.isNotBlank() } ?: defaultMcpFilePath,
            defaultPresetId = appConfig.defaultPresetId?.takeIf { it.isNotBlank() },
            inboundHttpPort =
                (appConfig.inboundHttpPort ?: defaults.inboundHttpPort)
                    .coerceIn(MIN_PORT, MAX_PORT),
            requestTimeoutSeconds = appConfig.requestTimeoutSeconds ?: defaults.requestTimeoutSeconds,
            capabilitiesTimeoutSeconds = appConfig.capabilitiesTimeoutSeconds ?: defaults.capabilitiesTimeoutSeconds,
            authorizationTimeoutSeconds = appConfig.authorizationTimeoutSeconds ?: defaults.authorizationTimeoutSeconds,
            connectionRetryCount =
                (appConfig.connectionRetryCount ?: defaults.connectionRetryCount).coerceAtLeast(MIN_RETRY_COUNT),
            ignoreHttpsCertificateErrors =
                appConfig.ignoreHttpsCertificateErrors ?: defaults.ignoreHttpsCertificateErrors,
            capabilitiesRefreshIntervalSeconds =
                appConfig.capabilitiesRefreshIntervalSeconds ?: defaults.capabilitiesRefreshIntervalSeconds,
            fallbackPromptsAndResourcesToTools = appConfig.fallbackPromptsAndResourcesToTools ?: false,
            adapterMode = appConfig.adapterMode ?: false,
        )

    fun normalizeForSave(config: McpServersConfig): McpServersConfig =
        config.copy(
            mcpFilePath = config.mcpFilePath.takeIf { it.isNotBlank() } ?: defaults.mcpFilePath,
            inboundHttpPort = config.inboundHttpPort.coerceIn(MIN_PORT, MAX_PORT),
            connectionRetryCount = config.connectionRetryCount.coerceAtLeast(MIN_RETRY_COUNT),
        )
}
