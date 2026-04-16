package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.ui.adapter.catalog.CatalogInstallPlanner
import io.qent.broxy.ui.adapter.models.UiPendingImportedServerCreate
import io.qent.broxy.ui.adapter.models.UiServerDraft
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class CatalogImportIntentsHandler(
    private val context: IntentExecutionContext,
    private val upsertServer: (UiServerDraft) -> Unit,
    private val upsertCatalogServer: (UiServerDraft) -> Unit,
    private val removeServer: (String) -> Unit,
) {
    fun importServerFromClient(
        clientId: String,
        sourceServerId: String,
    ) {
        context.scope.launch {
            val snapshot = context.state.snapshot
            val group = snapshot.importedServerGroups.firstOrNull { it.clientId == clientId } ?: return@launch
            val candidate = group.servers.firstOrNull { it.sourceServerId == sourceServerId } ?: return@launch
            context.state.updateSnapshot {
                copy(
                    pendingImportedServerCreate =
                        UiPendingImportedServerCreate(
                            clientId = clientId,
                            sourceServerId = sourceServerId,
                            draft = importedCandidateToDraft(candidate),
                        ),
                    pendingImportedServerCreateRequestId = pendingImportedServerCreateRequestId + 1,
                )
            }
            context.publishReady()
        }
    }

    fun saveImportedServerFromClient(
        clientId: String,
        sourceServerId: String,
        draft: UiServerDraft,
    ) {
        context.scope.launch {
            val importKey = importedServerHideKey(clientId = clientId, sourceServerId = sourceServerId)
            val resolvedServerId = draft.id.trim()
            withContext(context.ioDispatcher) {
                runCatching {
                    context.importedServerInstallRepository.saveInstalledMapping(importKey = importKey, serverId = resolvedServerId)
                }
            }.onFailure { error ->
                val msg =
                    logFailure(
                        context.logger,
                        "saveImportedServerMapping(clientId=$clientId,sourceServerId=$sourceServerId)",
                        error,
                        "Failed to save imported server mapping",
                    )
                context.pushToast(msg)
            }
            upsertServer(draft)
        }
    }

    fun hideImportedServer(
        clientId: String,
        sourceServerId: String,
    ) {
        context.scope.launch {
            val previousGroups = context.state.snapshot.importedServerGroups
            val updatedGroups = removeImportedServer(previousGroups, clientId, sourceServerId)
            if (updatedGroups == previousGroups) return@launch
            context.state.updateSnapshot { copy(importedServerGroups = updatedGroups) }
            context.publishReady()
            val hideKey = importedServerHideKey(clientId = clientId, sourceServerId = sourceServerId)
            val result =
                withContext(context.ioDispatcher) {
                    runCatching { context.importedServerHideRepository.hideServer(hideKey) }
                }
            if (result.isFailure) {
                val msg =
                    logFailure(
                        context.logger,
                        "hideImportedServer(clientId=$clientId,sourceServerId=$sourceServerId)",
                        result.exceptionOrNull(),
                        "Failed to hide imported server",
                    )
                context.state.updateSnapshot { copy(importedServerGroups = previousGroups) }
                context.state.setError(msg)
                context.publishReady()
            }
        }
    }

    fun resetHiddenImportedServers() {
        context.scope.launch {
            val clearResult =
                withContext(context.ioDispatcher) {
                    runCatching { context.importedServerHideRepository.clearHiddenServers() }
                }
            if (clearResult.isFailure) {
                val msg =
                    logFailure(
                        context.logger,
                        "resetHiddenImportedServers",
                        clearResult.exceptionOrNull(),
                        "Failed to reset hidden imported servers",
                    )
                context.state.setError(msg)
                context.publishReady()
                return@launch
            }
            context.refreshImportedServers()
        }
    }

    fun consumePendingImportedServerCreate() {
        context.scope.launch {
            if (context.state.snapshot.pendingImportedServerCreate == null) return@launch
            context.state.updateSnapshot { copy(pendingImportedServerCreate = null) }
            context.publishReady()
        }
    }

    fun refreshCatalog() {
        context.scope.launch {
            context.state.updateSnapshot { copy(catalogLoading = true, catalogErrorMessage = null) }
            context.publishReady()
            context.loadCatalogSnapshot()
            val refreshResult = context.refreshCatalogSnapshot(true)
            if (refreshResult.isFailure) {
                val message = logFailure(context.logger, "refreshCatalog", refreshResult.exceptionOrNull(), "Failed to refresh catalog")
                context.pushToast(message)
            }
        }
    }

    fun installCatalogServer(serverId: String) {
        context.scope.launch {
            val entry =
                context.state.snapshot.catalogServerEntries
                    .firstOrNull { it.detail.name == serverId } ?: return@launch
            val sessionResult = CatalogInstallPlanner.buildInstallSession(entry.detail)
            if (sessionResult.isFailure) {
                val message =
                    logFailure(
                        context.logger,
                        "installCatalogServer(id=$serverId)",
                        sessionResult.exceptionOrNull(),
                        "Failed to prepare catalog install session",
                    )
                context.pushToast(message)
                return@launch
            }
            val session = sessionResult.getOrThrow()
            val initialFieldValues = CatalogInstallPlanner.buildInitialFieldValues(session)
            if (session.installSteps.isEmpty() && !CatalogInstallPlanner.requiresInstallForm(session, initialFieldValues)) {
                val installResult =
                    CatalogInstallPlanner.buildInstallResult(
                        session = session,
                        displayName = "",
                        fieldValues = initialFieldValues,
                    )
                if (installResult.isFailure) {
                    val message =
                        logFailure(
                            context.logger,
                            "installCatalogServer(id=$serverId)/autoInstall",
                            installResult.exceptionOrNull(),
                            "Failed to install catalog server",
                        )
                    context.pushToast(message)
                    return@launch
                }
                upsertCatalogServer(installResult.getOrThrow().draft)
                return@launch
            }
            context.state.updateSnapshot {
                copy(
                    pendingCatalogInstallSession = session,
                    pendingCatalogInstallRequestId = pendingCatalogInstallRequestId + 1,
                )
            }
            context.publishReady()
        }
    }

    fun uninstallCatalogServer(serverId: String) {
        removeServer(serverId)
    }

    fun consumePendingCatalogInstall() {
        context.scope.launch {
            if (context.state.snapshot.pendingCatalogInstallSession == null) return@launch
            context.state.updateSnapshot { copy(pendingCatalogInstallSession = null) }
            context.publishReady()
        }
    }
}
