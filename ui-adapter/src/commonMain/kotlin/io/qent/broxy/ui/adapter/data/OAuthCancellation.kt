package io.qent.broxy.ui.adapter.data

expect fun signalOAuthCancellation(redirectUri: String): Result<Unit>
