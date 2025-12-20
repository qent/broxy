package io.qent.broxy.ui.adapter.remote

import io.qent.broxy.core.proxy.runtime.ProxyLifecycle
import io.qent.broxy.core.utils.CollectingLogger
import kotlinx.coroutines.CoroutineScope

actual fun createRemoteConnector(
    logger: CollectingLogger,
    proxyLifecycle: ProxyLifecycle,
    scope: CoroutineScope,
): RemoteConnector =
    if (!isRemoteIntegrationEnabled()) {
        logger.info("[RemoteAuth] bro-cloud disabled by build flag; using no-op connector")
        NoOpRemoteConnector(defaultRemoteState())
    } else {
        runCatching {
            BroCloudRemoteConnectorAdapter(
                logger = logger,
                proxyLifecycle = proxyLifecycle,
                scope = scope,
            )
        }.getOrElse { error ->
            logger.warn("[RemoteAuth] bro-cloud integration unavailable; using no-op connector: ${error.message}", error)
            NoOpRemoteConnector(defaultRemoteState())
        }
    }
