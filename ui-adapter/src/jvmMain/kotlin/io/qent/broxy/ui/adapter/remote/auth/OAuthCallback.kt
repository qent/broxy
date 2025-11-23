package io.qent.broxy.ui.adapter.remote.auth

data class OAuthCallback(
    val code: String,
    val state: String
)
