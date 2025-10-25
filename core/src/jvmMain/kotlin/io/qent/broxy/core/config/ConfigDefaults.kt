package io.qent.broxy.core.config

internal data class ConfigDefaults(
    val requestTimeoutSeconds: Int = 60,
    val capabilitiesTimeoutSeconds: Int = 30,
    val authorizationTimeoutSeconds: Int = 120,
    val connectionRetryCount: Int = 3,
    val capabilitiesRefreshIntervalSeconds: Int = 300,
    val inboundSsePort: Int = 3335,
)
