package io.qent.broxy.ui.adapter.remote

import io.qent.broxy.cloud.api.CloudRemoteState
import io.qent.broxy.cloud.api.CloudRemoteStatus
import java.util.Locale
import java.util.UUID

internal fun defaultRemoteServerIdentifier(): String {
    val raw = "broxy-${UUID.randomUUID()}"
    return raw.lowercase(Locale.getDefault()).take(64)
}

internal fun defaultCloudState(): CloudRemoteState =
    CloudRemoteState(
        serverIdentifier = defaultRemoteServerIdentifier(),
        email = null,
        hasCredentials = false,
        status = CloudRemoteStatus.NotAuthorized,
        message = null,
    )
