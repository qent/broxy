package io.qent.broxy.ui.adapter.remote.ws

import io.ktor.client.HttpClient
import io.qent.broxy.core.proxy.ProxyMcpServer
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.ui.adapter.models.UiRemoteStatus
import kotlinx.coroutines.CoroutineScope

class RemoteWsClientFactory {
    fun create(
        httpClient: HttpClient,
        url: String,
        authToken: String,
        serverIdentifier: String,
        proxyProvider: () -> ProxyMcpServer?,
        logger: CollectingLogger,
        scope: CoroutineScope,
        onStatus: (UiRemoteStatus, String?) -> Unit,
        onAuthFailure: (String) -> Unit
    ): RemoteWsClient = RemoteWsClient(
        httpClient = httpClient,
        url = url,
        authToken = authToken,
        serverIdentifier = serverIdentifier,
        proxyProvider = proxyProvider,
        logger = logger,
        scope = scope,
        onStatus = onStatus,
        onAuthFailure = onAuthFailure
    )
}
