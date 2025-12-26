package io.qent.broxy.ui.adapter.models

data class UiServer(
    val id: String,
    val name: String,
    val transportLabel: String,
    val enabled: Boolean,
    val canToggle: Boolean = true,
    val status: UiServerConnStatus,
    val icon: UiServerIcon,
    val errorMessage: String? = null,
    val connectingSinceEpochMillis: Long? = null,
    val toolsCount: Int? = null,
    val promptsCount: Int? = null,
    val resourcesCount: Int? = null,
)
