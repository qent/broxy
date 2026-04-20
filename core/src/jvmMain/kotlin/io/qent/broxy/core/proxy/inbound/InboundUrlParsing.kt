package io.qent.broxy.core.proxy.inbound

import java.net.URI

internal fun parse(url: String): Triple<String, Int, String> {
    val u = URI(url)
    val host = if (u.host.isNullOrBlank()) "0.0.0.0" else u.host
    val port =
        if (u.port == -1) {
            if (u.scheme == "https" || u.scheme == "wss") HTTPS_DEFAULT_PORT else HTTP_DEFAULT_PORT
        } else {
            u.port
        }
    val path = if (u.path.isNullOrBlank()) "/mcp" else u.path
    return Triple(host, port, if (path.endsWith('/')) path.dropLast(1) else path)
}

internal fun normalizePath(rawPath: String): NormalizedPath {
    val trimmed = rawPath.trim().ifBlank { "/" }
    val withoutPrefix = trimmed.removePrefix("/")
    val routeSegments = withoutPrefix.trim()
    val display = if (routeSegments.isBlank()) "/" else "/$routeSegments"
    return NormalizedPath(display = display, routeSegments = routeSegments)
}

internal data class NormalizedPath(
    val display: String,
    val routeSegments: String,
)

private const val HTTP_DEFAULT_PORT = 80
private const val HTTPS_DEFAULT_PORT = 443
