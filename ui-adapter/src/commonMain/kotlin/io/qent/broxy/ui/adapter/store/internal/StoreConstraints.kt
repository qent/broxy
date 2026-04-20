package io.qent.broxy.ui.adapter.store.internal

internal const val MIN_PORT = 1
internal const val MAX_PORT = 65535
internal const val MIN_REFRESH_INTERVAL_SECONDS = 30
internal const val MIN_CONNECTION_RETRY_COUNT = 1

internal fun clampPort(port: Int): Int = port.coerceIn(MIN_PORT, MAX_PORT)

internal fun clampRefreshIntervalSeconds(seconds: Int): Int = seconds.coerceAtLeast(MIN_REFRESH_INTERVAL_SECONDS)

internal fun clampConnectionRetryCount(count: Int): Int = count.coerceAtLeast(MIN_CONNECTION_RETRY_COUNT)
