package io.qent.broxy.headless

import io.qent.broxy.core.mcp.auth.resolveOAuthResourceUrl
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.TransportConfig
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.min

internal fun resolveAuthResourceUrl(config: McpServerConfig): String? =
    when (val transport = config.transport) {
        is TransportConfig.HttpTransport -> resolveOAuthResourceUrl(transport.url)
        is TransportConfig.StreamableHttpTransport -> resolveOAuthResourceUrl(transport.url)
        is TransportConfig.WebSocketTransport -> resolveOAuthResourceUrl(transport.url)
        else -> null
    }

internal fun resolveTimeouts(config: McpServersConfig): HeadlessTimeouts {
    val callTimeoutMillis = config.requestTimeoutSeconds.toLong() * MILLIS_PER_SECOND
    val capabilitiesTimeoutMillis = config.capabilitiesTimeoutSeconds.toLong() * MILLIS_PER_SECOND
    val authorizationTimeoutMillis = config.authorizationTimeoutSeconds.toLong() * MILLIS_PER_SECOND
    return HeadlessTimeouts(
        callTimeoutMillis = callTimeoutMillis,
        capabilitiesTimeoutMillis = capabilitiesTimeoutMillis,
        connectTimeoutMillis = capabilitiesTimeoutMillis,
        authorizationTimeoutMillis = authorizationTimeoutMillis,
    )
}

internal fun resolveRefreshIntervalMillis(seconds: Int): Long =
    seconds.coerceAtLeast(MIN_REFRESH_INTERVAL_SECONDS).toLong() * MILLIS_PER_SECOND

internal fun computeRefreshParallelism(serverCount: Int): Int {
    val cpu = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    val maxParallel = min(MAX_REFRESH_PARALLELISM, cpu)
    return serverCount.coerceAtLeast(1).coerceAtMost(maxParallel)
}

internal fun resolveConfigDir(configDir: String?): Path =
    if (configDir.isNullOrBlank()) {
        Paths.get(System.getProperty("user.home"), ".config", "broxy")
    } else {
        Paths.get(configDir)
    }

private const val MILLIS_PER_SECOND = 1_000L
private const val MIN_REFRESH_INTERVAL_SECONDS = 30
private const val MAX_REFRESH_PARALLELISM = 4
