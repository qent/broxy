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
        root: FileMcpRoot,
        servers: List<McpServerConfig>,
    ): McpServersConfig =
        McpServersConfig(
            servers = servers,
            defaultPresetId = root.defaultPresetId?.takeIf { it.isNotBlank() },
            inboundSsePort = (root.inboundSsePort ?: defaults.inboundSsePort).coerceIn(MIN_PORT, MAX_PORT),
            requestTimeoutSeconds = root.requestTimeoutSeconds ?: defaults.requestTimeoutSeconds,
            capabilitiesTimeoutSeconds = root.capabilitiesTimeoutSeconds ?: defaults.capabilitiesTimeoutSeconds,
            authorizationTimeoutSeconds = root.authorizationTimeoutSeconds ?: defaults.authorizationTimeoutSeconds,
            connectionRetryCount =
                (root.connectionRetryCount ?: defaults.connectionRetryCount).coerceAtLeast(MIN_RETRY_COUNT),
            capabilitiesRefreshIntervalSeconds =
                root.capabilitiesRefreshIntervalSeconds ?: defaults.capabilitiesRefreshIntervalSeconds,
            fallbackPromptsAndResourcesToTools = root.fallbackPromptsAndResourcesToTools ?: false,
        )

    fun normalizeForSave(config: McpServersConfig): McpServersConfig =
        config.copy(
            inboundSsePort = config.inboundSsePort.coerceIn(MIN_PORT, MAX_PORT),
            connectionRetryCount = config.connectionRetryCount.coerceAtLeast(MIN_RETRY_COUNT),
        )
}
