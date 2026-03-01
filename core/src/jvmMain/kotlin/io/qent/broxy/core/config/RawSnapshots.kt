package io.qent.broxy.core.config

import io.qent.broxy.core.models.AuthConfig

internal data class RawConfigSnapshot(
    val servers: Map<String, RawServerSnapshot>,
) {
    companion object {
        val Empty = RawConfigSnapshot(emptyMap())
    }
}

internal data class RawServerSnapshot(
    val rawEnv: Map<String, String>,
    val rawAuth: AuthConfig?,
    val resolvedEnv: Map<String, String>,
    val resolvedAuth: AuthConfig?,
)
