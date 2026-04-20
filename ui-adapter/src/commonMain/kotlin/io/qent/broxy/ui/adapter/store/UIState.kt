package io.qent.broxy.ui.adapter.store

import io.qent.broxy.ui.adapter.catalog.CatalogInstallSession
import io.qent.broxy.ui.adapter.catalog.CatalogServerItem
import io.qent.broxy.ui.adapter.models.UiAgenticInstallPermissionPopup
import io.qent.broxy.ui.adapter.models.UiAiClient
import io.qent.broxy.ui.adapter.models.UiAuthorizationPopup
import io.qent.broxy.ui.adapter.models.UiImportedServerGroup
import io.qent.broxy.ui.adapter.models.UiPendingImportedServerCreate
import io.qent.broxy.ui.adapter.models.UiPreset
import io.qent.broxy.ui.adapter.models.UiPresetDraft
import io.qent.broxy.ui.adapter.models.UiProxyStatus
import io.qent.broxy.ui.adapter.models.UiRemoteConnectionState
import io.qent.broxy.ui.adapter.models.UiServer
import io.qent.broxy.ui.adapter.models.UiServerDraft

// Sealed UI state for the entire app. UI collects this via Flow and renders.
sealed class UIState {
    data object Loading : UIState()

    data class Error(
        val message: String,
    ) : UIState()

    data class Ready(
        val servers: List<UiServer>,
        val importedServerGroups: List<UiImportedServerGroup>,
        val presets: List<UiPreset>,
        val clients: List<UiAiClient>,
        val activeProxyPresetId: String?,
        val pendingImportedServerCreate: UiPendingImportedServerCreate?,
        val pendingImportedServerCreateRequestId: Long,
        val catalogServers: List<CatalogServerItem>,
        val catalogLoading: Boolean,
        val catalogErrorMessage: String?,
        val catalogUpdatedAtEpochMillis: Long?,
        val pendingCatalogInstallSession: CatalogInstallSession?,
        val pendingCatalogInstallRequestId: Long,
        val pendingCatalogInstalledServerId: String?,
        val pendingCatalogInstalledServerRequestId: Long,
        val toastMessage: String?,
        val toastMessageId: Long,
        val inboundHttpPort: Int,
        val proxyStatus: UiProxyStatus,
        val requestTimeoutSeconds: Int,
        val capabilitiesTimeoutSeconds: Int,
        val mcpFilePath: String,
        val connectionRetryCount: Int,
        val ignoreHttpsCertificateErrors: Boolean,
        val capabilitiesRefreshIntervalSeconds: Int,
        val showTrayIcon: Boolean,
        val fallbackPromptsAndResourcesToTools: Boolean,
        val adapterMode: Boolean,
        val agenticModeEnabled: Boolean,
        val intents: Intents,
        val remote: UiRemoteConnectionState,
        val remoteEnabled: Boolean,
        val authorizationPopup: UiAuthorizationPopup?,
        val agenticInstallPermissionPopup: UiAgenticInstallPermissionPopup?,
    ) : UIState()
}

// Functions that UI may call (Intents). Implemented by ui-adapter only.
interface Intents {
    fun refresh()

    fun addOrUpdateServerUi(ui: UiServer)

    fun addServerBasic(
        id: String,
        name: String,
    )

    fun upsertServer(draft: UiServerDraft)

    fun upsertCatalogServer(draft: UiServerDraft)

    fun removeServer(id: String)

    fun toggleServer(
        id: String,
        enabled: Boolean,
    )

    fun reorderServers(serverIds: List<String>)

    fun refreshServerCapabilities(serverId: String)

    fun importServerFromClient(
        clientId: String,
        sourceServerId: String,
    )

    fun saveImportedServerFromClient(
        clientId: String,
        sourceServerId: String,
        draft: UiServerDraft,
    )

    fun hideImportedServer(
        clientId: String,
        sourceServerId: String,
    )

    fun resetHiddenImportedServers()

    fun consumePendingImportedServerCreate()

    fun refreshCatalog()

    fun installCatalogServer(serverId: String)

    fun uninstallCatalogServer(serverId: String)

    fun consumePendingCatalogInstall()

    fun consumePendingCatalogInstalledServer()

    fun pickServerIcon(serverId: String)

    fun clearServerIcon(serverId: String)

    fun addOrUpdatePreset(preset: UiPreset)

    fun upsertPreset(draft: UiPresetDraft)

    fun removePreset(id: String)

    fun reorderPresets(presetIds: List<String>)

    fun selectProxyPreset(presetId: String?)

    fun setPresetManagementAgenticMode(enabled: Boolean)

    fun updateInboundHttpPort(port: Int)

    fun updateRequestTimeout(seconds: Int)

    fun updateCapabilitiesTimeout(seconds: Int)

    fun updateMcpFilePath(path: String)

    fun updateConnectionRetryCount(count: Int)

    fun updateIgnoreHttpsCertificateErrors(enabled: Boolean)

    fun updateCapabilitiesRefreshInterval(seconds: Int)

    fun updateTrayIconVisibility(visible: Boolean)

    fun updateFallbackPromptsAndResourcesToTools(enabled: Boolean)

    fun updateAdapterMode(enabled: Boolean)

    fun toggleProxyServer()

    fun openLogsFolder()

    fun openExternalUrl(url: String)

    fun openRemotePortal()

    fun startRemoteAuthorization()

    fun connectRemote()

    fun disconnectRemote()

    fun logoutRemote()

    fun cancelAuthorization(serverId: String)

    fun openAuthorizationInBrowser(
        serverId: String,
        urlOverride: String? = null,
    )

    fun dismissAuthorizationPopup(serverId: String)

    fun allowAgenticInstallPermission(requestId: Long)

    fun denyAgenticInstallPermission(requestId: Long)

    fun connectAiClient(clientId: String)

    fun disconnectAiClient(clientId: String)

    fun openAiClientInfo(clientId: String)
}
