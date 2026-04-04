package io.qent.broxy.ui.adapter.remote

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.qent.broxy.cloud.BroCloudRemoteConnectorFactory
import io.qent.broxy.cloud.api.CloudLogger
import io.qent.broxy.cloud.api.CloudProxyRuntime
import io.qent.broxy.cloud.api.CloudRemoteConnector
import io.qent.broxy.cloud.api.CloudRemoteState
import io.qent.broxy.cloud.api.CloudRemoteStatus
import io.qent.broxy.cloud.api.CloudServerSession
import io.qent.broxy.core.proxy.runtime.ProxyRuntimeFacade
import io.qent.broxy.core.proxy.runtime.ProxyRuntimeSdkFacade
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.ui.adapter.models.UiRemoteConnectionState
import io.qent.broxy.ui.adapter.models.UiRemoteStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class BroCloudRemoteConnectorAdapter(
    logger: CollectingLogger,
    proxyRuntime: ProxyRuntimeFacade,
    proxySdkFacade: ProxyRuntimeSdkFacade,
    private val scope: CoroutineScope,
) : RemoteConnector {
    private val proxyRuntimeFacade = proxyRuntime
    private val runtimeAdapter = BroCloudProxyRuntime(proxyRuntime, proxySdkFacade, logger)
    private val cloudConnector: CloudRemoteConnector =
        BroCloudRemoteConnectorFactory.create(
            logger = CollectingCloudLogger(logger),
            proxyRuntime = runtimeAdapter,
            scope = scope,
        )

    private val connectionMode = AtomicReference(RemoteConnectionMode.Allowed)
    private val proxyRunning = AtomicBoolean(runtimeAdapter.isRunning())
    private val _state = MutableStateFlow(cloudConnector.state.value.toUiState())
    override val state: StateFlow<UiRemoteConnectionState> = _state
    override val isEnabled: Boolean = true

    init {
        scope.launch {
            cloudConnector.state.collect { cloudState ->
                if (shouldDisconnect(cloudState.status)) {
                    enforceDisconnect("connection blocked")
                }
                _state.value = cloudState.toUiState()
            }
        }
        scope.launch {
            proxyRuntimeFacade.capabilityUpdates.collect {
                runtimeAdapter.syncActiveSessions()
            }
        }
    }

    override fun start() {
        cloudConnector.start()
    }

    override fun beginAuthorization() {
        connectionMode.set(RemoteConnectionMode.Allowed)
        cloudConnector.beginAuthorization()
    }

    override fun connect() {
        connectionMode.set(RemoteConnectionMode.Allowed)
        cloudConnector.connect()
    }

    override fun disconnect() {
        connectionMode.set(RemoteConnectionMode.Disconnected)
        cloudConnector.onProxyRunningChanged(false)
        enforceDisconnect("manual disconnect")
    }

    override fun logout() {
        connectionMode.set(RemoteConnectionMode.LoggedOut)
        cloudConnector.onProxyRunningChanged(false)
        cloudConnector.logout()
        enforceDisconnect("logout")
    }

    override fun onProxyRunningChanged(running: Boolean) {
        proxyRunning.set(running)
        if (running && connectionMode.get() == RemoteConnectionMode.Allowed) {
            cloudConnector.onProxyRunningChanged(true)
            return
        }
        cloudConnector.onProxyRunningChanged(false)
        enforceDisconnect(if (running) "connection blocked" else "proxy stopped")
    }

    override fun notifyPresetChanged(
        presetId: String?,
        changeType: String,
    ) {
        if (connectionMode.get() != RemoteConnectionMode.Allowed) return
        cloudConnector.notifyPresetChanged(presetId, changeType)
    }

    private fun shouldDisconnect(status: CloudRemoteStatus): Boolean {
        if (connectionMode.get() != RemoteConnectionMode.Allowed) {
            return status == CloudRemoteStatus.WsConnecting || status == CloudRemoteStatus.WsOnline
        }
        if (!proxyRunning.get()) {
            return status == CloudRemoteStatus.WsConnecting || status == CloudRemoteStatus.WsOnline
        }
        return false
    }

    private fun enforceDisconnect(reason: String) {
        cloudConnector.disconnect()
        scope.launch {
            runtimeAdapter.closeActiveSessions(reason)
        }
    }
}

private enum class RemoteConnectionMode {
    Allowed,
    Disconnected,
    LoggedOut,
}

private class CollectingCloudLogger(
    private val logger: CollectingLogger,
) : CloudLogger {
    override fun debug(message: String) {
        logger.debug(message)
    }

    override fun info(message: String) {
        logger.info(message)
    }

    override fun warn(
        message: String,
        throwable: Throwable?,
    ) {
        logger.warn(message, throwable)
    }

    override fun error(
        message: String,
        throwable: Throwable?,
    ) {
        logger.error(message, throwable)
    }
}

private class BroCloudProxyRuntime(
    private val proxyRuntime: ProxyRuntimeFacade,
    private val proxySdkFacade: ProxyRuntimeSdkFacade,
    private val logger: CollectingLogger,
) : CloudProxyRuntime {
    private val activeServers = CopyOnWriteArraySet<Server>()

    override fun isRunning(): Boolean = proxyRuntime.isRunning

    override suspend fun createSession(transport: AbstractTransport): CloudServerSession {
        val server = proxySdkFacade.createSdkServer(logger)
        activeServers += server
        val session = server.createSession(transport)
        return SdkServerSessionAdapter(session) {
            activeServers.remove(server)
        }
    }

    fun syncActiveSessions() {
        if (activeServers.isEmpty()) return
        activeServers.forEach { server ->
            runCatching { proxySdkFacade.syncSdkServer(server, logger) }
                .onFailure { logger.warn("[RemoteAuth] Failed to sync remote capabilities", it) }
        }
    }

    suspend fun closeActiveSessions(reason: String) {
        if (activeServers.isEmpty()) return
        val servers = activeServers.toList()
        activeServers.removeAll(servers)
        servers.forEach { server ->
            runCatching { server.close() }
                .onFailure { logger.warn("[RemoteAuth] Failed to close remote session ($reason)", it) }
        }
    }
}

private class SdkServerSessionAdapter(
    private val session: ServerSession,
    private val onClosed: () -> Unit,
) : CloudServerSession {
    override fun onClose(handler: () -> Unit) {
        session.onClose {
            onClosed()
            handler()
        }
    }
}

private fun CloudRemoteState.toUiState(): UiRemoteConnectionState =
    UiRemoteConnectionState(
        serverIdentifier = serverIdentifier,
        email = email,
        hasCredentials = hasCredentials,
        status =
            when (status) {
                CloudRemoteStatus.NotAuthorized -> UiRemoteStatus.NotAuthorized
                CloudRemoteStatus.Authorizing -> UiRemoteStatus.Authorizing
                CloudRemoteStatus.Registering -> UiRemoteStatus.Registering
                CloudRemoteStatus.Registered -> UiRemoteStatus.Registered
                CloudRemoteStatus.WsConnecting -> UiRemoteStatus.WsConnecting
                CloudRemoteStatus.WsOnline -> UiRemoteStatus.WsOnline
                CloudRemoteStatus.WsOffline -> UiRemoteStatus.WsOffline
                CloudRemoteStatus.Error -> UiRemoteStatus.Error
            },
        message = message,
    )
