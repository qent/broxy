package io.qent.broxy.ui.adapter.models

data class UiServer(
    val id: String,
    val name: String,
    val transportLabel: String,
    val enabled: Boolean,
    val status: UiServerConnStatus,
    val connectingSinceEpochMillis: Long? = null,
    val toolsCount: Int? = null,
    val promptsCount: Int? = null,
    val resourcesCount: Int? = null,
)
