package io.qent.broxy.ui.adapter.remote

import io.qent.broxy.core.proxy.runtime.ProxyLifecycle
import io.qent.broxy.core.utils.CollectingLogger
import kotlinx.coroutines.CoroutineScope

expect fun createRemoteConnector(
    logger: CollectingLogger,
    proxyLifecycle: ProxyLifecycle,
    scope: CoroutineScope
): RemoteConnector
