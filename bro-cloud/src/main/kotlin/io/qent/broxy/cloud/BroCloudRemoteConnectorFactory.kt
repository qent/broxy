package io.qent.broxy.cloud

import io.qent.broxy.cloud.api.CloudLogger
import io.qent.broxy.cloud.api.CloudProxyRuntime
import io.qent.broxy.cloud.api.CloudRemoteConnector
import io.qent.broxy.ui.adapter.remote.RemoteConnectorImpl
import kotlinx.coroutines.CoroutineScope

object BroCloudRemoteConnectorFactory {
    @JvmStatic
    fun create(
        logger: CloudLogger,
        proxyRuntime: CloudProxyRuntime,
        scope: CoroutineScope,
    ): CloudRemoteConnector =
        RemoteConnectorImpl(
            logger = logger,
            proxyRuntime = proxyRuntime,
            scope = scope,
        )
}
