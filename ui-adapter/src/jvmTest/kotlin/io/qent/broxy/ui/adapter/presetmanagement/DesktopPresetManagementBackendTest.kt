package io.qent.broxy.ui.adapter.presetmanagement

import io.qent.broxy.core.capabilities.PersistedCapabilityCacheEntry
import io.qent.broxy.core.capabilities.PersistedCapabilityCacheStore
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.presetmanagement.CatalogServerInstallState
import io.qent.broxy.core.presetmanagement.CreatePresetRequest
import io.qent.broxy.core.presetmanagement.GetCatalogServerInstallStatusRequest
import io.qent.broxy.core.presetmanagement.InstallCatalogServerRequest
import io.qent.broxy.core.presetmanagement.NamedPresetManagementItem
import io.qent.broxy.core.presetmanagement.PresetManagementException
import io.qent.broxy.core.presetmanagement.PresetToolSelection
import io.qent.broxy.core.presetmanagement.SetServerEnabledRequest
import io.qent.broxy.core.proxy.runtime.ProxyRuntimeFacade
import io.qent.broxy.core.proxy.runtime.ServerConnectionUpdate
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.Logger
import io.qent.broxy.registry.catalog.CatalogBundle
import io.qent.broxy.registry.catalog.CatalogRemoteTransport
import io.qent.broxy.registry.catalog.CatalogServerDetail
import io.qent.broxy.registry.data.CatalogRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopPresetManagementBackendTest {
    @Test
    fun createPreset_refreshes_presets_in_running_app_context() =
        runTest {
            val repository =
                FakeConfigurationRepository(
                    config = McpServersConfig(servers = listOf(server("s1", "Server 1"))),
                    presets = mutableListOf(),
                )
            var refreshCalls = 0
            val backend =
                buildBackend(
                    scope = this,
                    repository = repository,
                    catalogServers = emptyList(),
                    refreshPresetListAfterCreate = { refreshCalls += 1 },
                )

            backend.createPreset(
                CreatePresetRequest(
                    presetId = "new-preset",
                    presetName = "New preset",
                    tools = listOf(PresetToolSelection(serverId = "s1", toolName = "tool")),
                ),
            )

            assertEquals(1, refreshCalls)
            assertEquals("new-preset", repository.listPresets().single().id)
        }

    @Test
    fun getCatalogServerInstallStatus_uses_mcp_and_capabilities_state_rules() =
        runTest {
            val serverId = "io.qent.broxy/context7"
            val repository =
                FakeConfigurationRepository(
                    config = McpServersConfig(servers = emptyList()),
                    presets = mutableListOf(),
                )
            var liveCapabilities: Map<String, ServerCapabilities> = emptyMap()
            val backend =
                buildBackend(
                    scope = this,
                    repository = repository,
                    catalogServers = listOf(oneClickCatalogServer(serverId)),
                    liveCapabilitiesProvider = { liveCapabilities },
                )

            val notInstalled =
                backend.getCatalogServerInstallStatus(
                    GetCatalogServerInstallStatusRequest(serverId = serverId),
                )
            assertEquals(CatalogServerInstallState.NotInstalled, notInstalled.state)
            assertFalse(notInstalled.installed)
            assertFalse(notInstalled.ready)

            repository.saveMcpConfig(McpServersConfig(servers = listOf(server(serverId, "Context7"))))
            val installing =
                backend.getCatalogServerInstallStatus(
                    GetCatalogServerInstallStatusRequest(serverId = serverId),
                )
            assertEquals(CatalogServerInstallState.Installing, installing.state)
            assertTrue(installing.installed)
            assertFalse(installing.ready)

            liveCapabilities = mapOf(serverId to ServerCapabilities())
            val installed =
                backend.getCatalogServerInstallStatus(
                    GetCatalogServerInstallStatusRequest(serverId = serverId),
                )
            assertEquals(CatalogServerInstallState.Installed, installed.state)
            assertTrue(installed.installed)
            assertTrue(installed.ready)
        }

    @Test
    fun installCatalogServer_denied_returns_error() =
        runTest {
            val serverId = "io.qent.broxy/context7"
            val backend =
                buildBackend(
                    scope = this,
                    catalogServers = listOf(oneClickCatalogServer(serverId)),
                    requestInstallPermission = { false },
                )

            assertFailsWith<PresetManagementException> {
                backend.installCatalogServer(InstallCatalogServerRequest(serverId = serverId))
            }
        }

    @Test
    fun installCatalogServer_allow_starts_async_install_and_status_is_server_id_based() =
        runTest {
            val serverId = "io.qent.broxy/context7"
            val repository =
                FakeConfigurationRepository(
                    config = McpServersConfig(servers = emptyList()),
                    presets = mutableListOf(),
                )
            val backend =
                buildBackend(
                    scope = this,
                    repository = repository,
                    catalogServers = listOf(oneClickCatalogServer(serverId)),
                    requestInstallPermission = { true },
                )

            val started = backend.installCatalogServer(InstallCatalogServerRequest(serverId = serverId))
            assertEquals(CatalogServerInstallState.Installing, started.state)

            advanceUntilIdle()

            assertTrue(repository.loadMcpConfig().servers.any { it.id == serverId })
            val status =
                backend.getCatalogServerInstallStatus(
                    GetCatalogServerInstallStatusRequest(serverId = serverId),
                )
            assertEquals(serverId, status.serverId)
            assertEquals(CatalogServerInstallState.Installing, status.state)
            assertTrue(status.installed)
            assertFalse(status.ready)
        }

    @Test
    fun installCatalogServer_allow_refreshes_ui_before_runtime_update_when_runtime_running() =
        runTest {
            val serverId = "io.qent.broxy/context7"
            val repository =
                FakeConfigurationRepository(
                    config = McpServersConfig(servers = emptyList()),
                    presets = mutableListOf(),
                )
            val callOrder = mutableListOf<String>()
            val proxyRuntime =
                FakeProxyRuntimeFacade(
                    isRunning = true,
                    onUpdateServers = { callOrder += "updateServers" },
                )
            val backend =
                buildBackend(
                    scope = this,
                    repository = repository,
                    catalogServers = listOf(oneClickCatalogServer(serverId)),
                    requestInstallPermission = { true },
                    proxyRuntime = proxyRuntime,
                    refreshUiAfterServerMutation = { callOrder += "refreshUi" },
                )

            val started = backend.installCatalogServer(InstallCatalogServerRequest(serverId = serverId))
            assertEquals(CatalogServerInstallState.Installing, started.state)

            advanceUntilIdle()

            assertTrue(repository.loadMcpConfig().servers.any { it.id == serverId })
            assertEquals(listOf("refreshUi", "updateServers"), callOrder)
            assertTrue(proxyRuntime.refreshServerCapabilitiesCalls.isEmpty())
            val status =
                backend.getCatalogServerInstallStatus(
                    GetCatalogServerInstallStatusRequest(serverId = serverId),
                )
            assertEquals(CatalogServerInstallState.Installing, status.state)
            assertTrue(status.installed)
        }

    @Test
    fun setServerEnabled_toggles_server_enabled_flag() =
        runTest {
            val serverId = "io.qent.broxy/context7"
            val repository =
                FakeConfigurationRepository(
                    config = McpServersConfig(servers = listOf(server(serverId, "Context7", enabled = false))),
                    presets = mutableListOf(),
                )
            val backend =
                buildBackend(
                    scope = this,
                    repository = repository,
                    catalogServers = listOf(oneClickCatalogServer(serverId)),
                )

            val response =
                backend.setServerEnabled(
                    SetServerEnabledRequest(
                        serverId = serverId,
                        enabled = true,
                    ),
                )

            assertTrue(response.enabled)
            assertTrue(
                repository
                    .loadMcpConfig()
                    .servers
                    .first { it.id == serverId }
                    .enabled,
            )
        }

    private fun buildBackend(
        scope: TestScope,
        repository: FakeConfigurationRepository = FakeConfigurationRepository(McpServersConfig(), mutableListOf()),
        catalogServers: List<CatalogServerDetail>,
        liveCapabilitiesProvider: () -> Map<String, ServerCapabilities> = { emptyMap() },
        requestInstallPermission: suspend () -> Boolean = { true },
        refreshPresetListAfterCreate: suspend () -> Unit = {},
        refreshUiAfterServerMutation: suspend () -> Unit = {},
        proxyRuntime: ProxyRuntimeFacade = FakeProxyRuntimeFacade(),
    ): DesktopPresetManagementBackend {
        val catalogRepository = FakeCatalogRepository(CatalogBundle(servers = catalogServers))
        return DesktopPresetManagementBackend(
            configurationRepository = repository,
            liveCapabilitiesProvider = liveCapabilitiesProvider,
            capabilityCacheStore = FakeCapabilityCacheStore(),
            logger = NoopLogger,
            configuredServersProvider = { repository.loadMcpConfig().servers },
            savedPresetNamesProvider = { repository.listPresets().map { NamedPresetManagementItem(it.id, it.name) } },
            refreshPresetListAfterCreate = refreshPresetListAfterCreate,
            catalogRepository = catalogRepository,
            proxyRuntime = proxyRuntime,
            coroutineScope = scope,
            requestInstallPermission = { request ->
                requestInstallPermission()
            },
            refreshUiAfterServerMutation = refreshUiAfterServerMutation,
            agenticModeEnabledProvider = { true },
        )
    }

    private fun oneClickCatalogServer(serverId: String): CatalogServerDetail =
        CatalogServerDetail(
            name = serverId,
            title = "Catalog Server",
            description = "Test catalog server",
            version = "1.0.0",
            remotes =
                listOf(
                    CatalogRemoteTransport(
                        type = "streamable-http",
                        url = "https://example.com/mcp",
                    ),
                ),
        )

    private fun server(
        id: String,
        name: String,
        enabled: Boolean = true,
    ): McpServerConfig =
        McpServerConfig(
            id = id,
            name = name,
            transport = TransportConfig.StdioTransport(command = "noop"),
            enabled = enabled,
        )

    private object NoopLogger : Logger {
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
}

private class FakeCatalogRepository(
    private var bundle: CatalogBundle,
) : CatalogRepository {
    override suspend fun loadCatalog(): Result<CatalogBundle> = Result.success(bundle)

    override suspend fun refreshCatalog(): Result<CatalogBundle?> = Result.success(bundle)
}

private class FakeProxyRuntimeFacade : ProxyRuntimeFacade {
    override val capabilityUpdates: Flow<Map<String, ServerCapabilities>> = emptyFlow()
    override val serverStatusUpdates: Flow<ServerConnectionUpdate> = emptyFlow()
    override val isRunning: Boolean
    private val onUpdateServers: (() -> Unit)?
    val updateServersCalls = mutableListOf<McpServersConfig>()
    val refreshServerCapabilitiesCalls = mutableListOf<String>()

    constructor(
        isRunning: Boolean = false,
        onUpdateServers: (() -> Unit)? = null,
    ) {
        this.isRunning = isRunning
        this.onUpdateServers = onUpdateServers
    }

    override fun start(
        config: McpServersConfig,
        preset: Preset,
        inbound: TransportConfig,
    ): Result<Unit> = Result.success(Unit)

    override fun stop(): Result<Unit> = Result.success(Unit)

    override fun applyPreset(preset: Preset): Result<Unit> = Result.success(Unit)

    override fun updateServers(config: McpServersConfig): Result<Unit> {
        updateServersCalls += config
        onUpdateServers?.invoke()
        return Result.success(Unit)
    }

    override fun refreshServerCapabilities(serverId: String): Result<Unit> {
        refreshServerCapabilitiesCalls += serverId
        return Result.success(Unit)
    }

    override fun refreshFilteredCapabilities(): Result<Unit> = Result.success(Unit)

    override fun updateCallTimeout(seconds: Int) = Unit

    override fun updateCapabilitiesTimeout(seconds: Int) = Unit

    override fun updateConnectionRetryCount(count: Int) = Unit

    override fun updateIgnoreHttpsCertificateErrors(enabled: Boolean) = Unit

    override fun updateFallbackPromptsAndResourcesToTools(enabled: Boolean) = Unit

    override fun updateAdapterMode(enabled: Boolean) = Unit

    override fun registerPresetManagementBackend(backend: io.qent.broxy.core.presetmanagement.PresetManagementBackend) = Unit

    override fun clearPresetManagementBackend() = Unit
}

private class FakeConfigurationRepository(
    private var config: McpServersConfig,
    private val presets: MutableList<Preset>,
) : ConfigurationRepository {
    override fun loadMcpConfig(): McpServersConfig = config

    override fun saveMcpConfig(config: McpServersConfig) {
        this.config = config
    }

    override fun loadPreset(id: String): Preset = presets.firstOrNull { it.id == id } ?: error("Preset '$id' not found")

    override fun savePreset(preset: Preset) {
        presets.removeAll { it.id == preset.id }
        presets += preset
    }

    override fun listPresets(): List<Preset> = presets.toList()

    override fun deletePreset(id: String) {
        presets.removeAll { it.id == id }
    }
}

private class FakeCapabilityCacheStore : PersistedCapabilityCacheStore {
    override fun loadAll(): List<PersistedCapabilityCacheEntry> = emptyList()

    override fun save(entry: PersistedCapabilityCacheEntry) = Unit

    override fun remove(serverId: String) = Unit

    override fun retain(validIds: Set<String>) = Unit
}
