package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.core.proxy.runtime.ProxyRuntimeFacade
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.ui.adapter.capabilities.CapabilityRefresher
import io.qent.broxy.ui.adapter.clients.AiClientConnector
import io.qent.broxy.ui.adapter.data.ImportedServerHideRepository
import io.qent.broxy.ui.adapter.data.ImportedServerInstallRepository
import io.qent.broxy.ui.adapter.data.UiSettingsRepository
import io.qent.broxy.ui.adapter.icons.ServerIconRepository
import io.qent.broxy.ui.adapter.models.UiAiClient
import io.qent.broxy.ui.adapter.remote.RemoteConnector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

internal class IntentExecutionContext(
    val scope: CoroutineScope,
    val logger: CollectingLogger,
    val state: StoreStateAccess,
    val capabilityRefresher: CapabilityRefresher,
    val proxyRuntime: ProxyRuntime,
    val proxyRuntimeFacade: ProxyRuntimeFacade,
    val aiClientConnectors: List<AiClientConnector>,
    val buildAiClients: suspend (Int) -> List<UiAiClient>,
    val importedServerHideRepository: ImportedServerHideRepository,
    val importedServerInstallRepository: ImportedServerInstallRepository,
    val loadConfiguration: suspend () -> Result<Unit>,
    val ioDispatcher: CoroutineDispatcher,
    val refreshEnabledCaps: suspend (Boolean) -> Unit,
    val refreshImportedServers: suspend () -> Unit,
    val loadCatalogSnapshot: suspend () -> Result<Unit>,
    val refreshCatalogSnapshot: suspend (Boolean) -> Result<Unit>,
    val syncBackgroundRefresh: () -> Unit,
    val publishReady: () -> Unit,
    val remoteConnector: RemoteConnector,
    val uiSettingsRepository: UiSettingsRepository,
    val serverIconRepository: ServerIconRepository,
)

internal fun IntentExecutionContext.pushToast(message: String) {
    val trimmed = message.trim()
    if (trimmed.isEmpty()) return
    state.updateSnapshot { copy(toastMessage = trimmed, toastMessageId = toastMessageId + 1) }
    publishReady()
}
