package io.qent.broxy.ui.adapter.remote

import java.util.Locale
import java.util.UUID

actual fun defaultRemoteServerIdentifier(): String {
    val raw = "broxy-${UUID.randomUUID()}"
    return raw.lowercase(Locale.getDefault()).take(64)
}
