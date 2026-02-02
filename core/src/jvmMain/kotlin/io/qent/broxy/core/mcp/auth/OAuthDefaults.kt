package io.qent.broxy.core.mcp.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout

internal const val DEFAULT_OAUTH_TIMEOUT_MILLIS = 30_000L
internal const val DEFAULT_AUTH_CODE_TIMEOUT_MILLIS = 120_000L
internal const val EXPIRY_SKEW_MILLIS = 30_000L
internal const val MILLIS_PER_SECOND = 1_000L
internal const val CODE_VERIFIER_BYTES = 32
internal const val STATE_BYTES = 16
internal const val PKCE_METHOD_S256 = "S256"

internal fun createDefaultHttpClient(): HttpClient =
    HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = DEFAULT_OAUTH_TIMEOUT_MILLIS
            socketTimeoutMillis = DEFAULT_OAUTH_TIMEOUT_MILLIS
            connectTimeoutMillis = DEFAULT_OAUTH_TIMEOUT_MILLIS
        }
    }
