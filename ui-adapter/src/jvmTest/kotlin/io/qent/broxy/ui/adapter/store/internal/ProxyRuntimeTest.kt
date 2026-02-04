package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.proxy.runtime.ProxyController
import io.qent.broxy.core.proxy.runtime.ProxyLifecycle
import io.qent.broxy.core.proxy.runtime.ServerConnectionUpdate
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.core.utils.LogEvent
import io.qent.broxy.core.utils.Logger
import io.qent.broxy.ui.adapter.models.UiProxyStatus
import io.qent.broxy.ui.adapter.models.UiStreamableHttpTransport
import io.qent.broxy.ui.adapter.models.toUi
import io.qent.broxy.ui.adapter.remote.RemoteConnector
import io.qent.broxy.ui.adapter.remote.RemotePresetChange
import io.qent.broxy.ui.adapter.remote.defaultRemoteState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ProxyRuntimeTest {
    private val noopLogger =
        object : Logger {
            override fun debug(message: String) {}

            override fun info(message: String) {}

            override fun warn(
                message: String,
                throwable: Throwable?,
            ) {}

            override fun error(
                message: String,
                throwable: Throwable?,
            ) {}
        }

    @Test
    fun ensureInboundRunning_notifiesSelectionChange() =
        runTest {
            val config = McpServersConfig()
            val preset = Preset(id = "next", name = "Next")
            val state = SnapshotState(defaultPresetId = "prev", activeProxyPresetId = "prev")
            val remoteConnector = RecordingRemoteConnector()
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val runtime =
                ProxyRuntime(
                    configurationRepository = FakeConfigurationRepository(preset),
                    proxyRuntime = proxyLifecycle,
                    logger = CollectingLogger(delegate = noopLogger),
                    state = state.asAccess(config),
                    publishReady = {},
                    remoteConnector = remoteConnector,
                    onProxyStatusChanged = {},
                )

            runtime.ensureInboundRunning(presetIdOverride = "next")

            assertEquals("next", proxyController.appliedPresetId)
            assertEquals("next", remoteConnector.lastPresetId)
            assertEquals(RemotePresetChange.SELECTION, remoteConnector.lastChangeType)
        }

    @Test
    fun ensureInboundRunning_notifiesCompositionChange() =
        runTest {
            val config = McpServersConfig()
            val preset = Preset(id = "active", name = "Active")
            val state = SnapshotState(defaultPresetId = "active", activeProxyPresetId = "active")
            val remoteConnector = RecordingRemoteConnector()
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val runtime =
                ProxyRuntime(
                    configurationRepository = FakeConfigurationRepository(preset),
                    proxyRuntime = proxyLifecycle,
                    logger = CollectingLogger(delegate = noopLogger),
                    state = state.asAccess(config),
                    publishReady = {},
                    remoteConnector = remoteConnector,
                    onProxyStatusChanged = {},
                )

            runtime.ensureInboundRunning(forceReloadPreset = true)

            assertEquals("active", proxyController.appliedPresetId)
            assertEquals("active", remoteConnector.lastPresetId)
            assertEquals(RemotePresetChange.COMPOSITION, remoteConnector.lastChangeType)
        }

    @Test
    fun ensureInboundRunning_notifiesSelectionChangeOnStart() =
        runTest {
            val config = McpServersConfig()
            val preset = Preset(id = "next", name = "Next")
            val state =
                SnapshotState(
                    defaultPresetId = "next",
                    activeProxyPresetId = null,
                    proxyStatus = UiProxyStatus.Stopped,
                    activeInbound = null,
                )
            val remoteConnector = RecordingRemoteConnector()
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val runtime =
                ProxyRuntime(
                    configurationRepository = FakeConfigurationRepository(preset),
                    proxyRuntime = proxyLifecycle,
                    logger = CollectingLogger(delegate = noopLogger),
                    state = state.asAccess(config),
                    publishReady = {},
                    remoteConnector = remoteConnector,
                    onProxyStatusChanged = {},
                )

            runtime.ensureInboundRunning()

            assertEquals("next", proxyController.startedPresetId)
            assertEquals("next", remoteConnector.lastPresetId)
            assertEquals(RemotePresetChange.SELECTION, remoteConnector.lastChangeType)
        }

    @Test
    fun ensureInboundRunning_skipsRemoteNotificationWhenAdapterModeEnabled() =
        runTest {
            val config = McpServersConfig()
            val preset = Preset(id = "next", name = "Next")
            val state =
                SnapshotState(
                    defaultPresetId = "prev",
                    activeProxyPresetId = "prev",
                    adapterMode = true,
                )
            val remoteConnector = RecordingRemoteConnector()
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val runtime =
                ProxyRuntime(
                    configurationRepository = FakeConfigurationRepository(preset),
                    proxyRuntime = proxyLifecycle,
                    logger = CollectingLogger(delegate = noopLogger),
                    state = state.asAccess(config),
                    publishReady = {},
                    remoteConnector = remoteConnector,
                    onProxyStatusChanged = {},
                )

            runtime.ensureInboundRunning(presetIdOverride = "next")

            assertEquals("next", proxyController.appliedPresetId)
            assertEquals(null, remoteConnector.lastPresetId)
            assertEquals(null, remoteConnector.lastChangeType)
        }

    private class FakeConfigurationRepository(
        private val preset: Preset,
    ) : ConfigurationRepository {
        override fun loadMcpConfig(): McpServersConfig = McpServersConfig()

        override fun saveMcpConfig(config: McpServersConfig) {}

        override fun loadPreset(id: String): Preset = preset

        override fun savePreset(preset: Preset) {}

        override fun listPresets(): List<Preset> = listOf(preset)

        override fun deletePreset(id: String) {}
    }

    private class FakeProxyController : ProxyController {
        private val _logs = MutableSharedFlow<LogEvent>(extraBufferCapacity = 1)
        override val logs: Flow<LogEvent> = _logs
        override val capabilityUpdates: Flow<Map<String, ServerCapabilities>> = emptyFlow()
        override val serverStatusUpdates: Flow<ServerConnectionUpdate> = emptyFlow()

        var appliedPresetId: String? = null
        var startedPresetId: String? = null

        override fun start(
            servers: List<McpServerConfig>,
            preset: Preset,
            inbound: TransportConfig,
            callTimeoutSeconds: Int,
            capabilitiesTimeoutSeconds: Int,
            authorizationTimeoutSeconds: Int,
            connectionRetryCount: Int,
            capabilitiesRefreshIntervalSeconds: Int,
            fallbackPromptsAndResourcesToTools: Boolean,
            adapterMode: Boolean,
        ): Result<Unit> {
            startedPresetId = preset.id
            return Result.success(Unit)
        }

        override fun stop(): Result<Unit> = Result.success(Unit)

        override fun applyPreset(preset: Preset): Result<Unit> {
            appliedPresetId = preset.id
            return Result.success(Unit)
        }

        override fun updateServers(
            servers: List<McpServerConfig>,
            callTimeoutSeconds: Int,
            capabilitiesTimeoutSeconds: Int,
            authorizationTimeoutSeconds: Int,
            connectionRetryCount: Int,
            capabilitiesRefreshIntervalSeconds: Int,
            fallbackPromptsAndResourcesToTools: Boolean,
            adapterMode: Boolean,
        ): Result<Unit> = Result.success(Unit)

        override fun updateCallTimeout(seconds: Int) {}

        override fun updateCapabilitiesTimeout(seconds: Int) {}

        override fun updateConnectionRetryCount(count: Int) {}

        override fun updateFallbackPromptsAndResourcesToTools(enabled: Boolean) {}

        override fun updateAdapterMode(enabled: Boolean) {}

        override fun refreshServerCapabilities(serverId: String): Result<Unit> = Result.success(Unit)

        override fun refreshFilteredCapabilities(): Result<Unit> = Result.success(Unit)
    }

    private class RecordingRemoteConnector : RemoteConnector {
        override val state = MutableStateFlow(defaultRemoteState())
        override val isEnabled: Boolean = true
        var lastPresetId: String? = null
        var lastChangeType: String? = null

        override fun start() {}

        override fun beginAuthorization() {}

        override fun connect() {}

        override fun disconnect() {}

        override fun logout() {}

        override fun onProxyRunningChanged(running: Boolean) {}

        override fun notifyPresetChanged(
            presetId: String?,
            changeType: String,
        ) {
            lastPresetId = presetId
            lastChangeType = changeType
        }
    }

    private data class SnapshotState(
        var defaultPresetId: String?,
        var activeProxyPresetId: String?,
        var proxyStatus: UiProxyStatus = UiProxyStatus.Running,
        var activeInbound: UiStreamableHttpTransport? = UiStreamableHttpTransport(url = "http://localhost:3335/mcp"),
        var adapterMode: Boolean = false,
    ) {
        var snapshot =
            StoreSnapshot(
                isLoading = false,
                proxyStatus = proxyStatus,
                defaultPresetId = defaultPresetId,
                activeProxyPresetId = activeProxyPresetId,
                activeInbound = activeInbound,
                inboundHttpPort = 3335,
                adapterMode = adapterMode,
            )

        fun asAccess(config: McpServersConfig): StoreStateAccess =
            StoreStateAccess(
                snapshotProvider = { snapshot },
                snapshotUpdater = { block -> snapshot = snapshot.block() },
                snapshotConfigProvider = { config.toUi() },
                errorHandler = {},
            )
    }
}
