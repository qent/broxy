package io.qent.bro.ui.adapter.store

import io.qent.bro.core.models.ToolReference
import io.qent.bro.core.repository.ConfigurationRepository
import io.qent.bro.core.utils.CollectingLogger
import io.qent.bro.core.utils.LogEvent
import io.qent.bro.core.mcp.PromptDescriptor
import io.qent.bro.core.mcp.ResourceDescriptor
import io.qent.bro.core.mcp.ToolDescriptor
import io.qent.bro.ui.adapter.models.UiHttpDraft
import io.qent.bro.ui.adapter.models.UiMcpServerConfig
import io.qent.bro.ui.adapter.models.UiMcpServersConfig
import io.qent.bro.ui.adapter.models.UiPresetCore
import io.qent.bro.ui.adapter.models.UiProxyStatus
import io.qent.bro.ui.adapter.models.UiServerCapabilities
import io.qent.bro.ui.adapter.models.UiServerConnStatus
import io.qent.bro.ui.adapter.models.UiServerDraft
import io.qent.bro.ui.adapter.models.UiStdioDraft
import io.qent.bro.ui.adapter.models.UiStdioTransport
import io.qent.bro.ui.adapter.models.UiTransportConfig
import io.qent.bro.ui.adapter.models.UiWebSocketDraft
import io.qent.bro.ui.adapter.models.UiWebSocketTransport
import io.qent.bro.ui.adapter.proxy.ProxyController
import io.qent.bro.ui.adapter.store.UIState
import io.qent.bro.ui.adapter.store.UIState.Error
import io.qent.bro.ui.adapter.store.UIState.Ready
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DEFAULT_TIMEOUT = 42

@OptIn(ExperimentalCoroutinesApi::class)
class AppStoreTest {
    @Test
    fun start_populates_ready_state_and_fetches_caps() = runTest {
        val harness = newHarness(this)

        harness.store.start()
        advanceUntilIdle()

        val state = harness.store.state.value
        assertTrue(state is Ready)
        val ready = state as Ready
        assertEquals(1, ready.servers.size)
        val server = ready.servers.first()
        assertEquals("Server 1", server.name)
        assertEquals(UiServerConnStatus.Available, server.status)
        assertEquals(DEFAULT_CAPABILITIES.tools.size, server.toolsCount)
        assertEquals(listOf(DEFAULT_TIMEOUT), harness.proxyController.updatedTimeouts)
        assertEquals(listOf("server-1"), harness.capabilityFetcher.calls)
        assertEquals(listOf("preset-1"), ready.presets.map { it.id })
        assertEquals(false, ready.showTrayIcon)
    }

    @Test
    fun start_failure_emits_error_state() = runTest {
        val harness = newHarness(this)
        harness.repository.loadConfigError = IllegalStateException("boom")

        harness.store.start()

        val error = withTimeout(1_000) {
            harness.store.state
                .dropWhile { it is UIState.Loading }
                .filterIsInstance<Error>()
                .first()
        }
        assertEquals("boom", error.message)
    }

    @Test
    fun getServerDraft_and_getPresetDraft_return_expected_models() = runTest {
        val harness = newHarness(this)
        harness.store.start()
        advanceUntilIdle()

        val serverDraft = harness.store.getServerDraft("server-1")
        assertNotNull(serverDraft)
        assertEquals("Server 1", serverDraft.name)
        assertTrue(serverDraft.transport is UiStdioDraft)

        val presetDraft = harness.store.getPresetDraft("preset-1")
        assertNotNull(presetDraft)
        assertEquals("preset-1", presetDraft.id)
        assertEquals(1, presetDraft.tools.size)

        harness.repository.loadPresetErrors["missing"] = IllegalStateException("no preset")
        assertNull(harness.store.getPresetDraft("missing"))
    }

    @Test
    fun listEnabledServerCaps_respects_cache_ttl() = runTest {
        val harness = newHarness(this, capsCacheTtlMillis = 10L)
        harness.store.start()
        advanceUntilIdle()

        harness.capabilityFetcher.calls.clear()
        val first = harness.store.listEnabledServerCaps()
        assertEquals(1, first.size)
        assertTrue(harness.capabilityFetcher.calls.isEmpty())

        harness.clock.advanceBy(11L)
        val second = harness.store.listEnabledServerCaps()
        assertEquals(1, second.size)
        assertEquals(listOf("server-1"), harness.capabilityFetcher.calls)
    }

