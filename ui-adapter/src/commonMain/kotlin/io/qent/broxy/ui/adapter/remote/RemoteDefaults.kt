package io.qent.broxy.ui.adapter.remote

import io.qent.broxy.ui.adapter.models.UiRemoteConnectionState
import io.qent.broxy.ui.adapter.models.UiRemoteStatus

expect fun defaultRemoteServerIdentifier(): String

fun defaultRemoteState(): UiRemoteConnectionState = UiRemoteConnectionState(
    serverIdentifier = defaultRemoteServerIdentifier(),
    email = null,
    status = UiRemoteStatus.NotAuthorized,
    message = null
)
