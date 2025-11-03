package io.qent.broxy.ui.adapter.store

import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.ToolReference
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.core.utils.LogEvent
import io.qent.broxy.core.utils.Logger
import io.qent.broxy.ui.adapter.models.UiLogLevel
import io.qent.broxy.ui.adapter.models.UiMcpServerConfig
import io.qent.broxy.ui.adapter.models.UiProxyStatus
import io.qent.broxy.ui.adapter.models.UiServerConnStatus
import io.qent.broxy.ui.adapter.models.UiServerCapabilities
import io.qent.broxy.ui.adapter.models.UiTransportConfig
import io.qent.broxy.ui.adapter.proxy.ProxyController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppStoreTest {
    private val noopLogger = object : Logger {
        override fun debug(message: String) {}
        override fun info(message: String) {}
        override fun warn(message: String, throwable: Throwable?) {}
        override fun error(message: String, throwable: Throwable?) {}
    }

    @org.junit.Test
    fun startLoadsConfigurationAndCachesCapabilities() = runTest {
        val server = McpServerConfig(
            id = "s1",
            name = "Server 1",
            transport = TransportConfig.StdioTransport(command = "cmd"),
            env = emptyMap(),
            enabled = true
        )
        val config = McpServersConfig(
            servers = listOf(server),
            requestTimeoutSeconds = 42,
            showTrayIcon = true
        )
        val preset = Preset(
            id = "dev",
            name = "Dev",
            description = "",
            tools = emptyList()
        )
        val repository = FakeConfigurationRepository(
            config = config,
            presets = mutableMapOf(preset.id to preset)
        )
        val capabilityFetcher = RecordingCapabilityFetcher(
            result = Result.success(
                UiServerCapabilities(
                    tools = listOf(io.qent.broxy.core.mcp.ToolDescriptor(name = "alpha"))
                )
            )
        )
        val proxyController = FakeProxyController()
        val logger = CollectingLogger(delegate = noopLogger)
        val storeScope = TestScope(testScheduler)
        val store = AppStore(
            configurationRepository = repository,
            proxyController = proxyController,
            capabilityFetcher = capabilityFetcher::invoke,
            logger = logger,
            scope = storeScope,
            now = { testScheduler.currentTime },
            capsCacheTtlMillis = Long.MAX_VALUE,
            maxLogs = 10
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
        assertEquals(listOf(42), proxyController.timeoutUpdates)
        assertEquals(listOf("s1"), capabilityFetcher.requestedIds)

        storeScope.cancel()
    }

    @org.junit.Test
    fun loggerEventsAppearInUiState() = runTest {
        val server = McpServerConfig(
            id = "s1",
            name = "Server 1",
            transport = TransportConfig.StdioTransport(command = "cmd"),
            env = emptyMap(),
            enabled = true
        )
        val config = McpServersConfig(servers = listOf(server))
        val preset = Preset("dev", "Dev", "", emptyList())
        val repository = FakeConfigurationRepository(
            config = config,
            presets = mutableMapOf(preset.id to preset)
        )
        val capabilityFetcher = RecordingCapabilityFetcher(Result.success(UiServerCapabilities()))
        val proxyController = FakeProxyController()
        val logger = CollectingLogger(delegate = noopLogger)
        val storeScope = TestScope(testScheduler)
        val store = AppStore(
            configurationRepository = repository,
            proxyController = proxyController,
            capabilityFetcher = capabilityFetcher::invoke,
            logger = logger,
            scope = storeScope,
            now = { testScheduler.currentTime }
        )

        store.start()
        storeScope.advanceUntilIdle()

        logger.info("hello from logger")
        storeScope.advanceUntilIdle()

        val readyState = store.state.value
        assertTrue(readyState is UIState.Ready, "Expected Ready state, got $readyState")
        val ready = readyState as UIState.Ready
        val logEntry = ready.logs.firstOrNull()
        assertNotNull(logEntry)
        assertEquals("hello from logger", logEntry.message)
        assertEquals(UiLogLevel.INFO, logEntry.level)

        storeScope.cancel()
    }

    @org.junit.Test
    fun startProxySimpleDelegatesToProxyController() = runTest {
        val server = McpServerConfig(
            id = "s1",
            name = "Server 1",
            transport = TransportConfig.StdioTransport(command = "cmd"),
            env = emptyMap(),
            enabled = true
        )
        val config = McpServersConfig(servers = listOf(server))
        val preset = Preset(
            id = "main",
            name = "Main",
            description = "",
            tools = listOf(ToolReference(serverId = "s1", toolName = "tool", enabled = true))
        )
        val repository = FakeConfigurationRepository(
            config = config,
            presets = mutableMapOf(preset.id to preset)
        )
        val capabilityFetcher = RecordingCapabilityFetcher(Result.success(UiServerCapabilities()))
        val proxyController = FakeProxyController()
        val logger = CollectingLogger(delegate = noopLogger)
        val storeScope = TestScope(testScheduler)
        val store = AppStore(
            configurationRepository = repository,
            proxyController = proxyController,
            capabilityFetcher = capabilityFetcher::invoke,
            logger = logger,
            scope = storeScope,
            now = { testScheduler.currentTime }
        )

        store.start()
        storeScope.advanceUntilIdle()

        val currentState = store.state.value
        assertTrue(currentState is UIState.Ready, "Expected Ready state before starting proxy, got $currentState")
        val ready = currentState as UIState.Ready
        ready.intents.startProxySimple("main")
        storeScope.advanceUntilIdle()

        assertEquals(1, proxyController.startCalls.size)
        val params = proxyController.startCalls.first()
        assertEquals(listOf("s1"), params.servers.map { it.id })
        assertEquals("main", params.preset.id)
        assertIs<TransportConfig.HttpTransport>(params.inbound)
        assertEquals(config.requestTimeoutSeconds, params.callTimeoutSeconds)

        val updatedState = store.state.value
        assertTrue(updatedState is UIState.Ready, "Expected Ready state after starting proxy, got $updatedState")
        val updated = updatedState as UIState.Ready
        assertEquals(UiProxyStatus.Running, updated.proxyStatus)
        assertEquals("main", updated.selectedPresetId)
        assertTrue(proxyController.startCalls.first().logsSubscriptionActive, "Logs flow should be active")

        storeScope.cancel()
    }

    private class FakeConfigurationRepository(
        var config: McpServersConfig,
        private val presets: MutableMap<String, Preset>
    ) : ConfigurationRepository {
        override fun loadMcpConfig(): McpServersConfig = config

        override fun saveMcpConfig(config: McpServersConfig) {
            this.config = config
        }

        override fun loadPreset(id: String): Preset =
            presets[id] ?: throw IllegalArgumentException("Preset $id not found")

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
            val logsSubscriptionActive: Boolean
        )

        var startResult: Result<Unit> = Result.success(Unit)
        val startCalls = mutableListOf<StartParams>()
        val timeoutUpdates = mutableListOf<Int>()

        override fun start(
            servers: List<UiMcpServerConfig>,
            preset: io.qent.broxy.core.models.Preset,
            inbound: UiTransportConfig,
            callTimeoutSeconds: Int
        ): Result<Unit> {
            startCalls += StartParams(
                servers = servers,
                preset = preset,
                inbound = inbound,
                callTimeoutSeconds = callTimeoutSeconds,
                logsSubscriptionActive = true
            )
            return startResult
        }

        override fun stop(): Result<Unit> = Result.success(Unit)

        override fun updateCallTimeout(seconds: Int) {
            timeoutUpdates += seconds
        }
    }

    private class RecordingCapabilityFetcher(
        private val result: Result<UiServerCapabilities>
    ) {
        val requestedIds = mutableListOf<String>()

        suspend fun invoke(config: UiMcpServerConfig): Result<UiServerCapabilities> {
            requestedIds += config.id
            return result
        }
    }
}
