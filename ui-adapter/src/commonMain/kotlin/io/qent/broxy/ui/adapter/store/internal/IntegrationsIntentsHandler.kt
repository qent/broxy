package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.ui.adapter.clients.AiClientConnectionRequest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.qent.broxy.ui.adapter.data.openExternalUrl as openExternalUrlPlatform
import io.qent.broxy.ui.adapter.data.openLogsFolder as openLogsFolderPlatform

private const val REMOTE_PORTAL_URL = "https://broxy.run/login"

internal class IntegrationsIntentsHandler(
    private val context: IntentExecutionContext,
) {
    fun openLogsFolder() {
        context.scope.launch {
            val result =
                withContext(context.ioDispatcher) {
                    openLogsFolderPlatform()
                }
            if (result.isFailure) {
                logFailure(context.logger, "openLogsFolder", result.exceptionOrNull(), "Failed to open logs folder")
            } else {
                logInfo(context.logger, "openLogsFolder", "opened")
            }
        }
    }

    fun openExternalUrl(url: String) {
        context.scope.launch {
            val targetUrl = url.trim()
            if (!isSupportedExternalUrl(targetUrl)) {
                logFailure(
                    context.logger,
                    "openExternalUrl",
                    IllegalArgumentException("Unsupported URL: '$targetUrl'"),
                    "Failed to open external URL",
                )
                return@launch
            }
            val result =
                withContext(context.ioDispatcher) {
                    openExternalUrlPlatform(targetUrl)
                }
            if (result.isFailure) {
                logFailure(context.logger, "openExternalUrl", result.exceptionOrNull(), "Failed to open external URL")
            }
        }
    }

    fun openRemotePortal() {
        context.scope.launch {
            val result =
                withContext(context.ioDispatcher) {
                    openExternalUrlPlatform(REMOTE_PORTAL_URL)
                }
            if (result.isFailure) {
                logFailure(context.logger, "openRemotePortal", result.exceptionOrNull(), "Failed to open remote portal")
            }
        }
    }

    fun startRemoteAuthorization() {
        context.remoteConnector.beginAuthorization()
    }

    fun connectRemote() {
        context.remoteConnector.connect()
    }

    fun disconnectRemote() {
        context.remoteConnector.disconnect()
    }

    fun logoutRemote() {
        context.remoteConnector.logout()
    }

    fun connectAiClient(clientId: String) {
        context.scope.launch {
            val connector = context.aiClientConnectors.firstOrNull { it.descriptor.id == clientId } ?: return@launch
            val request = AiClientConnectionRequest(httpEndpoint = httpEndpointFor(context.state.snapshot.inboundHttpPort))
            val result =
                withContext(context.ioDispatcher) {
                    connector.connect(request)
                }
            if (result.isFailure) {
                val msg = logFailure(context.logger, "connectAiClient(id=$clientId)", result.exceptionOrNull(), "Failed to connect client")
                context.state.setError(msg)
            }
            refreshAiClients()
            context.refreshImportedServers()
            context.publishReady()
        }
    }

    fun disconnectAiClient(clientId: String) {
        context.scope.launch {
            val connector = context.aiClientConnectors.firstOrNull { it.descriptor.id == clientId } ?: return@launch
            val request = AiClientConnectionRequest(httpEndpoint = httpEndpointFor(context.state.snapshot.inboundHttpPort))
            val result =
                withContext(context.ioDispatcher) {
                    connector.disconnect(request)
                }
            if (result.isFailure) {
                val msg =
                    logFailure(
                        context.logger,
                        "disconnectAiClient(id=$clientId)",
                        result.exceptionOrNull(),
                        "Failed to disconnect client",
                    )
                context.state.setError(msg)
            }
            refreshAiClients()
            context.refreshImportedServers()
            context.publishReady()
        }
    }

    fun openAiClientInfo(clientId: String) {
        context.scope.launch {
            val connector = context.aiClientConnectors.firstOrNull { it.descriptor.id == clientId } ?: return@launch
            val result =
                withContext(context.ioDispatcher) {
                    openExternalUrlPlatform(connector.descriptor.infoUrl)
                }
            if (result.isFailure) {
                logFailure(
                    context.logger,
                    "openAiClientInfo(id=$clientId)",
                    result.exceptionOrNull(),
                    "Failed to open client info",
                )
            }
        }
    }

    private suspend fun refreshAiClients() {
        if (context.aiClientConnectors.isEmpty()) return
        val clients = context.buildAiClients(context.state.snapshot.inboundHttpPort)
        context.state.updateSnapshot { copy(clients = clients) }
    }
}
