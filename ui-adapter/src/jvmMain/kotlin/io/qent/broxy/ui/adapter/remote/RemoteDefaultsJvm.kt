package io.qent.broxy.ui.adapter.remote

import java.net.InetAddress
import java.util.Locale

actual fun defaultRemoteServerIdentifier(): String {
    val host = runCatching { InetAddress.getLocalHost().hostName }.getOrNull().orEmpty()
    val os = System.getProperty("os.name") ?: ""
    val raw = "broxy-$host-$os"
    val normalized =
        raw.lowercase(Locale.getDefault())
            .map { ch ->
                when {
                    ch.isLetterOrDigit() -> ch
                    ch == '-' || ch == '_' || ch == '.' -> '-'
                    else -> '-'
                }
            }
            .joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')
    return normalized.ifBlank { "broxy-node" }.take(48)
}
