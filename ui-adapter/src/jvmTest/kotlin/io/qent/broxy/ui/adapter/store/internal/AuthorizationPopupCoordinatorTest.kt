package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.core.mcp.auth.AuthorizationCompletionPageContext
import io.qent.broxy.core.mcp.auth.AuthorizationRequest
import io.qent.broxy.core.mcp.auth.AuthorizationResult
import io.qent.broxy.core.utils.Logger
import io.qent.broxy.ui.adapter.catalog.CatalogConnectionType
import io.qent.broxy.ui.adapter.catalog.CatalogIcon
import io.qent.broxy.ui.adapter.catalog.CatalogServerDetail
import io.qent.broxy.ui.adapter.catalog.CatalogServerEntry
import io.qent.broxy.ui.adapter.models.UiAuthorizationPopupStatus
import io.qent.broxy.ui.adapter.models.UiMcpServerConfig
import io.qent.broxy.ui.adapter.models.UiMcpServersConfig
import io.qent.broxy.ui.adapter.models.UiServer
import io.qent.broxy.ui.adapter.models.UiServerDraft
import io.qent.broxy.ui.adapter.models.UiStreamableHttpTransport
import io.qent.broxy.ui.adapter.store.Intents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthorizationPopupCoordinatorTest {
    @Test
    fun onAuthorizationRequest_sets_popup_for_known_server() {
        val server =
            UiMcpServerConfig(
                id = "context7",
                name = "Context7",
                transport = UiStreamableHttpTransport(url = "https://mcp.context7.com/mcp"),
            )
        var snapshot = StoreSnapshot(isLoading = false, servers = listOf(server))
        val state =
            StoreStateAccess(
                snapshotProvider = { snapshot },
                snapshotUpdater = { block -> snapshot = snapshot.block() },
                snapshotConfigProvider = { UiMcpServersConfig(servers = snapshot.servers) },
                errorHandler = {},
            )
        val intents = RecordingIntents()
        val coordinator = AuthorizationPopupCoordinator(state, intents, publishReady = {}, logger = NoOpLogger)

        coordinator.onAuthorizationRequest(
            AuthorizationRequest(
                resourceUrl = "https://mcp.context7.com/mcp",
                authorizationUrl = "https://auth.example/authorize",
                redirectUri = "http://127.0.0.1/callback",
            ),
        )

        val popup = snapshot.authorizationPopup
        assertNotNull(popup)
        assertEquals("context7", popup.serverId)
        assertEquals(UiAuthorizationPopupStatus.AwaitingBrowserPermission, popup.status)
    }

    @Test
    fun onAuthorizationResult_success_marks_popup_and_refreshes_capabilities() {
        val server =
            UiMcpServerConfig(
                id = "context7",
                name = "Context7",
                transport = UiStreamableHttpTransport(url = "https://mcp.context7.com/mcp"),
            )
        var snapshot = StoreSnapshot(isLoading = false, servers = listOf(server))
        val state =
            StoreStateAccess(
                snapshotProvider = { snapshot },
                snapshotUpdater = { block -> snapshot = snapshot.block() },
                snapshotConfigProvider = { UiMcpServersConfig(servers = snapshot.servers) },
                errorHandler = {},
            )
        val intents = RecordingIntents()
        val coordinator = AuthorizationPopupCoordinator(state, intents, publishReady = {}, logger = NoOpLogger)
        coordinator.onAuthorizationRequest(
            AuthorizationRequest(
                resourceUrl = "https://mcp.context7.com/mcp",
                authorizationUrl = "https://auth.example/authorize",
                redirectUri = "http://127.0.0.1/callback",
            ),
        )

        coordinator.onAuthorizationResult(AuthorizationResult.Success(resourceUrl = "https://mcp.context7.com/mcp"))

        assertEquals(UiAuthorizationPopupStatus.Success, snapshot.authorizationPopup?.status)
        assertEquals(listOf("context7"), intents.refreshedServerIds)
    }

    @Test
    fun onAuthorizationResult_success_keeps_active_popup_when_queue_has_next_server() {
        val firstServer =
            UiMcpServerConfig(
                id = "context7",
                name = "Context7",
                transport = UiStreamableHttpTransport(url = "https://mcp.context7.com/mcp"),
            )
        val secondServer =
            UiMcpServerConfig(
                id = "slack",
                name = "Slack",
                transport = UiStreamableHttpTransport(url = "https://mcp.slack.com/mcp"),
            )
        var snapshot = StoreSnapshot(isLoading = false, servers = listOf(firstServer, secondServer))
        val state =
            StoreStateAccess(
                snapshotProvider = { snapshot },
                snapshotUpdater = { block -> snapshot = snapshot.block() },
                snapshotConfigProvider = { UiMcpServersConfig(servers = snapshot.servers) },
                errorHandler = {},
            )
        val intents = RecordingIntents()
        val coordinator = AuthorizationPopupCoordinator(state, intents, publishReady = {}, logger = NoOpLogger)
        coordinator.onAuthorizationRequest(
            AuthorizationRequest(
                resourceUrl = "https://mcp.context7.com/mcp",
                authorizationUrl = "https://auth.example/authorize/context7",
                redirectUri = "http://127.0.0.1/callback/context7",
            ),
        )
        coordinator.onAuthorizationRequest(
            AuthorizationRequest(
                resourceUrl = "https://mcp.slack.com/mcp",
                authorizationUrl = "https://auth.example/authorize/slack",
                redirectUri = "http://127.0.0.1/callback/slack",
            ),
        )

        coordinator.onAuthorizationResult(
            AuthorizationResult.Success(resourceUrl = "https://mcp.context7.com/mcp"),
        )

        assertEquals("context7", snapshot.authorizationPopup?.serverId)
        assertEquals(UiAuthorizationPopupStatus.Success, snapshot.authorizationPopup?.status)
        assertEquals(1, snapshot.authorizationPopupQueue.size)
        assertEquals("slack", snapshot.authorizationPopupQueue.first().serverId)
    }

    @Test
    fun onAuthorizationRequest_enqueues_second_server_and_shows_it_after_active_failure() {
        val firstServer =
            UiMcpServerConfig(
                id = "context7",
                name = "Context7",
                transport = UiStreamableHttpTransport(url = "https://mcp.context7.com/mcp"),
            )
        val secondServer =
            UiMcpServerConfig(
                id = "slack",
                name = "Slack",
                transport = UiStreamableHttpTransport(url = "https://mcp.slack.com/mcp"),
            )
        var snapshot = StoreSnapshot(isLoading = false, servers = listOf(firstServer, secondServer))
        val state =
            StoreStateAccess(
                snapshotProvider = { snapshot },
                snapshotUpdater = { block -> snapshot = snapshot.block() },
                snapshotConfigProvider = { UiMcpServersConfig(servers = snapshot.servers) },
                errorHandler = {},
            )
        val intents = RecordingIntents()
        val coordinator = AuthorizationPopupCoordinator(state, intents, publishReady = {}, logger = NoOpLogger)

        coordinator.onAuthorizationRequest(
            AuthorizationRequest(
                resourceUrl = "https://mcp.context7.com/mcp",
                authorizationUrl = "https://auth.example/authorize/context7",
                redirectUri = "http://127.0.0.1/callback/context7",
            ),
        )
        coordinator.onAuthorizationRequest(
            AuthorizationRequest(
                resourceUrl = "https://mcp.slack.com/mcp",
                authorizationUrl = "https://auth.example/authorize/slack",
                redirectUri = "http://127.0.0.1/callback/slack",
            ),
        )

        assertEquals("context7", snapshot.authorizationPopup?.serverId)
        assertEquals(1, snapshot.authorizationPopupQueue.size)
        assertEquals("slack", snapshot.authorizationPopupQueue.first().serverId)

        coordinator.onAuthorizationResult(
            AuthorizationResult.Failure(
                resourceUrl = "https://mcp.context7.com/mcp",
                message = "failed",
            ),
        )

        assertEquals("slack", snapshot.authorizationPopup?.serverId)
        assertTrue(snapshot.authorizationPopupQueue.isEmpty())
    }

    @Test
    fun onAuthorizationRequest_deduplicates_existing_server_entry() {
        val firstServer =
            UiMcpServerConfig(
                id = "context7",
                name = "Context7",
                transport = UiStreamableHttpTransport(url = "https://mcp.context7.com/mcp"),
            )
        val secondServer =
            UiMcpServerConfig(
                id = "slack",
                name = "Slack",
                transport = UiStreamableHttpTransport(url = "https://mcp.slack.com/mcp"),
            )
        var snapshot = StoreSnapshot(isLoading = false, servers = listOf(firstServer, secondServer))
        val state =
            StoreStateAccess(
                snapshotProvider = { snapshot },
                snapshotUpdater = { block -> snapshot = snapshot.block() },
                snapshotConfigProvider = { UiMcpServersConfig(servers = snapshot.servers) },
                errorHandler = {},
            )
        val intents = RecordingIntents()
        val coordinator = AuthorizationPopupCoordinator(state, intents, publishReady = {}, logger = NoOpLogger)

        coordinator.onAuthorizationRequest(
            AuthorizationRequest(
                resourceUrl = "https://mcp.context7.com/mcp",
                authorizationUrl = "https://auth.example/authorize/context7-v1",
                redirectUri = "http://127.0.0.1/callback/context7-v1",
            ),
        )
        coordinator.onAuthorizationRequest(
            AuthorizationRequest(
                resourceUrl = "https://mcp.slack.com/mcp",
                authorizationUrl = "https://auth.example/authorize/slack-v1",
                redirectUri = "http://127.0.0.1/callback/slack-v1",
            ),
        )
        coordinator.onAuthorizationRequest(
            AuthorizationRequest(
                resourceUrl = "https://mcp.slack.com/mcp",
                authorizationUrl = "https://auth.example/authorize/slack-v2",
                redirectUri = "http://127.0.0.1/callback/slack-v2",
            ),
        )

        assertEquals("context7", snapshot.authorizationPopup?.serverId)
        assertEquals(1, snapshot.authorizationPopupQueue.size)
        assertEquals("slack", snapshot.authorizationPopupQueue.first().serverId)
        assertEquals("https://auth.example/authorize/slack-v2", snapshot.authorizationPopupQueue.first().authorizationUrl)
    }

    @Test
    fun onAuthorizationResult_cancelled_clears_popup_and_disables_enabled_server() {
        val server =
            UiMcpServerConfig(
                id = "context7",
                name = "Context7",
                transport = UiStreamableHttpTransport(url = "https://mcp.context7.com/mcp"),
                enabled = true,
            )
        var snapshot = StoreSnapshot(isLoading = false, servers = listOf(server))
        val state =
            StoreStateAccess(
                snapshotProvider = { snapshot },
                snapshotUpdater = { block -> snapshot = snapshot.block() },
                snapshotConfigProvider = { UiMcpServersConfig(servers = snapshot.servers) },
                errorHandler = {},
            )
        val intents = RecordingIntents()
        val coordinator = AuthorizationPopupCoordinator(state, intents, publishReady = {}, logger = NoOpLogger)
        coordinator.onAuthorizationRequest(
            AuthorizationRequest(
                resourceUrl = "https://mcp.context7.com/mcp",
                authorizationUrl = "https://auth.example/authorize",
                redirectUri = "http://127.0.0.1/callback",
            ),
        )

        coordinator.onAuthorizationResult(
            AuthorizationResult.Cancelled(
                resourceUrl = "https://mcp.context7.com/mcp",
                message = "cancelled",
            ),
        )

        assertNull(snapshot.authorizationPopup)
        assertEquals(listOf("context7"), intents.toggledServerIds)
    }

    @Test
    fun onAuthorizationResult_failure_ignores_unknown_resource() {
        val server =
            UiMcpServerConfig(
                id = "context7",
                name = "Context7",
                transport = UiStreamableHttpTransport(url = "https://mcp.context7.com/mcp"),
            )
        var snapshot = StoreSnapshot(isLoading = false, servers = listOf(server))
        val state =
            StoreStateAccess(
                snapshotProvider = { snapshot },
                snapshotUpdater = { block -> snapshot = snapshot.block() },
                snapshotConfigProvider = { UiMcpServersConfig(servers = snapshot.servers) },
                errorHandler = {},
            )
        val intents = RecordingIntents()
        val coordinator = AuthorizationPopupCoordinator(state, intents, publishReady = {}, logger = NoOpLogger)

        coordinator.onAuthorizationResult(
            AuthorizationResult.Failure(
                resourceUrl = "https://unknown.example/mcp",
                message = "boom",
            ),
        )

        assertTrue(intents.refreshedServerIds.isEmpty())
        assertTrue(intents.toggledServerIds.isEmpty())
    }

    @Test
    fun resolve_completion_page_context_returns_server_name_and_remote_icon() {
        val server =
            UiMcpServerConfig(
                id = "manual-context7",
                name = "Context7",
                transport = UiStreamableHttpTransport(url = "https://mcp.context7.com/mcp"),
            )
        val iconUrl = "https://cdn.example/context7.png"
        var snapshot =
            StoreSnapshot(
                isLoading = false,
                servers = listOf(server),
                catalogServerEntries = listOf(registryEntry("io.qent.broxy/context7", iconUrl)),
            )
        val state =
            StoreStateAccess(
                snapshotProvider = { snapshot },
                snapshotUpdater = { block -> snapshot = snapshot.block() },
                snapshotConfigProvider = { UiMcpServersConfig(servers = snapshot.servers) },
                errorHandler = {},
            )

        val coordinator = AuthorizationPopupCoordinator(state, NoOpIntents, publishReady = {}, logger = NoOpLogger)

        val context = coordinator.resolveCompletionPageContext("https://mcp.context7.com/mcp")

        assertEquals(
            AuthorizationCompletionPageContext(
                serverName = "Context7",
                iconUrl = iconUrl,
            ),
            context,
        )
    }

    @Test
    fun resolve_completion_page_context_returns_null_for_unknown_server() {
        var snapshot = StoreSnapshot(isLoading = false)
        val state =
            StoreStateAccess(
                snapshotProvider = { snapshot },
                snapshotUpdater = { block -> snapshot = snapshot.block() },
                snapshotConfigProvider = { UiMcpServersConfig(servers = snapshot.servers) },
                errorHandler = {},
            )
        val coordinator = AuthorizationPopupCoordinator(state, NoOpIntents, publishReady = {}, logger = NoOpLogger)

        val context = coordinator.resolveCompletionPageContext("https://unknown.example/mcp")

        assertNull(context)
    }
}

