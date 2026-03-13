package io.qent.broxy.core.mcp.auth

import java.net.URI

fun resolveOAuthResourceUrl(url: String): String {
    val uri = runCatching { URI(url) }.getOrNull()
    if (uri == null) {
        return url
    }
    val scheme =
        when (uri.scheme?.lowercase()) {
            "ws" -> "http"
            "wss" -> "https"
            else -> uri.scheme
        }
    val resolved =
        if (scheme == null || scheme == uri.scheme) {
            url
        } else {
            URI(scheme, uri.userInfo, uri.host, uri.port, uri.path, uri.query, null).toString()
        }
    return resolved
}