    @Test
    fun addOrUpdateServerUi_failure_reverts_changes() = runTest {
        val harness = newHarness(this)

        harness.store.start()
        advanceUntilIdle()

        harness.repository.saveConfigError = IllegalStateException("nope")
        val ready = harness.store.state.value as Ready
        val updatedServer = ready.servers.first().copy(name = "Changed")
        ready.intents.addOrUpdateServerUi(updatedServer)
        advanceUntilIdle()

        val current = harness.store.state.value as Ready
        assertEquals("Server 1", current.servers.first().name)
        assertEquals("Server 1", harness.repository.config.servers.first().name)
    }

    @Test
    fun upsertServer_success_persists_and_fetches_caps() = runTest {
        val harness = newHarness(this)
        harness.store.start()
        advanceUntilIdle()

        harness.capabilityFetcher.calls.clear()
        val ready = harness.store.state.value as Ready
        val draft = UiServerDraft(
            id = "server-2",
            name = "Server Two",
            enabled = true,
            transport = UiHttpDraft(url = "http://localhost"),
            env = emptyMap()
        )
        ready.intents.upsertServer(draft)
        advanceUntilIdle()

        assertTrue(harness.repository.config.servers.any { it.id == "server-2" })
        assertEquals(listOf("server-2"), harness.capabilityFetcher.calls)
        val updated = harness.store.state.value as Ready
        assertTrue(updated.servers.any { it.id == "server-2" })
    }

    @Test
    fun removePreset_failure_reverts_changes() = runTest {
        val harness = newHarness(this)

        harness.store.start()
        advanceUntilIdle()

        harness.repository.deletePresetError = IllegalStateException("cannot")
        val ready = harness.store.state.value as Ready
        ready.intents.removePreset("preset-1")
        advanceUntilIdle()

        val current = harness.store.state.value as Ready
        assertTrue(current.presets.any { it.id == "preset-1" })
        assertTrue(harness.repository.presets.containsKey("preset-1"))
    }

    @Test
    fun startProxySimple_success_updates_state_and_records_call() = runTest {
        val harness = newHarness(this)
        harness.store.start()
        advanceUntilIdle()

        val ready = harness.store.state.value as Ready
        ready.intents.startProxySimple("preset-1")
        advanceUntilIdle()

        val current = harness.store.state.value as Ready
        assertEquals(UiProxyStatus.Running, current.proxyStatus)
        assertEquals("preset-1", current.selectedPresetId)
        assertEquals(1, harness.proxyController.startCalls.size)
        assertEquals("preset-1", harness.proxyController.startCalls.first().preset.id)
    }

    @Test
    fun startProxy_failure_sets_error_status() = runTest {
        val harness = newHarness(this)
        harness.proxyController.startResult = Result.failure(IllegalStateException("boom"))
        harness.store.start()
        advanceUntilIdle()

        val ready = harness.store.state.value as Ready
        ready.intents.startProxySimple("preset-1")
        advanceUntilIdle()

        val current = harness.store.state.value as Ready
        assertTrue(current.proxyStatus is UiProxyStatus.Error)
        assertEquals("preset-1", current.selectedPresetId)
    }

    @Test
    fun stopProxy_success_sets_status_stopped() = runTest {
        val harness = newHarness(this)
        harness.store.start()
        advanceUntilIdle()

        val ready = harness.store.state.value as Ready
        ready.intents.startProxySimple("preset-1")
        advanceUntilIdle()

        (harness.store.state.value as Ready).intents.stopProxy()
        advanceUntilIdle()

        val current = harness.store.state.value as Ready
        assertEquals(UiProxyStatus.Stopped, current.proxyStatus)
        assertEquals(1, harness.proxyController.stopCalls)
    }

    @Test
    fun stopProxy_failure_sets_error_status() = runTest {
        val harness = newHarness(this)
        harness.proxyController.stopResult = Result.failure(IllegalStateException("fail"))
        harness.store.start()
        advanceUntilIdle()

        val ready = harness.store.state.value as Ready
        ready.intents.startProxySimple("preset-1")
        advanceUntilIdle()

        (harness.store.state.value as Ready).intents.stopProxy()
        advanceUntilIdle()

        val current = harness.store.state.value as Ready
        assertTrue(current.proxyStatus is UiProxyStatus.Error)
    }

