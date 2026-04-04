package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.ui.adapter.models.UiHttpDraft
import io.qent.broxy.ui.adapter.models.UiHttpTransport
import io.qent.broxy.ui.adapter.models.UiMcpServerConfig
import io.qent.broxy.ui.adapter.models.UiMcpServersConfig
import io.qent.broxy.ui.adapter.models.UiServerDraft
import io.qent.broxy.ui.adapter.models.UiStdioDraft
import io.qent.broxy.ui.adapter.models.UiStdioTransport
import io.qent.broxy.ui.adapter.models.UiStreamableHttpDraft
import io.qent.broxy.ui.adapter.models.UiStreamableHttpTransport
import io.qent.broxy.ui.adapter.models.UiTransportConfig
import io.qent.broxy.ui.adapter.models.UiWebSocketDraft
import io.qent.broxy.ui.adapter.models.UiWebSocketTransport
import io.qent.broxy.ui.adapter.models.toCore
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun <T> reorderByIds(
    items: List<T>,
    orderedIds: List<String>,
    idSelector: (T) -> String,
): List<T>? {
    if (items.size != orderedIds.size) return null
    if (orderedIds.toSet().size != orderedIds.size) return null
    val byId = items.associateBy(idSelector)
    if (!orderedIds.all { it in byId }) return null
    return orderedIds.map { byId.getValue(it) }
}

internal fun isSupportedExternalUrl(url: String): Boolean {
    val normalized = url.trim().lowercase()
    if (normalized.isEmpty()) return false
    return normalized.startsWith("http://") || normalized.startsWith("https://")
}

internal fun revertServersOnFailure(
    context: IntentExecutionContext,
    operation: String,
    previousServers: List<UiMcpServerConfig>,
    failure: Throwable?,
    defaultMessage: String,
) {
    val message = logFailure(context.logger, operation, failure, defaultMessage)
    context.state.updateSnapshot {
        copy(servers = previousServers)
    }
    context.state.setError(message)
}

internal fun revertPresetsOnFailure(
    context: IntentExecutionContext,
    operation: String,
    previousSnapshot: StoreSnapshot,
    failure: Throwable?,
    defaultMessage: String,
) {
    val message = logFailure(context.logger, operation, failure, defaultMessage)
    context.state.updateSnapshot { previousSnapshot }
    context.state.setError(message)
}

internal fun triggerServerRefresh(
    context: IntentExecutionContext,
    ids: Set<String>,
    force: Boolean,
) {
    if (ids.isEmpty()) return
    if (shouldApplyProxyUpdates(context.state.snapshot.proxyStatus, context.proxyRuntimeFacade.isRunning)) return
    context.scope.launch { context.capabilityRefresher.refreshServersById(ids, force) }
}

internal suspend fun applyServerConfigToProxy(
    context: IntentExecutionContext,
    config: UiMcpServersConfig?,
    operation: String,
) {
    if (config == null) return
    if (context.proxyRuntimeFacade.isRunning) {
        val updateResult = context.proxyRuntimeFacade.updateServers(config.toCore())
        if (updateResult.isFailure) {
            logFailure(
                context.logger,
                "$operation/updateServers",
                updateResult.exceptionOrNull(),
                "Failed to update proxy servers",
            )
        }
        return
    }
    context.proxyRuntime.ensureInboundRunning()
}

internal suspend fun removeIconIfUnused(
    context: IntentExecutionContext,
    iconPath: String?,
    servers: List<UiMcpServerConfig>,
) {
    val trimmed = iconPath?.trim()?.takeIf { it.isNotEmpty() } ?: return
    if (servers.any { it.iconPath == trimmed }) return
    withContext(context.ioDispatcher) {
        context.serverIconRepository.deleteIcon(trimmed)
    }.onFailure {
        logFailure(context.logger, "removeIconIfUnused(path=$trimmed)", it, "Failed to delete unused server icon")
    }
}

internal suspend fun cleanupIconOnFailure(
    context: IntentExecutionContext,
    iconPath: String?,
    previousServers: List<UiMcpServerConfig>,
) {
    val trimmed = iconPath?.trim()?.takeIf { it.isNotEmpty() } ?: return
    if (previousServers.any { it.iconPath == trimmed }) return
    withContext(context.ioDispatcher) {
        context.serverIconRepository.deleteIcon(trimmed)
    }.onFailure {
        logFailure(context.logger, "cleanupIconOnFailure(path=$trimmed)", it, "Failed to delete unused server icon")
    }
}

internal fun removeImportedServer(
    groups: List<ImportedClientGroup>,
    clientId: String,
    sourceServerId: String,
): List<ImportedClientGroup> =
    groups
        .mapNotNull { group ->
            if (group.clientId != clientId) return@mapNotNull group
            val remaining = group.servers.filterNot { it.sourceServerId == sourceServerId }
            if (remaining.isEmpty()) {
                null
            } else {
                group.copy(servers = remaining)
            }
        }

internal fun importedCandidateToDraft(candidate: ImportedServerCandidate) =
    UiServerDraft(
        id = candidate.config.id,
        name = candidate.config.name,
        enabled = candidate.config.enabled,
        transport = candidate.config.transport.toDraft(),
        env = candidate.config.env,
        originalId = null,
        iconPath = candidate.config.iconPath,
    )

internal fun UiTransportConfig.toDraft() =
    when (this) {
        is UiStdioTransport -> UiStdioDraft(command = command, args = args)

        is UiHttpTransport -> UiHttpDraft(url = url, headers = headers)

        is UiStreamableHttpTransport -> UiStreamableHttpDraft(url = url, headers = headers)

        is UiWebSocketTransport -> UiWebSocketDraft(url = url, headers = headers)
    }
