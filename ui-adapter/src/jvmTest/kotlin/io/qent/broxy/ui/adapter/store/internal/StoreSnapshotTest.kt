package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.core.proxy.runtime.ServerConnectionStatus
import io.qent.broxy.ui.adapter.capabilities.CapabilityCache
import io.qent.broxy.ui.adapter.capabilities.ServerCapsSnapshot
import io.qent.broxy.ui.adapter.capabilities.ServerStatusTracker
import io.qent.broxy.ui.adapter.capabilities.ToolSummary
import io.qent.broxy.ui.adapter.catalog.CatalogConnectionType
import io.qent.broxy.ui.adapter.catalog.CatalogIcon
import io.qent.broxy.ui.adapter.catalog.CatalogRepositoryMetadata
import io.qent.broxy.ui.adapter.catalog.CatalogServerDetail
import io.qent.broxy.ui.adapter.catalog.CatalogServerEntry
import io.qent.broxy.ui.adapter.models.UiMcpServerConfig
import io.qent.broxy.ui.adapter.models.UiPresetCore
import io.qent.broxy.ui.adapter.models.UiServerConnStatus
import io.qent.broxy.ui.adapter.models.UiStdioTransport
import io.qent.broxy.ui.adapter.models.UiStreamableHttpTransport
import io.qent.broxy.ui.adapter.store.Intents
import io.qent.broxy.ui.adapter.store.UIState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StoreSnapshotTest {
    @Test
    fun matchedCatalogMetadataIsExposedInUiServer() {
        val cache = CapabilityCache(now = { 0L })
        val tracker = ServerStatusTracker()
        val snapshot =
            StoreSnapshot(
                isLoading = false,
                servers =
                    listOf(
                        UiMcpServerConfig(
                            id = "ctx",
                            name = "Context7",
                            transport =
                                UiStreamableHttpTransport(
                                    url = "https://mcp.context7.com/mcp",
                                    headers = emptyMap(),
                                ),
                            env = emptyMap(),
                            enabled = true,
                        ),
                    ),
                catalogServerEntries =
                    listOf(
                        CatalogServerEntry(
                            detail =
                                CatalogServerDetail(
                                    name = "io.qent.broxy/context7",
                                    title = "Context7",
                                    description = "Context7 MCP server",
                                    version = "1.0.0",
                                    websiteUrl = "https://context7.com",
                                    repository =
                                        CatalogRepositoryMetadata(
                                            url = "https://github.com/upstash/context7",
                                            source = "github",
                                        ),
                                    icons =
                                        listOf(
                                            CatalogIcon(
                                                src = "https://cdn.example/context7.png",
                                                mimeType = "image/png",
                                            ),
                                        ),
                                ),
                            connectionType = CatalogConnectionType.StreamableHttp,
                            canInstallWithoutInput = true,
                            connectionTypeLabel = "HTTP",
                            capabilities = emptyList(),
                            iconUrl = "https://cdn.example/context7.png",
                        ),
                    ),
            )

        val state = snapshot.toUiState(NoOpIntents, cache, tracker)

        val ready = assertIs<UIState.Ready>(state)
        val server = ready.servers.first()
        assertEquals("https://context7.com", server.externalUrl)
        assertEquals("Context7 MCP server", server.matchedDescription)
    }

    @Test
    fun errorStatusOverridesCachedSnapshot() {
        val cache = CapabilityCache(now = { 0L })
        cache.put(
            "s1",
            ServerCapsSnapshot(
                serverId = "s1",
                name = "Server 1",
                tools = listOf(ToolSummary(name = "tool", description = "")),
            ),
        )
        val tracker = ServerStatusTracker()
        tracker.setError("s1", "boom")
        val snapshot =
            StoreSnapshot(
                isLoading = false,
                servers =
                    listOf(
                        UiMcpServerConfig(
                            id = "s1",
                            name = "Server 1",
                            transport = UiStdioTransport(command = "cmd"),
                            env = emptyMap(),
                            enabled = true,
                        ),
                    ),
            )

        val state = snapshot.toUiState(NoOpIntents, cache, tracker)

        val ready = assertIs<UIState.Ready>(state)
        val server = ready.servers.first()
        assertEquals(UiServerConnStatus.Error, server.status)
        assertEquals("boom", server.errorMessage)
    }

    @Test
    fun cachedSnapshotOverridesConnectingStatus() {
        val cache = CapabilityCache(now = { 0L })
        cache.put(
            "s1",
            ServerCapsSnapshot(
                serverId = "s1",
                name = "Server 1",
                tools = listOf(ToolSummary(name = "tool", description = "")),
            ),
        )
        val tracker = ServerStatusTracker()
        tracker.set("s1", ServerConnectionStatus.Connecting)
        val snapshot =
            StoreSnapshot(
                isLoading = false,
                servers =
                    listOf(
                        UiMcpServerConfig(
                            id = "s1",
                            name = "Server 1",
                            transport = UiStdioTransport(command = "cmd"),
                            env = emptyMap(),
                            enabled = true,
                        ),
                    ),
            )

        val state = snapshot.toUiState(NoOpIntents, cache, tracker)

        val ready = assertIs<UIState.Ready>(state)
        val server = ready.servers.first()
        assertEquals(UiServerConnStatus.Available, server.status)
        assertEquals(null, server.connectingSinceEpochMillis)
    }

    @Test
    fun withPresets_retains_management_built_in_default_id_without_file_backed_presets() {
        val snapshot =
            StoreSnapshot(
                isLoading = false,
                defaultPresetId = UiPresetCore.PRESET_MANAGEMENT_ID,
            )

        val updated = snapshot.withPresets(emptyList())

        assertEquals(UiPresetCore.PRESET_MANAGEMENT_ID, updated.defaultPresetId)
    }

    private object NoOpIntents : Intents {
        override fun refresh() {}

        override fun addOrUpdateServerUi(ui: io.qent.broxy.ui.adapter.models.UiServer) {}

        override fun addServerBasic(
            id: String,
            name: String,
        ) {}

        override fun upsertServer(draft: io.qent.broxy.ui.adapter.models.UiServerDraft) {}

        override fun upsertCatalogServer(draft: io.qent.broxy.ui.adapter.models.UiServerDraft) {}

        override fun removeServer(id: String) {}

        override fun toggleServer(
            id: String,
            enabled: Boolean,
        ) {}

        override fun reorderServers(serverIds: List<String>) {}

        override fun refreshServerCapabilities(serverId: String) {}

        override fun importServerFromClient(
            clientId: String,
            sourceServerId: String,
        ) {}

        override fun saveImportedServerFromClient(
            clientId: String,
            sourceServerId: String,
            draft: io.qent.broxy.ui.adapter.models.UiServerDraft,
        ) {}

        override fun hideImportedServer(
            clientId: String,
            sourceServerId: String,
        ) {}

        override fun resetHiddenImportedServers() {}

        override fun consumePendingImportedServerCreate() {}

        override fun refreshCatalog() {}

        override fun installCatalogServer(serverId: String) {}

        override fun uninstallCatalogServer(serverId: String) {}

        override fun consumePendingCatalogInstall() {}

        override fun consumePendingCatalogInstalledServer() {}

        override fun pickServerIcon(serverId: String) {}

        override fun clearServerIcon(serverId: String) {}

        override fun addOrUpdatePreset(preset: io.qent.broxy.ui.adapter.models.UiPreset) {}

        override fun upsertPreset(draft: io.qent.broxy.ui.adapter.models.UiPresetDraft) {}

        override fun removePreset(id: String) {}

        override fun reorderPresets(presetIds: List<String>) {}

        override fun selectProxyPreset(presetId: String?) {}

        override fun setPresetManagementAgenticMode(enabled: Boolean) {}

        override fun updateInboundHttpPort(port: Int) {}

        override fun updateRequestTimeout(seconds: Int) {}

        override fun updateCapabilitiesTimeout(seconds: Int) {}

        override fun updateConnectionRetryCount(count: Int) {}

        override fun updateIgnoreHttpsCertificateErrors(enabled: Boolean) {}

        override fun updateCapabilitiesRefreshInterval(seconds: Int) {}

        override fun updateMcpFilePath(path: String) {}

        override fun updateTrayIconVisibility(visible: Boolean) {}

        override fun updateFallbackPromptsAndResourcesToTools(enabled: Boolean) {}

        override fun updateAdapterMode(enabled: Boolean) {}

        override fun toggleProxyServer() {}

        override fun openLogsFolder() {}

        override fun openExternalUrl(url: String) {}

        override fun openRemotePortal() {}

        override fun startRemoteAuthorization() {}

        override fun connectRemote() {}

        override fun disconnectRemote() {}

        override fun logoutRemote() {}

        override fun cancelAuthorization(serverId: String) {}

        override fun openAuthorizationInBrowser(
            serverId: String,
            urlOverride: String?,
        ) {}

        override fun dismissAuthorizationPopup(serverId: String) {}

        override fun allowAgenticInstallPermission(requestId: Long) {}

        override fun denyAgenticInstallPermission(requestId: Long) {}

        override fun connectAiClient(clientId: String) {}

        override fun disconnectAiClient(clientId: String) {}

        override fun openAiClientInfo(clientId: String) {}
    }
}
