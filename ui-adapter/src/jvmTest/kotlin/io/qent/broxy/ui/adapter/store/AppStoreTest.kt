package io.qent.broxy.ui.adapter.store

import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.ToolReference
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.proxy.ProxyMcpServer
import io.qent.broxy.core.proxy.runtime.ProxyController
import io.qent.broxy.core.proxy.runtime.ProxyLifecycle
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.core.utils.LogEvent
import io.qent.broxy.core.utils.Logger
import io.qent.broxy.ui.adapter.models.UiMcpServerConfig
import io.qent.broxy.ui.adapter.models.UiPresetDraft
import io.qent.broxy.ui.adapter.models.UiProxyStatus
import io.qent.broxy.ui.adapter.models.UiServerCapabilities
import io.qent.broxy.ui.adapter.models.UiServerConnStatus
import io.qent.broxy.ui.adapter.models.UiTransportConfig
import io.qent.broxy.ui.adapter.remote.NoOpRemoteConnector
import io.qent.broxy.ui.adapter.remote.defaultRemoteState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppStoreTest {
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

    @org.junit.Test
    fun startLoadsConfigurationAndCachesCapabilities() =
        runTest {
            val server =
                McpServerConfig(
                    id = "s1",
                    name = "Server 1",
                    transport = TransportConfig.StdioTransport(command = "cmd"),
                    env = emptyMap(),
                    enabled = true,
                )
            val config =
                McpServersConfig(
                    servers = listOf(server),
                    requestTimeoutSeconds = 42,
                    capabilitiesTimeoutSeconds = 24,
                    showTrayIcon = true,
                    capabilitiesRefreshIntervalSeconds = 180,
                )
            val preset =
                Preset(
                    id = "dev",
                    name = "Dev",
                    tools = emptyList(),
                )
            val repository =
                FakeConfigurationRepository(
                    config = config,
                    presets = mutableMapOf(preset.id to preset),
                )
            val capabilityFetcher =
                RecordingCapabilityFetcher(
                    result =
                        Result.success(
                            UiServerCapabilities(
                                tools = listOf(io.qent.broxy.core.mcp.ToolDescriptor(name = "alpha")),
                            ),
                        ),
                )
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyLifecycle = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    scope = storeScope,
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                )

            store.start()
            storeScope.advanceUntilIdle()

            val state = store.state.value
            assertTrue(state is UIState.Ready, "Expected Ready state, got $state")
            val ready = state as UIState.Ready
            assertEquals(1, ready.servers.size)
            val uiServer = ready.servers.first()
            assertEquals("s1", uiServer.id)
            assertEquals(UiServerConnStatus.Available, uiServer.status)
            assertEquals(1, uiServer.toolsCount)
            assertEquals(42, ready.requestTimeoutSeconds)
            assertEquals(24, ready.capabilitiesTimeoutSeconds)
            assertEquals(180, ready.capabilitiesRefreshIntervalSeconds)
            assertEquals(listOf(42), proxyController.callTimeoutUpdates)
            assertEquals(listOf(24), proxyController.capabilityTimeoutUpdates)
            assertEquals(listOf("s1"), capabilityFetcher.requestedIds)
            assertEquals(listOf(24), capabilityFetcher.requestedTimeouts)

            storeScope.cancel()
        }

    @org.junit.Test
    fun startAutomaticallyStartsHttpProxy() =
        runTest {
            val server =
                McpServerConfig(
                    id = "s1",
                    name = "Server 1",
                    transport = TransportConfig.StdioTransport(command = "cmd"),
                    env = emptyMap(),
                    enabled = true,
                )
            val config = McpServersConfig(servers = listOf(server))
            val preset = Preset("main", "Main", emptyList())
            val repository =
                FakeConfigurationRepository(
                    config = config,
                    presets = mutableMapOf(preset.id to preset),
                )
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(UiServerCapabilities()))
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyLifecycle = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    scope = storeScope,
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                )

            store.start()
            storeScope.advanceUntilIdle()

            assertEquals(1, proxyController.startCalls.size)
            val params = proxyController.startCalls.first()
            assertEquals(listOf("s1"), params.servers.map { it.id })
            assertEquals(Preset.EMPTY_PRESET_ID, params.preset.id)
            assertIs<TransportConfig.StreamableHttpTransport>(params.inbound)
            assertEquals(config.requestTimeoutSeconds, params.callTimeoutSeconds)
            assertEquals(config.capabilitiesTimeoutSeconds, params.capabilitiesTimeoutSeconds)

            val updatedState = store.state.value
            assertTrue(updatedState is UIState.Ready, "Expected Ready state, got $updatedState")
            val updated = updatedState as UIState.Ready
            assertEquals(UiProxyStatus.Running, updated.proxyStatus)
            assertEquals(config.inboundSsePort, updated.inboundSsePort)
            assertTrue(proxyController.startCalls.first().logsSubscriptionActive, "Logs flow should be active")

            storeScope.cancel()
        }

    @org.junit.Test
    fun toggleServerDisablesCapabilities() =
        runTest {
            val server =
                McpServerConfig(
                    id = "s1",
                    name = "Server 1",
                    transport = TransportConfig.StdioTransport(command = "cmd"),
                    env = emptyMap(),
                    enabled = true,
                )
            val config = McpServersConfig(servers = listOf(server))
            val preset = Preset("main", "Main", emptyList())
            val repository = FakeConfigurationRepository(config, mutableMapOf(preset.id to preset))
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(UiServerCapabilities()))
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyLifecycle = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    scope = storeScope,
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                )

            store.start()
            storeScope.advanceUntilIdle()

            assertEquals(1, proxyController.startCalls.size)
            store.getServerCaps("s1", forceRefresh = true)
            storeScope.advanceUntilIdle()

            var readyState = store.state.value
            assertTrue(readyState is UIState.Ready)

            readyState.intents.toggleServer("s1", enabled = false)
            storeScope.advanceUntilIdle()

            assertEquals(1, proxyController.startCalls.size)
            assertEquals(1, proxyController.updateServersCalls.size)
            readyState = store.state.value
            assertTrue(readyState is UIState.Ready)
            val serverState = (readyState as UIState.Ready).servers.first()
            assertEquals(UiServerConnStatus.Disabled, serverState.status)
            assertTrue(store.listEnabledServerCaps().isEmpty())

            storeScope.cancel()
        }

    @org.junit.Test
    fun updateRequestTimeoutPersistsConfiguration() =
        runTest {
            val server =
                McpServerConfig(
                    id = "s1",
                    name = "Server 1",
                    transport = TransportConfig.StdioTransport(command = "cmd"),
                    env = emptyMap(),
                    enabled = true,
                )
            val config = McpServersConfig(servers = listOf(server), requestTimeoutSeconds = 42)
            val preset = Preset("main", "Main", emptyList())
            val repository = FakeConfigurationRepository(config, mutableMapOf(preset.id to preset))
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(UiServerCapabilities()))
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyLifecycle = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    scope = storeScope,
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                )

            store.start()
            storeScope.advanceUntilIdle()

            val readyState = store.state.value
            assertTrue(readyState is UIState.Ready)
            readyState.intents.updateRequestTimeout(77)
            storeScope.advanceUntilIdle()

            assertEquals(77, repository.config.requestTimeoutSeconds)
            assertEquals(listOf(42, 77), proxyController.callTimeoutUpdates)
            val updatedReady = store.state.value as UIState.Ready
            assertEquals(77, updatedReady.requestTimeoutSeconds)

            storeScope.cancel()
        }

    @org.junit.Test
    fun selectingPresetWhileRunningAppliesPresetWithoutRestart() =
        runTest {
            val server =
                McpServerConfig(
                    id = "s1",
                    name = "Server 1",
                    transport = TransportConfig.StdioTransport(command = "cmd"),
                    env = emptyMap(),
                    enabled = true,
                )
            val config = McpServersConfig(servers = listOf(server))
            val presetMain =
                Preset(
                    id = "main",
                    name = "Main",
                    tools = listOf(ToolReference(serverId = "s1", toolName = "tool", enabled = true)),
                )
            val presetAlt =
                Preset(
                    id = "alt",
                    name = "Alt",
                    tools = listOf(ToolReference(serverId = "s1", toolName = "tool", enabled = true)),
                )
            val repository =
                FakeConfigurationRepository(
                    config = config,
                    presets = mutableMapOf(presetMain.id to presetMain, presetAlt.id to presetAlt),
                )
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(UiServerCapabilities()))
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyLifecycle = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    scope = storeScope,
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                )

            store.start()
            storeScope.advanceUntilIdle()

            assertEquals(1, proxyController.startCalls.size)

            val runningState = store.state.value as UIState.Ready
            runningState.intents.selectProxyPreset("alt")
            storeScope.advanceUntilIdle()

            assertEquals(1, proxyController.startCalls.size)
            assertEquals(listOf("alt"), proxyController.appliedPresets)
            val updated = store.state.value as UIState.Ready
            assertEquals("alt", updated.selectedPresetId)

            storeScope.cancel()
        }

    @org.junit.Test
    fun selectingNoPresetAppliesEmptyPresetWithoutRestart() =
        runTest {
            val server =
                McpServerConfig(
                    id = "s1",
                    name = "Server 1",
                    transport = TransportConfig.StdioTransport(command = "cmd"),
                    env = emptyMap(),
                    enabled = true,
                )
            val config = McpServersConfig(servers = listOf(server), defaultPresetId = "main")
            val presetMain = Preset(id = "main", name = "Main", tools = emptyList())
            val repository =
                FakeConfigurationRepository(
                    config = config,
                    presets = mutableMapOf(presetMain.id to presetMain),
                )
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(UiServerCapabilities()))
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyLifecycle = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    scope = storeScope,
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                )

            store.start()
            storeScope.advanceUntilIdle()
            assertEquals(1, proxyController.startCalls.size)
            assertEquals("main", proxyController.startCalls.last().preset.id)

            val ready = store.state.value as UIState.Ready
            ready.intents.selectProxyPreset(null)
            storeScope.advanceUntilIdle()

            assertEquals(1, proxyController.startCalls.size)
            assertEquals(listOf(Preset.EMPTY_PRESET_ID), proxyController.appliedPresets)

            val updated = store.state.value as UIState.Ready
            assertNull(updated.selectedPresetId)
            assertEquals(UiProxyStatus.Running, updated.proxyStatus)

            storeScope.cancel()
        }

    @org.junit.Test
    fun renamingSelectedPresetDoesNotCreateCopyAndAppliesWithoutRestart() =
        runTest {
            val server =
                McpServerConfig(
                    id = "s1",
                    name = "Server 1",
                    transport = TransportConfig.StdioTransport(command = "cmd"),
                    env = emptyMap(),
                    enabled = true,
                )
            val config = McpServersConfig(servers = listOf(server), defaultPresetId = "main")
            val presetMain =
                Preset(
                    id = "main",
                    name = "Main",
                    tools = listOf(ToolReference(serverId = "s1", toolName = "tool", enabled = true)),
                )
            val repository =
                FakeConfigurationRepository(
                    config = config,
                    presets = mutableMapOf(presetMain.id to presetMain),
                )
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(UiServerCapabilities()))
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyLifecycle = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    scope = storeScope,
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                )

            store.start()
            storeScope.advanceUntilIdle()

            assertEquals(1, proxyController.startCalls.size)
            assertEquals("main", proxyController.startCalls.last().preset.id)

            val ready = store.state.value as UIState.Ready
            ready.intents.upsertPreset(
                UiPresetDraft(
                    id = "renamed",
                    name = "Renamed",
                    tools = emptyList(),
                    prompts = emptyList(),
                    resources = emptyList(),
                    promptsConfigured = true,
                    resourcesConfigured = true,
                    originalId = "main",
                ),
            )
            storeScope.advanceUntilIdle()

            assertEquals(listOf("renamed"), repository.listPresets().map { it.id }.sorted())
            assertEquals("renamed", repository.config.defaultPresetId)

            val updated = store.state.value as UIState.Ready
            assertEquals("renamed", updated.selectedPresetId)
            assertEquals(listOf("renamed"), updated.presets.map { it.id }.sorted())

            assertEquals(1, proxyController.startCalls.size)
            assertEquals(listOf("renamed"), proxyController.appliedPresets)

            storeScope.cancel()
        }

    @org.junit.Test
    fun portBusySetsErrorStatus() =
        runTest {
            val server =
                McpServerConfig(
                    id = "s1",
                    name = "Server 1",
                    transport = TransportConfig.StdioTransport(command = "cmd"),
                    env = emptyMap(),
                    enabled = true,
                )
            val config = McpServersConfig(servers = listOf(server))
            val repository = FakeConfigurationRepository(config, mutableMapOf())
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(UiServerCapabilities()))
            val proxyController =
                FakeProxyController().apply {
                    startResult = Result.failure(IllegalStateException("Port 3335 is already in use"))
                }
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyLifecycle = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    scope = storeScope,
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                )

            store.start()
            storeScope.advanceUntilIdle()

            val state = store.state.value as UIState.Ready
            assertIs<UiProxyStatus.Error>(state.proxyStatus)

            storeScope.cancel()
        }

    @org.junit.Test
    fun updatingInboundPortRestartsProxy() =
        runTest {
            val server =
                McpServerConfig(
                    id = "s1",
                    name = "Server 1",
                    transport = TransportConfig.StdioTransport(command = "cmd"),
                    env = emptyMap(),
                    enabled = true,
                )
            val config = McpServersConfig(servers = listOf(server), inboundSsePort = 3335)
            val preset = Preset("main", "Main", emptyList())
            val repository =
                FakeConfigurationRepository(
                    config = config,
                    presets = mutableMapOf(preset.id to preset),
                )
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(UiServerCapabilities()))
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyLifecycle = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    scope = storeScope,
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                )

            store.start()
            storeScope.advanceUntilIdle()

            assertEquals(1, proxyController.startCalls.size)

            val readyState = store.state.value as UIState.Ready
            readyState.intents.updateInboundSsePort(4444)
            storeScope.advanceUntilIdle()

            assertEquals(2, proxyController.startCalls.size)
            val inbound = proxyController.startCalls.last().inbound as TransportConfig.StreamableHttpTransport
            assertTrue(inbound.url.contains(":4444/"))
            assertEquals(4444, repository.config.inboundSsePort)

            storeScope.cancel()
        }

    private class FakeConfigurationRepository(
        var config: McpServersConfig,
        private val presets: MutableMap<String, Preset>,
    ) : ConfigurationRepository {
        override fun loadMcpConfig(): McpServersConfig = config

        override fun saveMcpConfig(config: McpServersConfig) {
            this.config = config
        }

        override fun loadPreset(id: String): Preset = presets[id] ?: throw IllegalArgumentException("Preset $id not found")

        override fun savePreset(preset: Preset) {
            presets[preset.id] = preset
        }

        override fun listPresets(): List<Preset> = presets.values.toList()

        override fun deletePreset(id: String) {
            presets.remove(id)
        }
    }

    private class FakeProxyController : ProxyController {
        private val _logs = MutableSharedFlow<LogEvent>(extraBufferCapacity = 16)
        override val logs = _logs

        data class StartParams(
            val servers: List<UiMcpServerConfig>,
            val preset: io.qent.broxy.core.models.Preset,
            val inbound: UiTransportConfig,
            val callTimeoutSeconds: Int,
            val capabilitiesTimeoutSeconds: Int,
            val logsSubscriptionActive: Boolean,
        )

        var startResult: Result<Unit> = Result.success(Unit)
        val startCalls = mutableListOf<StartParams>()
        val callTimeoutUpdates = mutableListOf<Int>()
        val capabilityTimeoutUpdates = mutableListOf<Int>()
        val appliedPresets = mutableListOf<String>()
        val updateServersCalls = mutableListOf<List<UiMcpServerConfig>>()

        override fun start(
            servers: List<UiMcpServerConfig>,
            preset: io.qent.broxy.core.models.Preset,
            inbound: UiTransportConfig,
            callTimeoutSeconds: Int,
            capabilitiesTimeoutSeconds: Int,
        ): Result<Unit> {
            startCalls +=
                StartParams(
                    servers = servers,
                    preset = preset,
                    inbound = inbound,
                    callTimeoutSeconds = callTimeoutSeconds,
                    capabilitiesTimeoutSeconds = capabilitiesTimeoutSeconds,
                    logsSubscriptionActive = true,
                )
            return startResult
        }

        override fun stop(): Result<Unit> = Result.success(Unit)

        override fun applyPreset(preset: io.qent.broxy.core.models.Preset): Result<Unit> {
            appliedPresets += preset.id
            return Result.success(Unit)
        }

        override fun updateServers(
            servers: List<UiMcpServerConfig>,
            callTimeoutSeconds: Int,
            capabilitiesTimeoutSeconds: Int,
        ): Result<Unit> {
            updateServersCalls += servers
            return Result.success(Unit)
        }

        override fun updateCallTimeout(seconds: Int) {
            callTimeoutUpdates += seconds
        }

        override fun updateCapabilitiesTimeout(seconds: Int) {
            capabilityTimeoutUpdates += seconds
        }

        override fun currentProxy(): ProxyMcpServer? = null
    }

    private class RecordingCapabilityFetcher(
        private val result: Result<UiServerCapabilities>,
    ) {
        val requestedIds = mutableListOf<String>()
        val requestedTimeouts = mutableListOf<Int>()

        suspend fun invoke(
            config: UiMcpServerConfig,
            timeoutSeconds: Int,
        ): Result<UiServerCapabilities> {
            requestedIds += config.id
            requestedTimeouts += timeoutSeconds
            return result
        }
    }
}
