package io.qent.broxy.ui.adapter.remote.ws

import io.ktor.client.HttpClient
import io.qent.broxy.cloud.api.CloudLogger
import io.qent.broxy.cloud.api.CloudProxyRuntime
import io.qent.broxy.cloud.api.CloudRemoteStatus
import kotlinx.coroutines.CoroutineScope

class RemoteWsClientFactory {
    fun create(
        httpClient: HttpClient,
        url: String,
        authToken: String,
        serverIdentifier: String,
        proxyRuntime: CloudProxyRuntime,
        logger: CloudLogger,
        scope: CoroutineScope,
        onStatus: (CloudRemoteStatus, String?) -> Unit,
        onAuthFailure: (String) -> Unit,
    ): RemoteWsClient =
        RemoteWsClient(
            httpClient = httpClient,
            url = url,
            authToken = authToken,
            serverIdentifier = serverIdentifier,
            proxyRuntime = proxyRuntime,
            logger = logger,
            scope = scope,
            onStatus = onStatus,
            onAuthFailure = onAuthFailure,
        )
}
