package io.qent.broxy.ui.adapter.models

data class UiPendingImportedServerCreate(
    val clientId: String,
    val sourceServerId: String,
    val draft: UiServerDraft,
)
