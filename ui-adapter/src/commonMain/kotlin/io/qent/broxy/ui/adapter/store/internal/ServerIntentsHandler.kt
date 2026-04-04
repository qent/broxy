package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.ui.adapter.capabilities.ServerStateUpdate
import io.qent.broxy.ui.adapter.models.UiMcpServerConfig
import io.qent.broxy.ui.adapter.models.UiServer
import io.qent.broxy.ui.adapter.models.UiServerDraft
import io.qent.broxy.ui.adapter.models.UiStdioTransport
import io.qent.broxy.ui.adapter.models.toCore
import io.qent.broxy.ui.adapter.store.toTransportConfig
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import io.qent.broxy.ui.adapter.data.openExternalUrl as openExternalUrlPlatform
import io.qent.broxy.ui.adapter.data.signalOAuthCancellation as signalOAuthCancellationPlatform

internal class ServerIntentsHandler(
    private val context: IntentExecutionContext,
    private val configGateway: StoreConfigGateway,
) {
    private val toggleLock = Mutex()

    fun refresh() {
        context.scope.launch {
            val refreshResult = context.loadConfiguration()
            if (refreshResult.isFailure) {
                val msg = logFailure(context.logger, "refresh", refreshResult.exceptionOrNull(), "Failed to refresh")
                context.state.setError(msg)
                context.publishReady()
                return@launch
            }
            context.capabilityRefresher.syncWithServers(
                context.state.snapshot.servers
                    .toCore(),
            )
            context.publishReady()
            context.proxyRuntime.ensureInboundRunning(forceRestart = true)
            if (!shouldApplyProxyUpdates(context.state.snapshot.proxyStatus, context.proxyRuntimeFacade.isRunning)) {
                context.refreshEnabledCaps(true)
            }
            context.syncBackgroundRefresh()
            context.refreshImportedServers()
            context.refreshCatalogSnapshot(false)
        }
    }

    fun addOrUpdateServerUi(ui: UiServer) {
        context.scope.launch {
            val previousServers = context.state.snapshot.servers
            val previousConfig = context.state.snapshotConfig()
            val updated = previousServers.toMutableList()
            val idx = updated.indexOfFirst { it.id == ui.id }
            val base =
                updated.getOrNull(idx)
                    ?: UiMcpServerConfig(
                        id = ui.id,
                        name = ui.name,
                        transport = UiStdioTransport(command = ""),
                        enabled = ui.enabled,
                    )
            val merged = base.copy(name = ui.name, enabled = ui.enabled)
            if (idx >= 0) updated[idx] = merged else updated += merged
            context.state.updateSnapshot { copy(servers = updated) }
            val shouldShowConnecting =
                idx < 0 &&
                    merged.enabled &&
                    !context.capabilityRefresher.hasCachedSnapshot(merged.id)
            if (shouldShowConnecting) {
                context.capabilityRefresher.updateServerState(merged.id, ServerStateUpdate.Connecting)
            } else {
                context.publishReady()
            }
            val result =
                withContext(context.ioDispatcher) {
                    configGateway.upsertServer(previousConfig, merged)
                }
            if (result.isFailure) {
                revertServersOnFailure(
                    context = context,
                    operation = "addOrUpdateServerUi",
                    previousServers = previousServers,
                    failure = result.exceptionOrNull(),
                    defaultMessage = "Failed to save servers",
                )
            } else {
                context.capabilityRefresher.updateCachedName(ui.id, ui.name)
                val saved = result.getOrNull()
                context.capabilityRefresher.syncWithServers(saved?.servers?.toCore() ?: updated.toCore())
                triggerServerRefresh(context, setOf(ui.id), force = true)
                applyServerConfigToProxy(context, saved, "addOrUpdateServerUi")
            }
            context.publishReady()
        }
    }

    fun addServerBasic(
        id: String,
        name: String,
    ) {
        context.scope.launch {
            var previousServers: List<UiMcpServerConfig>? = null
            var newServer: UiMcpServerConfig? = null
            context.state.updateSnapshot {
                previousServers = servers
                if (servers.any { it.id == id }) return@updateSnapshot this
                val server = UiMcpServerConfig(id = id, name = name, transport = UiStdioTransport(command = ""), enabled = true)
                newServer = server
                val updated = servers.toMutableList().apply { add(server) }
                copy(servers = updated)
            }
            val addedServer = newServer ?: return@launch
            if (addedServer.enabled && !context.capabilityRefresher.hasCachedSnapshot(addedServer.id)) {
                context.capabilityRefresher.updateServerState(addedServer.id, ServerStateUpdate.Connecting)
            } else {
                context.publishReady()
            }
            val currentServers = context.state.snapshot.servers
            val result =
                withContext(context.ioDispatcher) {
                    configGateway.upsertServer(context.state.snapshotConfig(), addedServer)
                }
            if (result.isFailure) {
                revertServersOnFailure(
                    context = context,
                    operation = "addServerBasic",
                    previousServers = previousServers ?: context.state.snapshot.servers,
                    failure = result.exceptionOrNull(),
                    defaultMessage = "Failed to save servers",
                )
            } else {
                val saved = result.getOrNull()
                context.capabilityRefresher.syncWithServers(saved?.servers?.toCore() ?: currentServers.toCore())
                applyServerConfigToProxy(context, saved, "addServerBasic")
            }
            context.publishReady()
        }
    }

    fun upsertServer(draft: UiServerDraft) {
        upsertServerInternal(
            draft = draft,
            placeNewServerFirst = false,
            operationName = "upsertServer",
            signalCatalogInstalledServerFocus = false,
        )
    }

    fun upsertCatalogServer(draft: UiServerDraft) {
        upsertServerInternal(
            draft = draft,
            placeNewServerFirst = true,
            operationName = "upsertCatalogServer",
            signalCatalogInstalledServerFocus = true,
        )
    }

    fun consumePendingCatalogInstalledServer() {
        context.scope.launch {
            if (context.state.snapshot.pendingCatalogInstalledServerId == null) return@launch
            context.state.updateSnapshot { copy(pendingCatalogInstalledServerId = null) }
            context.publishReady()
        }
    }

    fun removeServer(id: String) {
        context.scope.launch {
            val previousServers = context.state.snapshot.servers
            val previousPopup = context.state.snapshot.authorizationPopup
            val previousConfig = context.state.snapshotConfig()
            val removedIconPath = previousServers.firstOrNull { it.id == id }?.iconPath
            val updated = previousServers.filterNot { it.id == id }
            val clearedPopup = if (previousPopup?.serverId == id) null else previousPopup
            context.state.updateSnapshot { copy(servers = updated, authorizationPopup = clearedPopup) }
            val result =
                withContext(context.ioDispatcher) {
                    configGateway.removeServer(previousConfig, id)
                }
            if (result.isFailure) {
                revertServersOnFailure(
                    context = context,
                    operation = "removeServer",
                    previousServers = previousServers,
                    failure = result.exceptionOrNull(),
                    defaultMessage = "Failed to save servers",
                )
            } else {
                val saved = result.getOrNull()
                context.capabilityRefresher.syncWithServers(saved?.servers?.toCore() ?: updated.toCore())
                context.capabilityRefresher.updateServerState(id, ServerStateUpdate.Removed)
                applyServerConfigToProxy(context, saved, "removeServer")
                context.refreshImportedServers()
                removeIconIfUnused(context, removedIconPath, updated)
            }
            context.publishReady()
        }
    }

    fun toggleServer(
        id: String,
        enabled: Boolean,
    ) {
        context.scope.launch {
            val previousServers = context.state.snapshot.servers
            val previousPopup = context.state.snapshot.authorizationPopup
            val idx = previousServers.indexOfFirst { it.id == id }
            if (idx < 0) return@launch
            val updated = previousServers.toMutableList()
            updated[idx] = updated[idx].copy(enabled = enabled)
            val clearedPopup =
                if (!enabled && previousPopup?.serverId == id) {
                    null
                } else {
                    previousPopup
                }
            context.state.updateSnapshot {
                copy(
                    servers = updated,
                    authorizationPopup = clearedPopup,
                )
            }
            val hasCachedSnapshot = context.capabilityRefresher.hasCachedSnapshot(id)
            if (!enabled) {
                if (previousPopup?.serverId == id) {
                    context.scope.launch {
                        val cancelResult =
                            withContext(context.ioDispatcher) {
                                signalOAuthCancellationPlatform(previousPopup.redirectUri)
                            }
                        cancelResult.onFailure {
                            logFailure(context.logger, "toggleServer(id=$id)/cancelAuthorization", it, "Failed to cancel authorization")
                        }
                    }
                }
                context.capabilityRefresher.updateServerState(id, ServerStateUpdate.Disabled)
            } else if (!hasCachedSnapshot) {
                context.capabilityRefresher.updateServerState(id, ServerStateUpdate.Connecting)
            }
            context.publishReady()

            toggleLock.withLock {
                val currentEnabled =
                    context.state.snapshot.servers
                        .firstOrNull { it.id == id }
                        ?.enabled
                        ?: return@withLock
                if (currentEnabled != enabled) return@withLock

                val result =
                    withContext(context.ioDispatcher) {
                        configGateway.toggleServer(context.state.snapshotConfig(), id, enabled)
                    }
                if (result.isFailure) {
                    val message =
                        logFailure(context.logger, "toggleServer(id=$id)", result.exceptionOrNull(), "Failed to save server state")
                    val shouldRevert = context.state.snapshot.servers == updated
                    if (shouldRevert) {
                        context.state.updateSnapshot { copy(servers = previousServers) }
                        if (enabled) {
                            context.capabilityRefresher.updateServerState(id, ServerStateUpdate.Disabled)
                        } else if (!hasCachedSnapshot) {
                            context.capabilityRefresher.updateServerState(id, ServerStateUpdate.Connecting)
                        }
                    }
                    context.state.setError(message)
                    context.publishReady()
                    return@withLock
                }
                val savedConfig = result.getOrNull()
                val refreshedEnabled =
                    context.state.snapshot.servers
                        .firstOrNull { it.id == id }
                        ?.enabled
                if (refreshedEnabled != enabled) return@withLock
                if (enabled) {
                    triggerServerRefresh(context, setOf(id), force = false)
                }
                if (savedConfig != null && context.proxyRuntimeFacade.isRunning) {
                    val updateResult = context.proxyRuntimeFacade.updateServers(savedConfig.toCore())
                    if (updateResult.isFailure) {
                        logFailure(
                            context.logger,
                            "toggleServer(id=$id)/updateServers",
                            updateResult.exceptionOrNull(),
                            "Failed to update proxy servers",
                        )
                    }
                }
                context.publishReady()
            }
        }
    }

    fun reorderServers(serverIds: List<String>) {
        context.scope.launch {
            val previousSnapshot = context.state.snapshot
            val previousConfig = context.state.snapshotConfig()
            val previousServers = previousSnapshot.servers
            val reordered =
                reorderByIds(previousServers, serverIds) { it.id }
                    ?: run {
                        logFailure(
                            context.logger,
                            "reorderServers",
                            IllegalArgumentException("Invalid server reorder request"),
                            "Failed to reorder servers",
                        )
                        return@launch
                    }
            if (reordered == previousServers) return@launch
            context.state.updateSnapshot { copy(servers = reordered) }
            context.publishReady()
            val result =
                withContext(context.ioDispatcher) {
                    configGateway.reorderServers(previousConfig, serverIds)
                }
            if (result.isFailure) {
                revertServersOnFailure(
                    context = context,
                    operation = "reorderServers",
                    previousServers = previousServers,
                    failure = result.exceptionOrNull(),
                    defaultMessage = "Failed to save server order",
                )
                context.publishReady()
                return@launch
            }
            val savedConfig = result.getOrNull()
            context.capabilityRefresher.syncWithServers(savedConfig?.servers?.toCore() ?: reordered.toCore())
            applyServerConfigToProxy(context, savedConfig, "reorderServers")
            context.publishReady()
        }
    }

    fun cancelAuthorization(serverId: String) {
        val popup = context.state.snapshot.authorizationPopup
        if (popup?.serverId == serverId) {
            context.scope.launch {
                val cancelResult =
                    withContext(context.ioDispatcher) {
                        signalOAuthCancellationPlatform(popup.redirectUri)
                    }
                cancelResult.onFailure {
                    logFailure(context.logger, "cancelAuthorization(id=$serverId)", it, "Failed to cancel authorization")
                }
            }
        }
        context.capabilityRefresher.updateServerState(serverId, ServerStateUpdate.Disabled)
        dismissAuthorizationPopup(serverId)
        toggleServer(serverId, enabled = false)
    }

    fun openAuthorizationInBrowser(
        serverId: String,
        urlOverride: String?,
    ) {
        context.scope.launch {
            val popup = context.state.snapshot.authorizationPopup
            if (popup?.serverId != serverId) return@launch
            val targetUrl = urlOverride?.trim()?.takeIf { it.isNotBlank() } ?: popup.authorizationUrl
            val result =
                withContext(context.ioDispatcher) {
                    openExternalUrlPlatform(targetUrl)
                }
            if (result.isFailure) {
                logFailure(
                    context.logger,
                    "openAuthorizationInBrowser(id=$serverId)",
                    result.exceptionOrNull(),
                    "Failed to open authorization",
                )
            }
        }
    }

    fun dismissAuthorizationPopup(serverId: String) {
        context.scope.launch {
            val popup = context.state.snapshot.authorizationPopup
            if (popup?.serverId != serverId) return@launch
            context.state.updateSnapshot { copy(authorizationPopup = null) }
            context.publishReady()
        }
    }

    fun refreshServerCapabilities(serverId: String) {
        context.scope.launch {
            context.state.updateSnapshot { copy(refreshingServerIds = refreshingServerIds + serverId) }
            context.publishReady()
            try {
                if (context.proxyRuntimeFacade.isRunning) {
                    val refreshResult =
                        withContext(context.ioDispatcher) {
                            context.proxyRuntimeFacade.refreshServerCapabilities(serverId)
                        }
                    if (refreshResult.isFailure) {
                        logFailure(
                            context.logger,
                            "refreshServerCapabilities(id=$serverId)",
                            refreshResult.exceptionOrNull(),
                            "Failed to refresh server capabilities",
                        )
                    }
                    return@launch
                }
                withContext(context.ioDispatcher) {
                    context.capabilityRefresher.refreshServersById(setOf(serverId), force = true)
                }
            } finally {
                context.state.updateSnapshot { copy(refreshingServerIds = refreshingServerIds - serverId) }
                context.publishReady()
            }
        }
    }

    fun pickServerIcon(serverId: String) {
        context.scope.launch {
            val previousServers = context.state.snapshot.servers
            val target = previousServers.firstOrNull { it.id == serverId } ?: return@launch
            val pickResult =
                withContext(context.ioDispatcher) {
                    context.serverIconRepository.pickAndImportIcon()
                }
            if (pickResult.isFailure) {
                val msg =
                    logFailure(
                        context.logger,
                        "pickServerIcon(id=$serverId)",
                        pickResult.exceptionOrNull(),
                        "Failed to pick server icon",
                    )
                context.state.setError(msg)
                context.publishReady()
                return@launch
            }
            val pickedPath = pickResult.getOrNull()?.trim().orEmpty()
            if (pickedPath.isBlank()) {
                return@launch
            }
            val updatedServer = target.copy(iconPath = pickedPath)
            val updatedServers = previousServers.map { if (it.id == serverId) updatedServer else it }
            context.state.updateSnapshot { copy(servers = updatedServers) }
            context.publishReady()
            val saveResult =
                withContext(context.ioDispatcher) {
                    configGateway.upsertServer(context.state.snapshotConfig(), updatedServer)
                }
            if (saveResult.isFailure) {
                revertServersOnFailure(
                    context = context,
                    operation = "pickServerIcon",
                    previousServers = previousServers,
                    failure = saveResult.exceptionOrNull(),
                    defaultMessage = "Failed to save server icon",
                )
                cleanupIconOnFailure(context, pickedPath, previousServers)
                context.publishReady()
                return@launch
            }
            val savedConfig = saveResult.getOrNull()
            applyServerConfigToProxy(context, savedConfig, "pickServerIcon")
            removeIconIfUnused(context, target.iconPath, updatedServers)
            context.publishReady()
        }
    }

    fun clearServerIcon(serverId: String) {
        context.scope.launch {
            val previousServers = context.state.snapshot.servers
            val target = previousServers.firstOrNull { it.id == serverId } ?: return@launch
            val previousIconPath = target.iconPath?.trim()?.takeIf { it.isNotBlank() } ?: return@launch
            val updatedServer = target.copy(iconPath = null)
            val updatedServers = previousServers.map { if (it.id == serverId) updatedServer else it }
            context.state.updateSnapshot { copy(servers = updatedServers) }
            context.publishReady()
            val saveResult =
                withContext(context.ioDispatcher) {
                    configGateway.upsertServer(context.state.snapshotConfig(), updatedServer)
                }
            if (saveResult.isFailure) {
                revertServersOnFailure(
                    context = context,
                    operation = "clearServerIcon",
                    previousServers = previousServers,
                    failure = saveResult.exceptionOrNull(),
                    defaultMessage = "Failed to clear server icon",
                )
                context.publishReady()
                return@launch
            }
            val savedConfig = saveResult.getOrNull()
            applyServerConfigToProxy(context, savedConfig, "clearServerIcon")
            removeIconIfUnused(context, previousIconPath, updatedServers)
            context.publishReady()
        }
    }

    private fun upsertServerInternal(
        draft: UiServerDraft,
        placeNewServerFirst: Boolean,
        operationName: String,
        signalCatalogInstalledServerFocus: Boolean,
    ) {
        context.scope.launch {
            val originalId = draft.originalId?.trim()?.takeIf { it.isNotBlank() }
            val trimmedId = draft.id.trim()
            val trimmedIconPath = draft.iconPath?.trim()?.takeIf { it.isNotBlank() }
            val normalizedDraft =
                if (trimmedId == draft.id && originalId == draft.originalId && trimmedIconPath == draft.iconPath) {
                    draft
                } else {
                    draft.copy(id = trimmedId, originalId = originalId, iconPath = trimmedIconPath)
                }

            val previousServers = context.state.snapshot.servers
            val previousConfig = context.state.snapshotConfig()
            val updated = previousServers.toMutableList()
            val oldId = originalId?.takeIf { it != normalizedDraft.id }
            val isRename = oldId != null
            val renameId = if (isRename) requireNotNull(oldId) else null
            val previousIconPath =
                previousServers.firstOrNull { it.id == (renameId ?: normalizedDraft.id) }?.iconPath
            val cfg =
                UiMcpServerConfig(
                    id = normalizedDraft.id,
                    name = normalizedDraft.name,
                    enabled = normalizedDraft.enabled,
                    transport = normalizedDraft.transport.toTransportConfig(),
                    env = normalizedDraft.env,
                    iconPath = normalizedDraft.iconPath,
                )
            val isNewServer = previousServers.none { it.id == cfg.id }
            val placeAtBeginning = placeNewServerFirst && isNewServer && renameId == null
            if (renameId != null) {
                val oldIndex = updated.indexOfFirst { it.id == renameId }
                val existingIndex = updated.indexOfFirst { it.id == cfg.id }
                if (existingIndex >= 0) {
                    updated[existingIndex] = cfg
                } else {
                    if (oldIndex >= 0) {
                        updated.removeAt(oldIndex)
                    }
                    val insertIndex = if (oldIndex >= 0) oldIndex else updated.size
                    updated.add(insertIndex.coerceAtMost(updated.size), cfg)
                }
                updated.removeAll { it.id == renameId }
            } else {
                val idx = updated.indexOfFirst { it.id == cfg.id }
                when {
                    idx >= 0 -> updated[idx] = cfg
                    placeAtBeginning -> updated.add(0, cfg)
                    else -> updated += cfg
                }
            }

            context.state.updateSnapshot { copy(servers = updated) }
            val shouldShowConnecting =
                isNewServer &&
                    cfg.enabled &&
                    !context.capabilityRefresher.hasCachedSnapshot(cfg.id)
            if (shouldShowConnecting) {
                context.capabilityRefresher.updateServerState(cfg.id, ServerStateUpdate.Connecting)
            } else {
                context.publishReady()
            }

            val renameResult =
                if (renameId != null) {
                    withContext(context.ioDispatcher) {
                        configGateway.renameServer(previousConfig, oldId = renameId, server = cfg)
                    }
                } else {
                    null
                }
            val saveResult =
                renameResult?.map { it.config }
                    ?: withContext(context.ioDispatcher) {
                        configGateway.upsertServer(
                            config = previousConfig,
                            server = cfg,
                            insertAtBeginning = placeAtBeginning,
                        )
                    }

            if (saveResult.isFailure) {
                revertServersOnFailure(
                    context = context,
                    operation = operationName,
                    previousServers = previousServers,
                    failure = saveResult.exceptionOrNull(),
                    defaultMessage = "Failed to save server",
                )
                cleanupIconOnFailure(context, normalizedDraft.iconPath, previousServers)
            } else {
                val savedConfig = saveResult.getOrNull()
                context.capabilityRefresher.syncWithServers(savedConfig?.servers?.toCore() ?: updated.toCore())
                val shouldSignalCatalogInstalledServerFocus = signalCatalogInstalledServerFocus && placeAtBeginning
                if (shouldSignalCatalogInstalledServerFocus) {
                    context.state.updateSnapshot {
                        copy(
                            pendingCatalogInstalledServerId = cfg.id,
                            pendingCatalogInstalledServerRequestId = pendingCatalogInstalledServerRequestId + 1,
                        )
                    }
                }
                if (renameId != null) {
                    context.capabilityRefresher.updateServerState(renameId, ServerStateUpdate.Removed)
                    val migrationError = renameResult?.getOrNull()?.presetMigrationError
                    if (migrationError != null) {
                        val msg =
                            logFailure(
                                context.logger,
                                "$operationName(renamePresets,id=${cfg.id})",
                                migrationError,
                                "Failed to update presets after server rename",
                            )
                        context.state.setError(msg)
                    }
                }
                triggerServerRefresh(context, setOf(cfg.id), force = true)
                applyServerConfigToProxy(context, savedConfig, operationName)
                context.refreshImportedServers()
                removeIconIfUnused(context, previousIconPath, updated)
            }
            context.publishReady()
        }
    }
}
