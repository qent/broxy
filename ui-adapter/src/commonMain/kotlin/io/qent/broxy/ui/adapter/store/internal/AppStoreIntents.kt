package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.core.config.ConfigurationManager
import io.qent.broxy.core.proxy.runtime.ProxyRuntimeFacade
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.ui.adapter.capabilities.CapabilityRefresher
import io.qent.broxy.ui.adapter.clients.AiClientConnector
import io.qent.broxy.ui.adapter.data.ImportedServerHideRepository
import io.qent.broxy.ui.adapter.data.ImportedServerInstallRepository
import io.qent.broxy.ui.adapter.data.UiSettingsRepository
import io.qent.broxy.ui.adapter.icons.ServerIconRepository
import io.qent.broxy.ui.adapter.models.UiAiClient
import io.qent.broxy.ui.adapter.models.UiPreset
import io.qent.broxy.ui.adapter.models.UiPresetDraft
import io.qent.broxy.ui.adapter.models.UiServer
import io.qent.broxy.ui.adapter.models.UiServerDraft
import io.qent.broxy.ui.adapter.remote.RemoteConnector
import io.qent.broxy.ui.adapter.store.Intents
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

internal class AppStoreIntents(
    private val scope: CoroutineScope,
    private val logger: CollectingLogger,
    private val configurationManager: ConfigurationManager,
    private val uiSettingsRepository: UiSettingsRepository,
    private val serverIconRepository: ServerIconRepository,
    private val state: StoreStateAccess,
    private val capabilityRefresher: CapabilityRefresher,
    private val proxyRuntime: ProxyRuntime,
    private val proxyRuntimeFacade: ProxyRuntimeFacade,
    private val aiClientConnectors: List<AiClientConnector>,
    private val buildAiClients: suspend (Int) -> List<UiAiClient>,
    private val importedServerHideRepository: ImportedServerHideRepository,
    private val importedServerInstallRepository: ImportedServerInstallRepository,
    private val loadConfiguration: suspend () -> Result<Unit>,
    private val ioDispatcher: CoroutineDispatcher,
    private val refreshEnabledCaps: suspend (Boolean) -> Unit,
    private val refreshImportedServers: suspend () -> Unit,
    private val loadCatalogSnapshot: suspend () -> Result<Unit>,
    private val refreshCatalogSnapshot: suspend (Boolean) -> Result<Unit>,
    private val syncBackgroundRefresh: () -> Unit,
    private val publishReady: () -> Unit,
    private val remoteConnector: RemoteConnector,
) : Intents {
    private val context =
        IntentExecutionContext(
            scope = scope,
            logger = logger,
            state = state,
            capabilityRefresher = capabilityRefresher,
            proxyRuntime = proxyRuntime,
            proxyRuntimeFacade = proxyRuntimeFacade,
            aiClientConnectors = aiClientConnectors,
            buildAiClients = buildAiClients,
            importedServerHideRepository = importedServerHideRepository,
            importedServerInstallRepository = importedServerInstallRepository,
            loadConfiguration = loadConfiguration,
            ioDispatcher = ioDispatcher,
            refreshEnabledCaps = refreshEnabledCaps,
            refreshImportedServers = refreshImportedServers,
            loadCatalogSnapshot = loadCatalogSnapshot,
            refreshCatalogSnapshot = refreshCatalogSnapshot,
            syncBackgroundRefresh = syncBackgroundRefresh,
            publishReady = publishReady,
            remoteConnector = remoteConnector,
            uiSettingsRepository = uiSettingsRepository,
            serverIconRepository = serverIconRepository,
        )

    private val configGateway: StoreConfigGateway = ConfigurationManagerStoreConfigGateway(configurationManager)
    private val serverHandler = ServerIntentsHandler(context, configGateway)
    private val presetHandler = PresetIntentsHandler(context, configGateway)
    private val runtimeSettingsHandler = RuntimeSettingsIntentsHandler(context, configGateway)
    private val integrationsHandler = IntegrationsIntentsHandler(context)
    private val catalogImportHandler =
        CatalogImportIntentsHandler(
            context = context,
            upsertServer = { draft -> serverHandler.upsertServer(draft) },
            upsertCatalogServer = { draft -> serverHandler.upsertCatalogServer(draft) },
            removeServer = { serverId -> serverHandler.removeServer(serverId) },
        )

    override fun refresh() = serverHandler.refresh()

    override fun addOrUpdateServerUi(ui: UiServer) = serverHandler.addOrUpdateServerUi(ui)

    override fun addServerBasic(
        id: String,
        name: String,
    ) = serverHandler.addServerBasic(id, name)

    override fun upsertServer(draft: UiServerDraft) = serverHandler.upsertServer(draft)

    override fun upsertCatalogServer(draft: UiServerDraft) = serverHandler.upsertCatalogServer(draft)

    override fun removeServer(id: String) = serverHandler.removeServer(id)

    override fun toggleServer(
        id: String,
        enabled: Boolean,
    ) = serverHandler.toggleServer(id, enabled)

    override fun reorderServers(serverIds: List<String>) = serverHandler.reorderServers(serverIds)

    override fun refreshServerCapabilities(serverId: String) = serverHandler.refreshServerCapabilities(serverId)

    override fun importServerFromClient(
        clientId: String,
        sourceServerId: String,
    ) = catalogImportHandler.importServerFromClient(clientId, sourceServerId)

    override fun saveImportedServerFromClient(
        clientId: String,
        sourceServerId: String,
        draft: UiServerDraft,
    ) = catalogImportHandler.saveImportedServerFromClient(clientId, sourceServerId, draft)

    override fun hideImportedServer(
        clientId: String,
        sourceServerId: String,
    ) = catalogImportHandler.hideImportedServer(clientId, sourceServerId)

    override fun resetHiddenImportedServers() = catalogImportHandler.resetHiddenImportedServers()

    override fun consumePendingImportedServerCreate() = catalogImportHandler.consumePendingImportedServerCreate()

    override fun refreshCatalog() = catalogImportHandler.refreshCatalog()

    override fun installCatalogServer(serverId: String) = catalogImportHandler.installCatalogServer(serverId)

    override fun uninstallCatalogServer(serverId: String) = catalogImportHandler.uninstallCatalogServer(serverId)

    override fun consumePendingCatalogInstall() = catalogImportHandler.consumePendingCatalogInstall()

    override fun consumePendingCatalogInstalledServer() = serverHandler.consumePendingCatalogInstalledServer()

    override fun pickServerIcon(serverId: String) = serverHandler.pickServerIcon(serverId)

    override fun clearServerIcon(serverId: String) = serverHandler.clearServerIcon(serverId)

    override fun addOrUpdatePreset(preset: UiPreset) = presetHandler.addOrUpdatePreset(preset)

    override fun upsertPreset(draft: UiPresetDraft) = presetHandler.upsertPreset(draft)

    override fun removePreset(id: String) = presetHandler.removePreset(id)

    override fun reorderPresets(presetIds: List<String>) = presetHandler.reorderPresets(presetIds)

    override fun selectProxyPreset(presetId: String?) = presetHandler.selectProxyPreset(presetId)

    override fun updateInboundHttpPort(port: Int) = runtimeSettingsHandler.updateInboundHttpPort(port)

    override fun updateRequestTimeout(seconds: Int) = runtimeSettingsHandler.updateRequestTimeout(seconds)

    override fun updateCapabilitiesTimeout(seconds: Int) = runtimeSettingsHandler.updateCapabilitiesTimeout(seconds)

    override fun updateMcpFilePath(path: String) = runtimeSettingsHandler.updateMcpFilePath(path)

    override fun updateConnectionRetryCount(count: Int) = runtimeSettingsHandler.updateConnectionRetryCount(count)

    override fun updateIgnoreHttpsCertificateErrors(enabled: Boolean) = runtimeSettingsHandler.updateIgnoreHttpsCertificateErrors(enabled)

    override fun updateCapabilitiesRefreshInterval(seconds: Int) = runtimeSettingsHandler.updateCapabilitiesRefreshInterval(seconds)

    override fun updateTrayIconVisibility(visible: Boolean) = runtimeSettingsHandler.updateTrayIconVisibility(visible)

    override fun updateFallbackPromptsAndResourcesToTools(enabled: Boolean) =
        runtimeSettingsHandler.updateFallbackPromptsAndResourcesToTools(enabled)

    override fun updateAdapterMode(enabled: Boolean) = runtimeSettingsHandler.updateAdapterMode(enabled)

    override fun toggleProxyServer() = runtimeSettingsHandler.toggleProxyServer()

    override fun openLogsFolder() = integrationsHandler.openLogsFolder()

    override fun openExternalUrl(url: String) = integrationsHandler.openExternalUrl(url)

    override fun openRemotePortal() = integrationsHandler.openRemotePortal()

    override fun startRemoteAuthorization() = integrationsHandler.startRemoteAuthorization()

    override fun connectRemote() = integrationsHandler.connectRemote()

    override fun disconnectRemote() = integrationsHandler.disconnectRemote()

    override fun logoutRemote() = integrationsHandler.logoutRemote()

    override fun cancelAuthorization(serverId: String) = serverHandler.cancelAuthorization(serverId)

    override fun openAuthorizationInBrowser(
        serverId: String,
        urlOverride: String?,
    ) = serverHandler.openAuthorizationInBrowser(serverId, urlOverride)

    override fun dismissAuthorizationPopup(serverId: String) = serverHandler.dismissAuthorizationPopup(serverId)

    override fun connectAiClient(clientId: String) = integrationsHandler.connectAiClient(clientId)

    override fun disconnectAiClient(clientId: String) = integrationsHandler.disconnectAiClient(clientId)

    override fun openAiClientInfo(clientId: String) = integrationsHandler.openAiClientInfo(clientId)
}