private class RecordingIntents : Intents by NoOpIntents {
    val refreshedServerIds = mutableListOf<String>()
    val toggledServerIds = mutableListOf<String>()

    override fun refreshServerCapabilities(serverId: String) {
        refreshedServerIds += serverId
    }

    override fun toggleServer(
        id: String,
        enabled: Boolean,
    ) {
        if (!enabled) {
            toggledServerIds += id
        }
    }
}

private object NoOpLogger : Logger {
    override fun debug(message: String) = Unit

    override fun info(message: String) = Unit

    override fun warn(
        message: String,
        throwable: Throwable?,
    ) = Unit

    override fun error(
        message: String,
        throwable: Throwable?,
    ) = Unit
}

private object NoOpIntents : Intents {
    override fun refresh() {}

    override fun addOrUpdateServerUi(ui: UiServer) {}

    override fun addServerBasic(
        id: String,
        name: String,
    ) {}

    override fun upsertServer(draft: UiServerDraft) {}

    override fun upsertCatalogServer(draft: UiServerDraft) {}

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
        draft: UiServerDraft,
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

    override fun updateInboundHttpPort(port: Int) {}

    override fun updateRequestTimeout(seconds: Int) {}

    override fun updateCapabilitiesTimeout(seconds: Int) {}

    override fun updateMcpFilePath(path: String) {}

    override fun updateConnectionRetryCount(count: Int) {}

    override fun updateIgnoreHttpsCertificateErrors(enabled: Boolean) {}

    override fun updateCapabilitiesRefreshInterval(seconds: Int) {}

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

    override fun connectAiClient(clientId: String) {}

    override fun disconnectAiClient(clientId: String) {}

    override fun openAiClientInfo(clientId: String) {}
}

private fun registryEntry(
    name: String,
    iconUrl: String,
): CatalogServerEntry =
    CatalogServerEntry(
        detail =
            CatalogServerDetail(
                name = name,
                title = name.replaceFirstChar { it.titlecase() },
                description = "$name description",
                version = "1.0.0",
                icons =
                    listOf(
                        CatalogIcon(
                            src = iconUrl,
                            mimeType = "image/png",
                        ),
                    ),
            ),
        connectionType = CatalogConnectionType.StreamableHttp,
        canInstallWithoutInput = true,
        connectionTypeLabel = "HTTP",
        capabilities = emptyList(),
        iconUrl = iconUrl,
    )