    @Test
    fun updateRequestTimeout_failure_reverts_value() = runTest {
        val harness = newHarness(this)

        harness.store.start()
        advanceUntilIdle()

        harness.repository.saveConfigError = IllegalStateException("bad config")
        val ready = harness.store.state.value as Ready
        val initial = ready.requestTimeoutSeconds
        ready.intents.updateRequestTimeout(99)
        advanceUntilIdle()

        val current = harness.store.state.value as Ready
        assertEquals(initial, current.requestTimeoutSeconds)
        assertEquals(initial, harness.repository.config.requestTimeoutSeconds)
        assertEquals(initial, harness.proxyController.updatedTimeouts.last())
    }

    @Test
    fun selectProxyPreset_restart_running_proxy() = runTest {
        val presetTwo = defaultPreset(id = "preset-2", serverId = "server-1")
        val harness = newHarness(this, presets = mapOf(defaultPreset().id to defaultPreset(), presetTwo.id to presetTwo))
        harness.store.start()
        advanceUntilIdle()

        val ready = harness.store.state.value as Ready
        ready.intents.startProxySimple("preset-1")
        advanceUntilIdle()

        harness.proxyController.startCalls.clear()
        ready.intents.selectProxyPreset("preset-2")
        advanceUntilIdle()

        assertEquals(1, harness.proxyController.startCalls.size)
        assertEquals("preset-2", harness.proxyController.startCalls.first().preset.id)
        val current = harness.store.state.value as Ready
        assertEquals("preset-2", current.selectedPresetId)
    }

    @Test
    fun startProxy_with_explicit_inbound_converts_transport() = runTest {
        val harness = newHarness(this)
        harness.store.start()
        advanceUntilIdle()

        val ready = harness.store.state.value as Ready
        ready.intents.startProxy("preset-1", UiWebSocketDraft(url = "ws://localhost"))
        advanceUntilIdle()

        val call = harness.proxyController.startCalls.last()
        assertTrue(call.inbound is UiWebSocketTransport)
    }

    @Test
    fun updateTrayIconVisibility_failure_reverts_value() = runTest {
        val harness = newHarness(this)

        harness.store.start()
        advanceUntilIdle()

        harness.repository.saveConfigError = IllegalStateException("tray")
        val ready = harness.store.state.value as Ready
        val initial = ready.showTrayIcon
        ready.intents.updateTrayIconVisibility(!initial)
        advanceUntilIdle()

        val current = harness.store.state.value as Ready
        assertEquals(initial, current.showTrayIcon)
        assertEquals(initial, harness.repository.config.showTrayIcon)
    }
}

private data class Harness(
    val repository: FakeConfigurationRepository,
    val proxyController: FakeProxyController,
    val capabilityFetcher: FakeCapabilityFetcher,
    val clock: FakeClock,
    val store: AppStore
)

private fun newHarness(
    scope: TestScope,
    config: UiMcpServersConfig = defaultConfig(),
    presets: Map<String, UiPresetCore> = mapOf(defaultPreset().id to defaultPreset()),
    capsCacheTtlMillis: Long = 300_000L
): Harness {
    val repository = FakeConfigurationRepository(config, presets.toMutableMap())
    val proxy = FakeProxyController()
    val capabilityFetcher = FakeCapabilityFetcher()
    val clock = FakeClock()
    val logger = CollectingLogger()
    val dispatcher = StandardTestDispatcher(scope.testScheduler)
    val storeScope = CoroutineScope(SupervisorJob() + dispatcher)
    scope.coroutineContext[Job]?.invokeOnCompletion { storeScope.cancel() }
    val store = AppStore(
        configurationRepository = repository,
        proxyController = proxy,
        capabilityFetcher = capabilityFetcher::fetch,
        logger = logger,
        scope = storeScope,
        now = clock::now,
        capsCacheTtlMillis = capsCacheTtlMillis
    )
    return Harness(repository, proxy, capabilityFetcher, clock, store)
}

