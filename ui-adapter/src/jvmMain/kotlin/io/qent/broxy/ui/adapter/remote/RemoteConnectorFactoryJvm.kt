package io.qent.broxy.ui.adapter.remote

import io.qent.broxy.core.proxy.runtime.ProxyLifecycle
import io.qent.broxy.core.utils.CollectingLogger
import kotlinx.coroutines.CoroutineScope

actual fun createRemoteConnector(
    logger: CollectingLogger,
    proxyLifecycle: ProxyLifecycle,
    scope: CoroutineScope
): RemoteConnector = RemoteConnectorImpl(
    logger = logger,
    proxyLifecycle = proxyLifecycle,
    scope = scope
)
