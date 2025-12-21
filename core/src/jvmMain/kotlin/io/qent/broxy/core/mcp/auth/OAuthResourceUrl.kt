package io.qent.broxy.core.mcp.auth

import java.net.URI

fun resolveOAuthResourceUrl(url: String): String {
    val uri = runCatching { URI(url) }.getOrNull() ?: return url
    val scheme =
        when (uri.scheme?.lowercase()) {
            "ws" -> "http"
            "wss" -> "https"
            else -> uri.scheme
        }
    if (scheme == null || scheme == uri.scheme) return url
    return URI(scheme, uri.userInfo, uri.host, uri.port, uri.path, uri.query, null).toString()
}
