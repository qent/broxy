package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.ui.adapter.models.UiMcpServerConfig

private const val HIDE_KEY_SEPARATOR = "::"

internal data class ImportedServerCandidate(
    val sourceServerId: String,
    val config: UiMcpServerConfig,
)

internal data class ImportedClientGroup(
    val clientId: String,
    val clientName: String,
    val clientIconId: String,
    val servers: List<ImportedServerCandidate>,
)

internal fun importedServerHideKey(
    clientId: String,
    sourceServerId: String,
): String = "${clientId.trim()}$HIDE_KEY_SEPARATOR${sourceServerId.trim()}"