private fun defaultConfig(): UiMcpServersConfig = UiMcpServersConfig(
    servers = listOf(defaultServer()),
    requestTimeoutSeconds = DEFAULT_TIMEOUT,
    showTrayIcon = false
)

private fun defaultServer(
    id: String = "server-1",
    name: String = "Server 1"
): UiMcpServerConfig = UiMcpServerConfig(
    id = id,
    name = name,
    transport = UiStdioTransport(command = "run"),
    enabled = true,
    env = emptyMap()
)

private fun defaultPreset(
    id: String = "preset-1",
    serverId: String = "server-1"
): UiPresetCore = UiPresetCore(
    id = id,
    name = "Preset ${id.takeLast(1)}",
    description = "Preset description",
    tools = listOf(ToolReference(serverId = serverId, toolName = "tool-${id}", enabled = true))
)

private val DEFAULT_CAPABILITIES = UiServerCapabilities(
    tools = listOf(ToolDescriptor(name = "tool-1")),
    resources = listOf(ResourceDescriptor(name = "res-1", uri = "uri://res")),
    prompts = listOf(PromptDescriptor(name = "prompt-1"))
)

private class FakeConfigurationRepository(
    initialConfig: UiMcpServersConfig,
    initialPresets: MutableMap<String, UiPresetCore>
) : ConfigurationRepository {
    var config: UiMcpServersConfig = initialConfig
    val presets: MutableMap<String, UiPresetCore> = initialPresets

    var loadConfigError: Throwable? = null
    var saveConfigError: Throwable? = null
    val loadPresetErrors: MutableMap<String, Throwable> = mutableMapOf()
    var savePresetError: Throwable? = null
    var listPresetsError: Throwable? = null
    var deletePresetError: Throwable? = null

    override fun loadMcpConfig(): UiMcpServersConfig {
        loadConfigError?.let { throw it }
        return config
    }

    override fun saveMcpConfig(config: UiMcpServersConfig) {
        saveConfigError?.let { throw it }
        this.config = config
    }

    override fun loadPreset(id: String): UiPresetCore {
        loadPresetErrors[id]?.let { throw it }
        return presets[id] ?: throw IllegalArgumentException("Preset $id not found")
    }

    override fun savePreset(preset: UiPresetCore) {
        savePresetError?.let { throw it }
        presets[preset.id] = preset
    }

    override fun listPresets(): List<UiPresetCore> {
        listPresetsError?.let { throw it }
        return presets.values.toList()
    }

    override fun deletePreset(id: String) {
        deletePresetError?.let { throw it }
        presets.remove(id)
    }
}

private class FakeProxyController : ProxyController {
    data class StartCall(
        val servers: List<UiMcpServerConfig>,
        val preset: UiPresetCore,
        val inbound: UiTransportConfig,
        val timeoutSeconds: Int
    )

    override val logs = emptyFlow<LogEvent>()
    val startCalls = mutableListOf<StartCall>()
    var startResult: Result<Unit> = Result.success(Unit)
    var stopResult: Result<Unit> = Result.success(Unit)
    var stopCalls: Int = 0
    val updatedTimeouts = mutableListOf<Int>()

    override fun start(
        servers: List<UiMcpServerConfig>,
        preset: UiPresetCore,
        inbound: UiTransportConfig,
        callTimeoutSeconds: Int
    ): Result<Unit> {
        startCalls += StartCall(servers.toList(), preset, inbound, callTimeoutSeconds)
        return startResult
    }

    override fun stop(): Result<Unit> {
        stopCalls += 1
        return stopResult
    }

    override fun updateCallTimeout(seconds: Int) {
        updatedTimeouts += seconds
    }
}

private class FakeCapabilityFetcher(
    private val defaultResult: Result<UiServerCapabilities> = Result.success(DEFAULT_CAPABILITIES)
) {
    val calls = mutableListOf<String>()
    val perServerResults: MutableMap<String, Result<UiServerCapabilities>> = mutableMapOf()

    suspend fun fetch(config: UiMcpServerConfig): Result<UiServerCapabilities> {
        calls += config.id
        return perServerResults[config.id] ?: defaultResult
    }
}

private class FakeClock {
    private var current: Long = 0L

    fun now(): Long = current

    fun advanceBy(delta: Long) {
        current += delta
    }
}
