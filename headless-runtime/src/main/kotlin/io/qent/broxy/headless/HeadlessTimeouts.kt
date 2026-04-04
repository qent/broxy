package io.qent.broxy.headless

internal data class HeadlessTimeouts(
    val callTimeoutMillis: Long,
    val capabilitiesTimeoutMillis: Long,
    val connectTimeoutMillis: Long,
    val authorizationTimeoutMillis: Long,
)
