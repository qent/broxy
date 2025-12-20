package io.qent.broxy.ui.adapter.remote

import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.qent.broxy.cloud.BroCloudRemoteConnectorFactory
import io.qent.broxy.cloud.api.CloudLogger
import io.qent.broxy.cloud.api.CloudProxyRuntime
import io.qent.broxy.cloud.api.CloudRemoteConnector
import io.qent.broxy.cloud.api.CloudRemoteState
import io.qent.broxy.cloud.api.CloudRemoteStatus
import io.qent.broxy.cloud.api.CloudServerSession
import io.qent.broxy.core.proxy.inbound.buildSdkServer
import io.qent.broxy.core.proxy.runtime.ProxyLifecycle
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.ui.adapter.models.UiRemoteConnectionState
import io.qent.broxy.ui.adapter.models.UiRemoteStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal class BroCloudRemoteConnectorAdapter(
    logger: CollectingLogger,
    proxyLifecycle: ProxyLifecycle,
    scope: CoroutineScope,
) : RemoteConnector {
    private val cloudConnector: CloudRemoteConnector =
        BroCloudRemoteConnectorFactory.create(
            logger = CollectingCloudLogger(logger),
            proxyRuntime = BroCloudProxyRuntime(proxyLifecycle, logger),
            scope = scope,
        )

    private val _state = MutableStateFlow(cloudConnector.state.value.toUiState())
    override val state: StateFlow<UiRemoteConnectionState> = _state

    init {
        scope.launch {
            cloudConnector.state.collect { cloudState ->
                _state.value = cloudState.toUiState()
            }
        }
    }

    override fun start() {
        cloudConnector.start()
    }

    override fun beginAuthorization() {
        cloudConnector.beginAuthorization()
    }

    override fun connect() {
        cloudConnector.connect()
    }

    override fun disconnect() {
        cloudConnector.disconnect()
    }

    override fun logout() {
        cloudConnector.logout()
    }

    override fun onProxyRunningChanged(running: Boolean) {
        cloudConnector.onProxyRunningChanged(running)
    }
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
    private val proxyLifecycle: ProxyLifecycle,
    private val logger: CollectingLogger,
) : CloudProxyRuntime {
    override fun isRunning(): Boolean = proxyLifecycle.isRunning()

    override suspend fun createSession(transport: AbstractTransport): CloudServerSession {
        val proxy = proxyLifecycle.currentProxy() ?: error("Proxy is not running; cannot attach remote client")
        val server = buildSdkServer(proxy, logger)
        val session = server.createSession(transport)
        return SdkServerSessionAdapter(session)
    }
}

private class SdkServerSessionAdapter(
    private val session: ServerSession,
) : CloudServerSession {
    override fun onClose(handler: () -> Unit) {
        session.onClose { handler() }
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
