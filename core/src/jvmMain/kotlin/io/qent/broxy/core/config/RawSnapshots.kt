package io.qent.broxy.core.config

import io.qent.broxy.core.models.AuthConfig
import io.qent.broxy.core.models.TransportConfig

internal data class RawConfigSnapshot(
    val servers: Map<String, RawServerSnapshot>,
) {
    companion object {
        val Empty = RawConfigSnapshot(emptyMap())
    }
}

internal data class RawServerSnapshot(
    val rawEnv: Map<String, String>,
    val envFilePath: String?,
    val rawAuth: AuthConfig?,
    val resolvedEnv: Map<String, String>,
    val resolvedAuth: AuthConfig?,
    val rawCommand: String?,
    val rawArgs: List<String>?,
    val rawUrl: String?,
    val rawHeaders: Map<String, String>?,
    val resolvedTransport: TransportConfig,
)
