package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.ui.adapter.models.UiProxyStatus
import io.qent.broxy.ui.adapter.models.UiSettings
import io.qent.broxy.ui.adapter.models.toCore
import io.qent.broxy.ui.adapter.remote.RemotePresetChange
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class RuntimeSettingsIntentsHandler(
    private val context: IntentExecutionContext,
    private val configGateway: StoreConfigGateway,
) {
    private val proxyToggleLock = Mutex()

    fun updateInboundHttpPort(port: Int) {
        context.scope.launch {
            val clamped = clampPort(port)
            val previous = context.state.snapshot.inboundHttpPort
            val previousClients = context.state.snapshot.clients
            if (previous == clamped) return@launch
            val previousConfig = context.state.snapshotConfig()
            val updatedClients = context.buildAiClients(clamped)
            context.state.updateSnapshot { copy(inboundHttpPort = clamped, clients = updatedClients) }
            context.publishReady()
            val result =
                withContext(context.ioDispatcher) {
                    configGateway.updateInboundHttpPort(previousConfig, clamped)
                }
            if (result.isFailure) {
                val msg = logFailure(context.logger, "updateInboundHttpPort", result.exceptionOrNull(), "Failed to update HTTP port")
                context.state.updateSnapshot { copy(inboundHttpPort = previous, clients = previousClients) }
                context.state.setError(msg)
                context.publishReady()
                return@launch
            }
            context.proxyRuntime.ensureInboundRunning(forceRestart = true)
        }
    }

    fun updateRequestTimeout(seconds: Int) {
        context.scope.launch {
            val previous = context.state.snapshot.requestTimeoutSeconds
            val previousConfig = context.state.snapshotConfig()
            context.state.updateSnapshot { copy(requestTimeoutSeconds = seconds) }
            context.proxyRuntimeFacade.updateCallTimeout(context.state.snapshot.requestTimeoutSeconds)
            val result =
                withContext(context.ioDispatcher) {
                    configGateway.updateRequestTimeout(previousConfig, seconds)
                }
            if (result.isFailure) {
                val msg = logFailure(context.logger, "updateRequestTimeout", result.exceptionOrNull(), "Failed to update timeout")
                context.state.updateSnapshot { copy(requestTimeoutSeconds = previous) }
                context.proxyRuntimeFacade.updateCallTimeout(previous)
                context.state.setError(msg)
            }
            context.publishReady()
        }
    }

    fun updateCapabilitiesTimeout(seconds: Int) {
        context.scope.launch {
            val previous = context.state.snapshot.capabilitiesTimeoutSeconds
            val previousConfig = context.state.snapshotConfig()
            context.state.updateSnapshot { copy(capabilitiesTimeoutSeconds = seconds) }
            context.proxyRuntimeFacade.updateCapabilitiesTimeout(context.state.snapshot.capabilitiesTimeoutSeconds)
            val result =
                withContext(context.ioDispatcher) {
                    configGateway.updateCapabilitiesTimeout(previousConfig, seconds)
                }
            if (result.isFailure) {
                val msg =
                    logFailure(
                        context.logger,
                        "updateCapabilitiesTimeout",
                        result.exceptionOrNull(),
                        "Failed to update capabilities timeout",
                    )
                context.state.updateSnapshot { copy(capabilitiesTimeoutSeconds = previous) }
                context.proxyRuntimeFacade.updateCapabilitiesTimeout(previous)
                context.state.setError(msg)
            }
            context.publishReady()
        }
    }

    fun updateMcpFilePath(path: String) {
        context.scope.launch {
            val trimmed = path.trim()
            if (trimmed.isBlank() || context.state.snapshot.mcpFilePath == trimmed) return@launch
            val previousSnapshot = context.state.snapshot
            val previousConfig = context.state.snapshotConfig()
            context.state.updateSnapshot { copy(mcpFilePath = trimmed) }
            context.publishReady()
            val result =
                withContext(context.ioDispatcher) {
                    configGateway.updateMcpFilePath(previousConfig, trimmed)
                }
            if (result.isFailure) {
                val msg = logFailure(context.logger, "updateMcpFilePath", result.exceptionOrNull(), "Failed to update MCP file path")
                context.state.updateSnapshot { copy(mcpFilePath = previousSnapshot.mcpFilePath) }
                context.state.setError(msg)
                context.publishReady()
                return@launch
            }

            val reloadResult = context.loadConfiguration()
            if (reloadResult.isFailure) {
                val msg =
                    logFailure(context.logger, "updateMcpFilePath/reload", reloadResult.exceptionOrNull(), "Failed to reload configuration")
                context.state.updateSnapshot {
                    copy(
                        mcpFilePath = previousSnapshot.mcpFilePath,
                        servers = previousSnapshot.servers,
                        defaultPresetId = previousSnapshot.defaultPresetId,
                        inboundHttpPort = previousSnapshot.inboundHttpPort,
                        requestTimeoutSeconds = previousSnapshot.requestTimeoutSeconds,
                        capabilitiesTimeoutSeconds = previousSnapshot.capabilitiesTimeoutSeconds,
                        connectionRetryCount = previousSnapshot.connectionRetryCount,
                        ignoreHttpsCertificateErrors = previousSnapshot.ignoreHttpsCertificateErrors,
                        capabilitiesRefreshIntervalSeconds = previousSnapshot.capabilitiesRefreshIntervalSeconds,
                        fallbackPromptsAndResourcesToTools = previousSnapshot.fallbackPromptsAndResourcesToTools,
                        adapterMode = previousSnapshot.adapterMode,
                        clients = previousSnapshot.clients,
                    )
                }
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
            context.publishReady()
        }
    }

    fun updateConnectionRetryCount(count: Int) {
        context.scope.launch {
            val clamped = clampConnectionRetryCount(count)
            val previous = context.state.snapshot.connectionRetryCount
            val previousConfig = context.state.snapshotConfig()
            context.state.updateSnapshot { copy(connectionRetryCount = clamped) }
            context.proxyRuntimeFacade.updateConnectionRetryCount(clamped)
            val result =
                withContext(context.ioDispatcher) {
                    configGateway.updateConnectionRetryCount(previousConfig, clamped)
                }
            if (result.isFailure) {
                val msg =
                    logFailure(
                        context.logger,
                        "updateConnectionRetryCount",
                        result.exceptionOrNull(),
                        "Failed to update connection retries",
                    )
                context.state.updateSnapshot { copy(connectionRetryCount = previous) }
                context.proxyRuntimeFacade.updateConnectionRetryCount(previous)
                context.state.setError(msg)
            }
            context.publishReady()
        }
    }

    fun updateIgnoreHttpsCertificateErrors(enabled: Boolean) {
        context.scope.launch {
            if (context.state.snapshot.ignoreHttpsCertificateErrors == enabled) return@launch
            val previous = context.state.snapshot.ignoreHttpsCertificateErrors
            val previousConfig = context.state.snapshotConfig()
            context.state.updateSnapshot { copy(ignoreHttpsCertificateErrors = enabled) }
            context.proxyRuntimeFacade.updateIgnoreHttpsCertificateErrors(enabled)
            val result =
                withContext(context.ioDispatcher) {
                    configGateway.updateIgnoreHttpsCertificateErrors(previousConfig, enabled)
                }
            if (result.isFailure) {
                val msg =
                    logFailure(
                        context.logger,
                        "updateIgnoreHttpsCertificateErrors",
                        result.exceptionOrNull(),
                        "Failed to update HTTPS certificate handling",
                    )
                context.state.updateSnapshot { copy(ignoreHttpsCertificateErrors = previous) }
                context.proxyRuntimeFacade.updateIgnoreHttpsCertificateErrors(previous)
                context.state.setError(msg)
            }
            context.publishReady()
        }
    }

    fun updateCapabilitiesRefreshInterval(seconds: Int) {
        context.scope.launch {
            val clamped = clampRefreshIntervalSeconds(seconds)
            if (context.state.snapshot.capabilitiesRefreshIntervalSeconds == clamped) return@launch
            val previous = context.state.snapshot.capabilitiesRefreshIntervalSeconds
            val previousConfig = context.state.snapshotConfig()
            context.state.updateSnapshot { copy(capabilitiesRefreshIntervalSeconds = clamped) }
            val result =
                withContext(context.ioDispatcher) {
                    configGateway.updateRefreshInterval(previousConfig, clamped)
                }
            if (result.isFailure) {
                val msg =
                    logFailure(
                        context.logger,
                        "updateCapabilitiesRefreshInterval",
                        result.exceptionOrNull(),
                        "Failed to update refresh interval",
                    )
                context.state.updateSnapshot { copy(capabilitiesRefreshIntervalSeconds = previous) }
                context.state.setError(msg)
            } else {
                if (!shouldApplyProxyUpdates(context.state.snapshot.proxyStatus, context.proxyRuntimeFacade.isRunning)) {
                    context.refreshEnabledCaps(true)
                }
                context.syncBackgroundRefresh()
            }
            context.publishReady()
        }
    }

    fun updateTrayIconVisibility(visible: Boolean) {
        context.scope.launch {
            if (context.state.snapshot.showTrayIcon == visible) return@launch
            val previous = context.state.snapshot.showTrayIcon
            context.state.updateSnapshot { copy(showTrayIcon = visible) }
            val result =
                withContext(context.ioDispatcher) {
                    runCatching {
                        val existing =
                            runCatching { context.uiSettingsRepository.loadUiSettings() }
                                .onFailure { context.logger.warn("Failed to load ui.json before save: ${it.message}", it) }
                                .getOrElse { UiSettings(showTrayIcon = previous) }
                        context.uiSettingsRepository.saveUiSettings(existing.copy(showTrayIcon = visible))
                    }
                }
            if (result.isFailure) {
                val msg =
                    logFailure(context.logger, "updateTrayIconVisibility", result.exceptionOrNull(), "Failed to update tray preference")
                context.state.updateSnapshot { copy(showTrayIcon = previous) }
                context.state.setError(msg)
            }
            context.publishReady()
        }
    }

    fun updateFallbackPromptsAndResourcesToTools(enabled: Boolean) {
        context.scope.launch {
            if (context.state.snapshot.fallbackPromptsAndResourcesToTools == enabled) return@launch
            val previous = context.state.snapshot.fallbackPromptsAndResourcesToTools
            val previousConfig = context.state.snapshotConfig()
            context.state.updateSnapshot { copy(fallbackPromptsAndResourcesToTools = enabled) }
            context.proxyRuntimeFacade.updateFallbackPromptsAndResourcesToTools(enabled)
            val result =
                withContext(context.ioDispatcher) {
                    configGateway.updateFallbackPromptsAndResourcesToTools(previousConfig, enabled)
                }
            if (result.isFailure) {
                val msg =
                    logFailure(
                        context.logger,
                        "updateFallbackPromptsAndResourcesToTools",
                        result.exceptionOrNull(),
                        "Failed to update prompt/resource tool fallback",
                    )
                context.state.updateSnapshot { copy(fallbackPromptsAndResourcesToTools = previous) }
                context.proxyRuntimeFacade.updateFallbackPromptsAndResourcesToTools(previous)
                context.state.setError(msg)
            }
            context.publishReady()
        }
    }

    fun updateAdapterMode(enabled: Boolean) {
        context.scope.launch {
            if (context.state.snapshot.adapterMode == enabled) return@launch
            val previous = context.state.snapshot.adapterMode
            val previousConfig = context.state.snapshotConfig()
            context.state.updateSnapshot { copy(adapterMode = enabled) }
            context.publishReady()
            val result =
                withContext(context.ioDispatcher) {
                    context.proxyRuntimeFacade.updateAdapterMode(enabled)
                    configGateway.updateAdapterMode(previousConfig, enabled)
                }
            if (result.isFailure) {
                val msg =
                    logFailure(
                        context.logger,
                        "updateAdapterMode",
                        result.exceptionOrNull(),
                        "Failed to update adapter mode",
                    )
                context.state.updateSnapshot { copy(adapterMode = previous) }
                withContext(context.ioDispatcher) {
                    context.proxyRuntimeFacade.updateAdapterMode(previous)
                }
                context.state.setError(msg)
            } else if (context.state.snapshot.proxyStatus is UiProxyStatus.Running) {
                context.remoteConnector.notifyPresetChanged(context.state.snapshot.activeProxyPresetId, RemotePresetChange.COMPOSITION)
            }
            context.publishReady()
        }
    }

    fun toggleProxyServer() {
        context.scope.launch {
            val status = context.state.snapshot.proxyStatus
            if (status is UiProxyStatus.Starting || status is UiProxyStatus.Stopping) return@launch
            proxyToggleLock.withLock {
                when (context.state.snapshot.proxyStatus) {
                    UiProxyStatus.Running ->
                        withContext(context.ioDispatcher) {
                            context.proxyRuntime.stopInbound()
                        }

                    UiProxyStatus.Stopped,
                    is UiProxyStatus.Error,
                    -> context.proxyRuntime.ensureInboundRunning(forceRestart = true)

                    UiProxyStatus.Starting,
                    UiProxyStatus.Stopping,
                    -> Unit
                }
            }
        }
    }
}
