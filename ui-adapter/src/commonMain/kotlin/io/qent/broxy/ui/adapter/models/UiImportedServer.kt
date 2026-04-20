package io.qent.broxy.ui.adapter.models

data class UiImportedServerGroup(
    val clientId: String,
    val clientName: String,
    val clientIconId: String,
    val servers: List<UiImportedServer>,
)

data class UiImportedServer(
    val sourceServerId: String,
    val name: String,
    val transportLabel: String,
    val icon: UiServerIcon,
)
