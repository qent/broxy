package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.core.capabilities.CapabilityCache
import io.qent.broxy.core.capabilities.ServerCapsSnapshot
import io.qent.broxy.core.capabilities.ServerStatusTracker
import io.qent.broxy.core.capabilities.ToolSummary
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.ui.adapter.models.UiServerConnStatus
import io.qent.broxy.ui.adapter.store.Intents
import io.qent.broxy.ui.adapter.store.UIState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StoreSnapshotTest {
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
                        McpServerConfig(
                            id = "s1",
                            name = "Server 1",
                            transport = TransportConfig.StdioTransport(command = "cmd"),
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

    private object NoOpIntents : Intents {
        override fun refresh() {}

        override fun addOrUpdateServerUi(ui: io.qent.broxy.ui.adapter.models.UiServer) {}

        override fun addServerBasic(
            id: String,
            name: String,
        ) {}

        override fun upsertServer(draft: io.qent.broxy.ui.adapter.models.UiServerDraft) {}

        override fun removeServer(id: String) {}

        override fun toggleServer(
            id: String,
            enabled: Boolean,
        ) {}

        override fun refreshServerCapabilities(serverId: String) {}

        override fun addOrUpdatePreset(preset: io.qent.broxy.ui.adapter.models.UiPreset) {}

        override fun upsertPreset(draft: io.qent.broxy.ui.adapter.models.UiPresetDraft) {}

        override fun removePreset(id: String) {}

        override fun selectProxyPreset(presetId: String?) {}

        override fun updateInboundSsePort(port: Int) {}

        override fun updateRequestTimeout(seconds: Int) {}

        override fun updateCapabilitiesTimeout(seconds: Int) {}

        override fun updateConnectionRetryCount(count: Int) {}

        override fun updateCapabilitiesRefreshInterval(seconds: Int) {}

        override fun updateTrayIconVisibility(visible: Boolean) {}

        override fun openLogsFolder() {}

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
    }
}
