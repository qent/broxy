package io.qent.broxy.ui.adapter.store

import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.ToolDescriptor
import io.qent.broxy.core.mcp.auth.AuthorizationPresenterRegistry
import io.qent.broxy.core.mcp.auth.AuthorizationRequest
import io.qent.broxy.core.models.AuthConfig
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.ToolReference
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.presetmanagement.PresetManagementBackend
import io.qent.broxy.core.proxy.runtime.ProxyController
import io.qent.broxy.core.proxy.runtime.ProxyLifecycle
import io.qent.broxy.core.proxy.runtime.ServerConnectionStatus
import io.qent.broxy.core.proxy.runtime.ServerConnectionUpdate
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.core.utils.LogEvent
import io.qent.broxy.core.utils.Logger
import io.qent.broxy.ui.adapter.capabilities.CapabilityCacheEntry
import io.qent.broxy.ui.adapter.capabilities.CapabilityCachePersistence
import io.qent.broxy.ui.adapter.capabilities.ServerCapsSnapshot
import io.qent.broxy.ui.adapter.catalog.CatalogBundle
import io.qent.broxy.ui.adapter.catalog.CatalogInput
import io.qent.broxy.ui.adapter.catalog.CatalogInstallPlanner
import io.qent.broxy.ui.adapter.catalog.CatalogLocalTransport
import io.qent.broxy.ui.adapter.catalog.CatalogPackage
import io.qent.broxy.ui.adapter.catalog.CatalogRemoteOAuth
import io.qent.broxy.ui.adapter.catalog.CatalogRemoteTransport
import io.qent.broxy.ui.adapter.catalog.CatalogServerDetail
import io.qent.broxy.ui.adapter.clients.AiClientConnectionRequest
import io.qent.broxy.ui.adapter.clients.AiClientConnector
import io.qent.broxy.ui.adapter.clients.AiClientDescriptor
import io.qent.broxy.ui.adapter.clients.AiClientImportServer
import io.qent.broxy.ui.adapter.clients.AiClientStatus
import io.qent.broxy.ui.adapter.data.CatalogRepository
import io.qent.broxy.ui.adapter.data.FilePickRequest
import io.qent.broxy.ui.adapter.data.ImportedServerHideRepository
import io.qent.broxy.ui.adapter.data.ImportedServerInstallRepository
import io.qent.broxy.ui.adapter.data.SystemPicker
import io.qent.broxy.ui.adapter.data.UiSettingsRepository
import io.qent.broxy.ui.adapter.icons.ServerIconRepository
import io.qent.broxy.ui.adapter.models.UiAuthConfig
import io.qent.broxy.ui.adapter.models.UiAuthorizationPopupStatus
import io.qent.broxy.ui.adapter.models.UiPreset
import io.qent.broxy.ui.adapter.models.UiPresetCore
import io.qent.broxy.ui.adapter.models.UiPresetDraft
import io.qent.broxy.ui.adapter.models.UiProxyStatus
import io.qent.broxy.ui.adapter.models.UiRemoteConnectionState
import io.qent.broxy.ui.adapter.models.UiRemoteStatus
import io.qent.broxy.ui.adapter.models.UiServerConnStatus
import io.qent.broxy.ui.adapter.models.UiServerDraft
import io.qent.broxy.ui.adapter.models.UiSettings
import io.qent.broxy.ui.adapter.models.UiStreamableHttpDraft
import io.qent.broxy.ui.adapter.models.UiStreamableHttpTransport
import io.qent.broxy.ui.adapter.remote.NoOpRemoteConnector
import io.qent.broxy.ui.adapter.remote.RemoteConnector
import io.qent.broxy.ui.adapter.remote.RemotePresetChange
import io.qent.broxy.ui.adapter.remote.defaultRemoteState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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

    private fun ioDispatcher(scope: TestScope): CoroutineDispatcher = requireNotNull(scope.coroutineContext[CoroutineDispatcher])

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
                    connectionRetryCount = 4,
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
                            ServerCapabilities(
                                tools =
                                    listOf(
                                        io.qent.broxy.core.mcp
                                            .ToolDescriptor(name = "alpha"),
                                    ),
                            ),
                        ),
                )
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val uiSettingsRepository =
                FakeUiSettingsRepository(
                    UiSettings(
                        showTrayIcon = false,
                        agentRunNotificationsEnabled = false,
                    ),
                )
            val store =
                AppStore(
                    configurationRepository = repository,
                    uiSettingsRepository = uiSettingsRepository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    aiClientConnectors = emptyList(),
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                )

            store.start()
            storeScope.advanceUntilIdle()
            proxyController.emitCapabilities(
                mapOf(
                    "s1" to
                        ServerCapabilities(
                            tools =
                                listOf(
                                    ToolDescriptor(name = "alpha"),
                                ),
                        ),
                ),
            )
            storeScope.advanceUntilIdle()

            val ready = assertIs<UIState.Ready>(store.state.value)
            assertEquals(1, ready.servers.size)
            val uiServer = ready.servers.first()
            assertEquals("s1", uiServer.id)
            assertEquals(UiServerConnStatus.Available, uiServer.status)
            assertEquals(1, uiServer.toolsCount)
            assertEquals(42, ready.requestTimeoutSeconds)
            assertEquals(24, ready.capabilitiesTimeoutSeconds)
            assertEquals(4, ready.connectionRetryCount)
            assertEquals(180, ready.capabilitiesRefreshIntervalSeconds)
            assertEquals(false, ready.showTrayIcon)
            assertEquals(false, ready.agentRunNotificationsEnabled)
            assertEquals(listOf(42), proxyController.callTimeoutUpdates)
            assertEquals(listOf(24), proxyController.capabilityTimeoutUpdates)
            assertEquals(listOf(4), proxyController.connectionRetryUpdates)
            assertTrue(capabilityFetcher.requestedIds.isEmpty())
            assertTrue(capabilityFetcher.requestedTimeouts.isEmpty())

            storeScope.cancel()
        }

    @org.junit.Test
    fun startUsesPresetManagementWhenDefaultPresetMissingAndNoPresets() =
        runTest {
            val repository =
                FakeConfigurationRepository(
                    config = McpServersConfig(),
                    presets = mutableMapOf(),
                )
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = { _, _, _, _ -> Result.success(ServerCapabilities()) },
                    logger = logger,
                    aiClientConnectors = emptyList(),
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                )

            store.start()
            storeScope.advanceUntilIdle()

            assertEquals(1, proxyController.startCalls.size)
            val startedPresetId =
                proxyController.startCalls
                    .first()
                    .preset
                    .id
            assertEquals(
                UiPresetCore.PRESET_MANAGEMENT_ID,
                startedPresetId,
            )
            val readyState = store.state.value
            val ready = assertIs<UIState.Ready>(readyState)
            assertEquals(UiPresetCore.PRESET_MANAGEMENT_ID, ready.activeProxyPresetId)

            storeScope.cancel()
        }

    @org.junit.Test
    fun startAppliesProxyStatusUpdatesDuringStartup() =
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
            val statusUpdates = MutableSharedFlow<ServerConnectionUpdate>(extraBufferCapacity = 4)
            val proxyController =
                object : ProxyController {
                    override val logs = MutableSharedFlow<LogEvent>(extraBufferCapacity = 4)
                    override val capabilityUpdates = MutableSharedFlow<Map<String, ServerCapabilities>>(replay = 1)
                    override val serverStatusUpdates = statusUpdates

                    override fun start(
                        servers: List<McpServerConfig>,
                        preset: Preset,
                        inbound: TransportConfig,
                        callTimeoutSeconds: Int,
                        capabilitiesTimeoutSeconds: Int,
                        authorizationTimeoutSeconds: Int,
                        connectionRetryCount: Int,
                        ignoreHttpsCertificateErrors: Boolean,
                        capabilitiesRefreshIntervalSeconds: Int,
                        fallbackPromptsAndResourcesToTools: Boolean,
                        adapterMode: Boolean,
                    ): Result<Unit> {
                        statusUpdates.tryEmit(
                            ServerConnectionUpdate(
                                serverId = "s1",
                                status = ServerConnectionStatus.Error,
                                errorMessage = "boom",
                            ),
                        )
                        return Result.success(Unit)
                    }

                    override fun stop(): Result<Unit> = Result.success(Unit)

                    override fun applyPreset(preset: Preset): Result<Unit> = Result.success(Unit)

                    override fun updateServers(
                        servers: List<McpServerConfig>,
                        callTimeoutSeconds: Int,
                        capabilitiesTimeoutSeconds: Int,
                        authorizationTimeoutSeconds: Int,
                        connectionRetryCount: Int,
                        ignoreHttpsCertificateErrors: Boolean,
                        capabilitiesRefreshIntervalSeconds: Int,
                        fallbackPromptsAndResourcesToTools: Boolean,
                        adapterMode: Boolean,
                    ): Result<Unit> = Result.success(Unit)

                    override fun updateCallTimeout(seconds: Int) {}

                    override fun updateCapabilitiesTimeout(seconds: Int) {}

                    override fun updateConnectionRetryCount(count: Int) {}

                    override fun updateIgnoreHttpsCertificateErrors(enabled: Boolean) {}

                    override fun updateFallbackPromptsAndResourcesToTools(enabled: Boolean) {}

                    override fun updateAdapterMode(enabled: Boolean) {}

                    override fun registerPresetManagementBackend(backend: PresetManagementBackend) {}

                    override fun clearPresetManagementBackend() {}

                    override fun refreshServerCapabilities(serverId: String): Result<Unit> = Result.success(Unit)

                    override fun refreshFilteredCapabilities(): Result<Unit> = Result.success(Unit)
                }
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = { _, _, _, _ -> Result.success(ServerCapabilities()) },
                    logger = logger,
                    aiClientConnectors = emptyList(),
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                )

            store.start()
            storeScope.advanceUntilIdle()

            val ready = assertIs<UIState.Ready>(store.state.value)
            val uiServer = ready.servers.first()
            assertEquals(UiServerConnStatus.Error, uiServer.status)
            assertEquals("boom", uiServer.errorMessage)

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
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(ServerCapabilities()))
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    aiClientConnectors = emptyList(),
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
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
            assertEquals(config.authorizationTimeoutSeconds, params.authorizationTimeoutSeconds)
            assertEquals(config.connectionRetryCount, params.connectionRetryCount)
            assertEquals(config.capabilitiesRefreshIntervalSeconds, params.capabilitiesRefreshIntervalSeconds)

            val updated = assertIs<UIState.Ready>(store.state.value)
            assertEquals(UiProxyStatus.Running, updated.proxyStatus)
            assertEquals(config.inboundHttpPort, updated.inboundHttpPort)
            assertTrue(proxyController.startCalls.first().logsSubscriptionActive, "Logs flow should be active")

            storeScope.cancel()
        }

    @org.junit.Test
    fun installCatalogServer_autoInstalls_whenRequiredInputIsNotNeeded() =
        runTest {
            val existingServer =
                McpServerConfig(
                    id = "existing",
                    name = "Existing",
                    transport = TransportConfig.StdioTransport(command = "cmd"),
                    enabled = true,
                )
            val config = McpServersConfig(servers = listOf(existingServer))
            val repository = FakeConfigurationRepository(config = config, presets = mutableMapOf())
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(ServerCapabilities()))
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val catalogRepository =
                FakeCatalogRepository(
                    bundle =
                        CatalogBundle(
                            servers =
                                listOf(
                                    CatalogServerDetail(
                                        name = "time",
                                        title = "Time",
                                        description = "desc",
                                        version = "1.0.0",
                                        packages =
                                            listOf(
                                                CatalogPackage(
                                                    registryType = "pypi",
                                                    identifier = "mcp-server-time",
                                                    runtimeHint = "uvx",
                                                    transport = CatalogLocalTransport(type = "stdio"),
                                                ),
                                            ),
                                    ),
                                ),
                        ),
                )
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    aiClientConnectors = emptyList(),
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                    catalogRepository = catalogRepository,
                )

            store.start()
            storeScope.advanceUntilIdle()
            val ready = assertIs<UIState.Ready>(store.state.value)
            assertTrue(ready.catalogServers.any { it.id == "time" && it.canInstallWithoutInput })

            ready.intents.installCatalogServer("time")
            storeScope.advanceUntilIdle()

            val updated = assertIs<UIState.Ready>(store.state.value)
            assertEquals(listOf("time", "existing"), updated.servers.map { it.id })
            assertEquals(null, updated.pendingCatalogInstallSession)
            assertEquals("time", updated.pendingCatalogInstalledServerId)
            assertTrue(updated.pendingCatalogInstalledServerRequestId > 0L)
            assertEquals(listOf("time", "existing"), repository.config.servers.map { it.id })
            val installedServer = repository.config.servers.first()
            val transport = assertIs<TransportConfig.StdioTransport>(installedServer.transport)
            assertEquals("uvx", transport.command)
            assertEquals(listOf("mcp-server-time"), transport.args)

            val installFocusRequestId = updated.pendingCatalogInstalledServerRequestId
            updated.intents.consumePendingCatalogInstalledServer()
            storeScope.advanceUntilIdle()

            val consumed = assertIs<UIState.Ready>(store.state.value)
            assertEquals(null, consumed.pendingCatalogInstalledServerId)
            assertEquals(installFocusRequestId, consumed.pendingCatalogInstalledServerRequestId)
            assertEquals(listOf("time", "existing"), consumed.servers.map { it.id })

            storeScope.cancel()
        }

    @org.junit.Test
    fun installCatalogServer_opensForm_whenRequiredInputIsNeeded() =
        runTest {
            val config = McpServersConfig()
            val repository = FakeConfigurationRepository(config = config, presets = mutableMapOf())
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(ServerCapabilities()))
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val catalogRepository =
                FakeCatalogRepository(
                    bundle =
                        CatalogBundle(
                            servers =
                                listOf(
                                    CatalogServerDetail(
                                        name = "secured-http",
                                        title = "Secured HTTP",
                                        description = "desc",
                                        version = "1.0.0",
                                        remotes =
                                            listOf(
                                                CatalogRemoteTransport(
                                                    type = "streamable-http",
                                                    url = "https://api.example.com/{workspace}",
                                                    variables =
                                                        mapOf(
                                                            "workspace" to CatalogInput(isRequired = true),
                                                        ),
                                                ),
                                            ),
                                    ),
                                ),
                        ),
                )
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    aiClientConnectors = emptyList(),
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                    catalogRepository = catalogRepository,
                )

            store.start()
            storeScope.advanceUntilIdle()
            val ready = assertIs<UIState.Ready>(store.state.value)
            assertTrue(ready.catalogServers.any { it.id == "secured-http" && !it.canInstallWithoutInput })

            ready.intents.installCatalogServer("secured-http")
            storeScope.advanceUntilIdle()

            val updated = assertIs<UIState.Ready>(store.state.value)
            assertTrue(updated.servers.none { it.id == "secured-http" })
            assertEquals("secured-http", updated.pendingCatalogInstallSession?.serverId)

            storeScope.cancel()
        }

    @org.junit.Test
    fun installCatalogServer_opensForm_whenInstallStepsPresent_evenIfDefaultsAllowOneClick() =
        runTest {
            val config = McpServersConfig()
            val repository = FakeConfigurationRepository(config = config, presets = mutableMapOf())
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(ServerCapabilities()))
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val catalogRepository =
                FakeCatalogRepository(
                    bundle =
                        CatalogBundle(
                            servers =
                                listOf(
                                    CatalogServerDetail(
                                        name = "defaulted-http",
                                        title = "Defaulted HTTP",
                                        description = "desc",
                                        version = "1.0.0",
                                        remotes =
                                            listOf(
                                                CatalogRemoteTransport(
                                                    type = "streamable-http",
                                                    url = "https://api.example.com/{workspace}",
                                                    variables =
                                                        mapOf(
                                                            "workspace" to CatalogInput(isRequired = true, default = "acme"),
                                                        ),
                                                ),
                                            ),
                                        meta =
                                            buildJsonObject {
                                                put(
                                                    "install_steps",
                                                    buildJsonArray {
                                                        add(JsonPrimitive("Use [workspace]"))
                                                    },
                                                )
                                            },
                                    ),
                                ),
                        ),
                )
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    aiClientConnectors = emptyList(),
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                    catalogRepository = catalogRepository,
                )

            store.start()
            storeScope.advanceUntilIdle()
            val ready = assertIs<UIState.Ready>(store.state.value)
            assertTrue(ready.catalogServers.any { it.id == "defaulted-http" && !it.canInstallWithoutInput })

            ready.intents.installCatalogServer("defaulted-http")
            storeScope.advanceUntilIdle()

            val updated = assertIs<UIState.Ready>(store.state.value)
            assertTrue(updated.servers.none { it.id == "defaulted-http" })
            assertEquals("defaulted-http", updated.pendingCatalogInstallSession?.serverId)
            assertEquals(listOf("Use [workspace]"), updated.pendingCatalogInstallSession?.installSteps)

            storeScope.cancel()
        }

    @org.junit.Test
    fun installCatalogServer_copiesRegistryOauthFieldsIntoSavedConfig() =
        runTest {
            val repository = FakeConfigurationRepository(config = McpServersConfig(), presets = mutableMapOf())
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(ServerCapabilities()))
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val catalogRepository =
                FakeCatalogRepository(
                    bundle =
                        CatalogBundle(
                            servers =
                                listOf(
                                    CatalogServerDetail(
                                        name = "slack",
                                        title = "Slack",
                                        description = "desc",
                                        version = "1.0.0",
                                        remotes =
                                            listOf(
                                                CatalogRemoteTransport(
                                                    type = "streamable-http",
                                                    url = "https://mcp.slack.com/mcp",
                                                    oauth =
                                                        CatalogRemoteOAuth(
                                                            type = "oauth",
                                                            clientId = "{slack_client_id}",
                                                            clientSecret = "{slack_client_secret}",
                                                            callbackPort = JsonPrimitive("{slack_callback_port}"),
                                                            redirectUri = "https://localhost:{slack_callback_port}/callback",
                                                            tokenEndpointAuthMethod = "client_secret_post",
                                                            allowDynamicRegistration = false,
                                                        ),
                                                    variables =
                                                        mapOf(
                                                            "slack_client_id" to CatalogInput(isRequired = true),
                                                            "slack_client_secret" to CatalogInput(isRequired = true, isSecret = true),
                                                            "slack_callback_port" to CatalogInput(default = "3118"),
                                                        ),
                                                ),
                                            ),
                                    ),
                                ),
                        ),
                )
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    aiClientConnectors = emptyList(),
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                    catalogRepository = catalogRepository,
                )

            store.start()
            storeScope.advanceUntilIdle()
            val ready = assertIs<UIState.Ready>(store.state.value)
            ready.intents.installCatalogServer("slack")
            storeScope.advanceUntilIdle()

            val readyWithSession = assertIs<UIState.Ready>(store.state.value)
            val session = assertNotNull(readyWithSession.pendingCatalogInstallSession)
            val fieldValues = CatalogInstallPlanner.buildInitialFieldValues(session).toMutableMap()
            fieldValues[session.fields.first { it.label == "slack_client_id" }.id] = "slack-client"
            fieldValues[session.fields.first { it.label == "slack_client_secret" }.id] = "slack-secret"
            val installDraft =
                CatalogInstallPlanner
                    .buildInstallResult(session = session, displayName = "", fieldValues = fieldValues)
                    .getOrThrow()
                    .draft

            readyWithSession.intents.upsertCatalogServer(installDraft)
            storeScope.advanceUntilIdle()

            val savedServer = repository.config.servers.first { it.id == "slack" }
            val savedAuth = assertIs<AuthConfig.OAuth>(savedServer.auth)
            assertEquals("slack-client", savedAuth.clientId)
            assertEquals("slack-secret", savedAuth.clientSecret)
            assertEquals(3118, savedAuth.callbackPort)
            assertEquals("https://localhost:3118/callback", savedAuth.redirectUri)
            assertEquals("client_secret_post", savedAuth.tokenEndpointAuthMethod)
            assertEquals(false, savedAuth.allowDynamicRegistration)

            storeScope.cancel()
        }

    @org.junit.Test
    fun upsertCatalogServer_placesNewServerFirst_whenSavingFromInstallForm() =
        runTest {
            val existingServer =
                McpServerConfig(
                    id = "existing",
                    name = "Existing",
                    transport = TransportConfig.StdioTransport(command = "cmd"),
                    enabled = true,
                )
            val config = McpServersConfig(servers = listOf(existingServer))
            val repository = FakeConfigurationRepository(config = config, presets = mutableMapOf())
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(ServerCapabilities()))
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val catalogRepository =
                FakeCatalogRepository(
                    bundle =
                        CatalogBundle(
                            servers =
                                listOf(
                                    CatalogServerDetail(
                                        name = "secured-http",
                                        title = "Secured HTTP",
                                        description = "desc",
                                        version = "1.0.0",
                                        remotes =
                                            listOf(
                                                CatalogRemoteTransport(
                                                    type = "streamable-http",
                                                    url = "https://api.example.com/{workspace}",
                                                    variables =
                                                        mapOf(
                                                            "workspace" to CatalogInput(isRequired = true),
                                                        ),
                                                ),
                                            ),
                                    ),
                                ),
                        ),
                )
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    aiClientConnectors = emptyList(),
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                    catalogRepository = catalogRepository,
                )

            store.start()
            storeScope.advanceUntilIdle()
            val ready = assertIs<UIState.Ready>(store.state.value)
            ready.intents.installCatalogServer("secured-http")
            storeScope.advanceUntilIdle()

            val readyWithSession = assertIs<UIState.Ready>(store.state.value)
            assertEquals("secured-http", readyWithSession.pendingCatalogInstallSession?.serverId)
            readyWithSession.intents.upsertCatalogServer(
                UiServerDraft(
                    id = "secured-http",
                    name = "Secured HTTP",
                    enabled = true,
                    transport = UiStreamableHttpDraft(url = "https://api.example.com/acme"),
                    env = emptyMap(),
                    auth =
                        UiAuthConfig.OAuth(
                            clientId = "slack-client",
                            clientSecret = "slack-secret",
                            callbackPort = 3118,
                            tokenEndpointAuthMethod = "client_secret_post",
                            allowDynamicRegistration = false,
                        ),
                    originalId = null,
                    iconPath = null,
                ),
            )
            storeScope.advanceUntilIdle()

            val updated = assertIs<UIState.Ready>(store.state.value)
            assertEquals(listOf("secured-http", "existing"), updated.servers.map { it.id })
            assertEquals("secured-http", updated.pendingCatalogInstalledServerId)
            assertTrue(updated.pendingCatalogInstalledServerRequestId > 0L)
            assertEquals(listOf("secured-http", "existing"), repository.config.servers.map { it.id })
            val savedAuth =
                assertIs<AuthConfig.OAuth>(
                    repository.config.servers
                        .first()
                        .auth,
                )
            assertEquals("slack-client", savedAuth.clientId)
            assertEquals("slack-secret", savedAuth.clientSecret)
            assertEquals(3118, savedAuth.callbackPort)
            assertEquals("client_secret_post", savedAuth.tokenEndpointAuthMethod)
            assertEquals(false, savedAuth.allowDynamicRegistration)

            storeScope.cancel()
        }

    @org.junit.Test
    fun upsertServer_placesManualCreateAtTop_andSignalsFocusScroll() =
        runTest {
            val firstServer =
                McpServerConfig(
                    id = "first",
                    name = "First",
                    transport = TransportConfig.StdioTransport(command = "cmd"),
                    enabled = true,
                )
            val secondServer =
                McpServerConfig(
                    id = "second",
                    name = "Second",
                    transport = TransportConfig.StdioTransport(command = "cmd"),
                    enabled = true,
                )
            val config = McpServersConfig(servers = listOf(firstServer, secondServer))
            val repository = FakeConfigurationRepository(config = config, presets = mutableMapOf())
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(ServerCapabilities()))
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    aiClientConnectors = emptyList(),
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                )

            store.start()
            storeScope.advanceUntilIdle()
            val ready = assertIs<UIState.Ready>(store.state.value)
            ready.intents.upsertServer(
                UiServerDraft(
                    id = "manual-new",
                    name = "Manual New",
                    enabled = true,
                    transport = UiStreamableHttpDraft(url = "http://localhost:9002/mcp"),
                    env = emptyMap(),
                    originalId = null,
                    iconPath = null,
                ),
            )
            storeScope.advanceUntilIdle()

            val updated = assertIs<UIState.Ready>(store.state.value)
            assertEquals(listOf("manual-new", "first", "second"), updated.servers.map { it.id })
            assertEquals("manual-new", updated.pendingCatalogInstalledServerId)
            assertTrue(updated.pendingCatalogInstalledServerRequestId > 0L)
            assertEquals(listOf("manual-new", "first", "second"), repository.config.servers.map { it.id })

            storeScope.cancel()
        }

    @org.junit.Test
    fun startLoadsImportableServersAndFiltersBroxyAndHidden() =
        runTest {
            val config = McpServersConfig()
            val repository = FakeConfigurationRepository(config = config, presets = mutableMapOf())
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(ServerCapabilities()))
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val hideRepository =
                FakeImportedServerHideRepository(
                    mutableSetOf("cursor::hidden"),
                )
            val connectors =
                listOf(
                    FakeAiClientConnector(
                        id = "cursor",
                        name = "Cursor",
                        iconId = "cursor",
                        importableServers =
                            listOf(
                                importServer("broxy", "broxy"),
                                importServer("hidden", "Hidden"),
                                importServer("alpha", "Alpha"),
                            ),
                    ),
                    FakeAiClientConnector(
                        id = "claude-code",
                        name = "Claude Code",
                        iconId = "claude_code",
                        importableServers =
                            listOf(
                                importServer("delta", "Delta"),
                            ),
                    ),
                )
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    aiClientConnectors = connectors,
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                    importedServerHideRepository = hideRepository,
                )

            store.start()
            storeScope.advanceUntilIdle()

            val ready = assertIs<UIState.Ready>(store.state.value)
            assertEquals(listOf("Claude Code", "Cursor"), ready.importedServerGroups.map { it.clientName })
            assertEquals(listOf("Delta"), ready.importedServerGroups[0].servers.map { it.name })
            assertEquals(listOf("Alpha"), ready.importedServerGroups[1].servers.map { it.name })

            storeScope.cancel()
        }

    @org.junit.Test
    fun importServerQueuesPrefilledCreateWithoutPersisting() =
        runTest {
            val existingServer =
                McpServerConfig(
                    id = "github",
                    name = "GitHub",
                    transport = TransportConfig.StdioTransport(command = "cmd"),
                    enabled = true,
                )
            val config = McpServersConfig(servers = listOf(existingServer))
            val repository = FakeConfigurationRepository(config = config, presets = mutableMapOf())
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(ServerCapabilities()))
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val hideRepository = FakeImportedServerHideRepository()
            val installRepository = FakeImportedServerInstallRepository()
            val connectors =
                listOf(
                    FakeAiClientConnector(
                        id = "cursor",
                        name = "Cursor",
                        iconId = "cursor",
                        importableServers =
                            listOf(
                                importServer("github", "GitHub"),
                            ),
                    ),
                )
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    aiClientConnectors = connectors,
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                    importedServerHideRepository = hideRepository,
                    importedServerInstallRepository = installRepository,
                )

            store.start()
            storeScope.advanceUntilIdle()
            val ready = assertIs<UIState.Ready>(store.state.value)

            ready.intents.importServerFromClient(clientId = "cursor", sourceServerId = "github")
            storeScope.advanceUntilIdle()

            val updated = assertIs<UIState.Ready>(store.state.value)
            assertEquals(1, updated.servers.size)
            assertEquals(listOf("github"), repository.config.servers.map { it.id })
            assertTrue(proxyController.updateServersCalls.isEmpty())
            assertTrue(hideRepository.hiddenKeys.isEmpty())
            assertTrue(installRepository.installedMappings.isEmpty())
            val pendingCreate = updated.pendingImportedServerCreate
            assertTrue(pendingCreate != null)
            assertEquals("cursor", pendingCreate.clientId)
            assertEquals("github", pendingCreate.sourceServerId)
            assertEquals("GitHub", pendingCreate.draft.name)
            assertEquals("github", pendingCreate.draft.id)

            storeScope.cancel()
        }

    @org.junit.Test
    fun saveImportedServerFromClientCreatesServerAndHidesWhileInstalled() =
        runTest {
            val config = McpServersConfig()
            val repository = FakeConfigurationRepository(config = config, presets = mutableMapOf())
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(ServerCapabilities()))
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val hideRepository = FakeImportedServerHideRepository()
            val installRepository = FakeImportedServerInstallRepository()
            val connectors =
                listOf(
                    FakeAiClientConnector(
                        id = "cursor",
                        name = "Cursor",
                        iconId = "cursor",
                        importableServers = listOf(importServer("alpha", "Alpha")),
                    ),
                )
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    aiClientConnectors = connectors,
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                    importedServerHideRepository = hideRepository,
                    importedServerInstallRepository = installRepository,
                )

            store.start()
            storeScope.advanceUntilIdle()
            val ready = assertIs<UIState.Ready>(store.state.value)

            ready.intents.saveImportedServerFromClient(
                clientId = "cursor",
                sourceServerId = "alpha",
                draft =
                    UiServerDraft(
                        id = "alpha-imported",
                        name = "Alpha Imported",
                        enabled = true,
                        transport = UiStreamableHttpDraft(url = "http://localhost:9001/mcp"),
                        env = emptyMap(),
                        originalId = null,
                        iconPath = null,
                    ),
            )
            storeScope.advanceUntilIdle()

            val updated = assertIs<UIState.Ready>(store.state.value)
            assertTrue(updated.servers.any { it.id == "alpha-imported" && it.name == "Alpha Imported" })
            assertTrue(updated.importedServerGroups.isEmpty())
            assertEquals("alpha-imported", installRepository.installedMappings["cursor::alpha"])
            assertTrue(hideRepository.hiddenKeys.isEmpty())
            assertTrue(repository.config.servers.any { it.id == "alpha-imported" && it.name == "Alpha Imported" })

            storeScope.cancel()
        }

    @org.junit.Test
    fun removingInstalledImportedServerShowsImportAgain() =
        runTest {
            val config = McpServersConfig()
            val repository = FakeConfigurationRepository(config = config, presets = mutableMapOf())
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(ServerCapabilities()))
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val installRepository = FakeImportedServerInstallRepository()
            val connectors =
                listOf(
                    FakeAiClientConnector(
                        id = "cursor",
                        name = "Cursor",
                        iconId = "cursor",
                        importableServers = listOf(importServer("alpha", "Alpha")),
                    ),
                )
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    aiClientConnectors = connectors,
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                    importedServerInstallRepository = installRepository,
                )

            store.start()
            storeScope.advanceUntilIdle()
            var ready = assertIs<UIState.Ready>(store.state.value)

            ready.intents.saveImportedServerFromClient(
                clientId = "cursor",
                sourceServerId = "alpha",
                draft =
                    UiServerDraft(
                        id = "alpha-imported",
                        name = "Alpha Imported",
                        enabled = true,
                        transport = UiStreamableHttpDraft(url = "http://localhost:9001/mcp"),
                        env = emptyMap(),
                        originalId = null,
                        iconPath = null,
                    ),
            )
            storeScope.advanceUntilIdle()

            ready = assertIs<UIState.Ready>(store.state.value)
            assertTrue(ready.importedServerGroups.isEmpty())

            ready.intents.removeServer("alpha-imported")
            storeScope.advanceUntilIdle()

            val afterRemove = assertIs<UIState.Ready>(store.state.value)
            val namesAfterRemove =
                afterRemove.importedServerGroups
                    .first()
                    .servers
                    .map { it.name }
            assertEquals(listOf("Alpha"), namesAfterRemove)

            storeScope.cancel()
        }

    @org.junit.Test
    fun hideImportedServerKeepsItHiddenAfterInstalledServerRemoval() =
        runTest {
            val config = McpServersConfig()
            val repository = FakeConfigurationRepository(config = config, presets = mutableMapOf())
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(ServerCapabilities()))
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val hideRepository = FakeImportedServerHideRepository()
            val installRepository = FakeImportedServerInstallRepository()
            val connectors =
                listOf(
                    FakeAiClientConnector(
                        id = "cursor",
                        name = "Cursor",
                        iconId = "cursor",
                        importableServers = listOf(importServer("alpha", "Alpha")),
                    ),
                )
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    aiClientConnectors = connectors,
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                    importedServerHideRepository = hideRepository,
                    importedServerInstallRepository = installRepository,
                )

            store.start()
            storeScope.advanceUntilIdle()
            var ready = assertIs<UIState.Ready>(store.state.value)

            ready.intents.saveImportedServerFromClient(
                clientId = "cursor",
                sourceServerId = "alpha",
                draft =
                    UiServerDraft(
                        id = "alpha-imported",
                        name = "Alpha Imported",
                        enabled = true,
                        transport = UiStreamableHttpDraft(url = "http://localhost:9001/mcp"),
                        env = emptyMap(),
                        originalId = null,
                        iconPath = null,
                    ),
            )
            storeScope.advanceUntilIdle()

            ready = assertIs<UIState.Ready>(store.state.value)
            ready.intents.removeServer("alpha-imported")
            storeScope.advanceUntilIdle()

            ready = assertIs<UIState.Ready>(store.state.value)
            val namesBeforeHide =
                ready.importedServerGroups
                    .first()
                    .servers
                    .map { it.name }
            assertEquals(listOf("Alpha"), namesBeforeHide)

            ready.intents.hideImportedServer(clientId = "cursor", sourceServerId = "alpha")
            storeScope.advanceUntilIdle()

            val afterHide = assertIs<UIState.Ready>(store.state.value)
            assertTrue(afterHide.importedServerGroups.isEmpty())
            assertTrue(hideRepository.hiddenKeys.contains("cursor::alpha"))

            storeScope.cancel()
        }

    @org.junit.Test
    fun renamingImportedServerIdShowsImportAgain() =
        runTest {
            val config = McpServersConfig()
            val repository = FakeConfigurationRepository(config = config, presets = mutableMapOf())
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(ServerCapabilities()))
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val installRepository = FakeImportedServerInstallRepository()
            val connectors =
                listOf(
                    FakeAiClientConnector(
                        id = "cursor",
                        name = "Cursor",
                        iconId = "cursor",
                        importableServers = listOf(importServer("alpha", "Alpha")),
                    ),
                )
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    aiClientConnectors = connectors,
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                    importedServerInstallRepository = installRepository,
                )

            store.start()
            storeScope.advanceUntilIdle()
            val ready = assertIs<UIState.Ready>(store.state.value)

            ready.intents.saveImportedServerFromClient(
                clientId = "cursor",
                sourceServerId = "alpha",
                draft =
                    UiServerDraft(
                        id = "alpha-imported",
                        name = "Alpha Imported",
                        enabled = true,
                        transport = UiStreamableHttpDraft(url = "http://localhost:9001/mcp"),
                        env = emptyMap(),
                        originalId = null,
                        iconPath = null,
                    ),
            )
            storeScope.advanceUntilIdle()

            val readyAfterSave = assertIs<UIState.Ready>(store.state.value)
            readyAfterSave.intents.upsertServer(
                UiServerDraft(
                    id = "alpha-renamed",
                    name = "Alpha Imported",
                    enabled = true,
                    transport = UiStreamableHttpDraft(url = "http://localhost:9001/mcp"),
                    env = emptyMap(),
                    originalId = "alpha-imported",
                    iconPath = null,
                ),
            )
            storeScope.advanceUntilIdle()

            val afterRename = assertIs<UIState.Ready>(store.state.value)
            assertTrue(afterRename.servers.any { it.id == "alpha-renamed" })
            val namesAfterRename =
                afterRename.importedServerGroups
                    .first()
                    .servers
                    .map { it.name }
            assertEquals(listOf("Alpha"), namesAfterRename)

            storeScope.cancel()
        }

    @org.junit.Test
    fun resetHiddenImportedServersRescansClients() =
        runTest {
            val installedServer =
                McpServerConfig(
                    id = "installed-alpha",
                    name = "Installed Alpha",
                    transport = TransportConfig.StdioTransport(command = "cmd"),
                    enabled = true,
                )
            val config = McpServersConfig(servers = listOf(installedServer))
            val repository = FakeConfigurationRepository(config = config, presets = mutableMapOf())
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(ServerCapabilities()))
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val hideRepository = FakeImportedServerHideRepository(mutableSetOf("cursor::alpha"))
            val installRepository =
                FakeImportedServerInstallRepository(
                    mutableMapOf("cursor::alpha" to "installed-alpha"),
                )
            val connectors =
                listOf(
                    FakeAiClientConnector(
                        id = "cursor",
                        name = "Cursor",
                        iconId = "cursor",
                        importableServers = listOf(importServer("alpha", "Alpha")),
                    ),
                )
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    aiClientConnectors = connectors,
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                    importedServerHideRepository = hideRepository,
                    importedServerInstallRepository = installRepository,
                )

            store.start()
            storeScope.advanceUntilIdle()

            val readyBeforeReset = assertIs<UIState.Ready>(store.state.value)
            assertTrue(readyBeforeReset.importedServerGroups.isEmpty())

            readyBeforeReset.intents.resetHiddenImportedServers()
            storeScope.advanceUntilIdle()

            val readyAfterReset = assertIs<UIState.Ready>(store.state.value)
            assertTrue(hideRepository.hiddenKeys.isEmpty())
            assertTrue(readyAfterReset.importedServerGroups.isEmpty())

            readyAfterReset.intents.removeServer("installed-alpha")
            storeScope.advanceUntilIdle()

            val readyAfterRemove = assertIs<UIState.Ready>(store.state.value)
            assertEquals(
                listOf("Alpha"),
                readyAfterRemove
                    .importedServerGroups
                    .first()
                    .servers
                    .map { it.name },
            )

            storeScope.cancel()
        }

    @org.junit.Test
    fun refreshFailureKeepsErrorState() =
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
            val repository =
                ToggleableConfigurationRepository(
                    config = config,
                    presets = mutableMapOf(),
                )
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(ServerCapabilities()))
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    aiClientConnectors = emptyList(),
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                )

            store.start()
            storeScope.advanceUntilIdle()

            val ready = assertIs<UIState.Ready>(store.state.value)
            assertEquals(1, proxyController.startCalls.size)

            repository.failLoad = true
            ready.intents.refresh()
            storeScope.advanceUntilIdle()

            val error = assertIs<UIState.Error>(store.state.value)
            assertEquals("boom", error.message)
            assertEquals(1, proxyController.startCalls.size)

            storeScope.cancel()
        }

    @org.junit.Test
    fun concurrentRemoteAndServerUpdatesKeepBoth() =
        runTest {
            val repository = FakeConfigurationRepository(McpServersConfig(), mutableMapOf())
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = TestRemoteConnector(defaultRemoteState())
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = { _, _, _, _ -> Result.success(ServerCapabilities()) },
                    logger = logger,
                    aiClientConnectors = emptyList(),
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { System.currentTimeMillis() },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                )

            try {
                store.start()
                storeScope.advanceUntilIdle()

                val intents = (store.state.value as UIState.Ready).intents
                val targetRemote =
                    defaultRemoteState().copy(
                        email = "user@broxy.run",
                        hasCredentials = true,
                        status = UiRemoteStatus.WsOnline,
                    )

                val iterations = 50
                repeat(iterations) { index ->
                    val remoteReady = CompletableDeferred<Unit>()
                    val serverReady = CompletableDeferred<Unit>()
                    val startSignal = CompletableDeferred<Unit>()
                    val remoteJob =
                        storeScope.launch {
                            remoteReady.complete(Unit)
                            startSignal.await()
                            remoteConnector.emit(targetRemote.copy(message = "m$index"))
                        }
                    val serverJob =
                        storeScope.launch {
                            serverReady.complete(Unit)
                            startSignal.await()
                            intents.addServerBasic("s$index", "Server $index")
                        }
                    remoteReady.await()
                    serverReady.await()
                    startSignal.complete(Unit)
                    joinAll(remoteJob, serverJob)
                }

                storeScope.advanceUntilIdle()

                val ready = assertIs<UIState.Ready>(store.state.value)
                assertEquals(iterations, ready.servers.size)
                assertEquals("m${iterations - 1}", ready.remote.message)
                assertEquals(UiRemoteStatus.WsOnline, ready.remote.status)
                assertEquals("user@broxy.run", ready.remote.email)
            } finally {
                storeScope.cancel()
            }
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
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(ServerCapabilities()))
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    aiClientConnectors = emptyList(),
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                )

            store.start()
            storeScope.advanceUntilIdle()

            assertEquals(1, proxyController.startCalls.size)
            proxyController.emitCapabilities(mapOf("s1" to ServerCapabilities()))
            storeScope.advanceUntilIdle()

            var readyState = assertIs<UIState.Ready>(store.state.value)

            readyState.intents.toggleServer("s1", enabled = false)
            storeScope.advanceUntilIdle()

            assertEquals(1, proxyController.startCalls.size)
            assertEquals(1, proxyController.updateServersCalls.size)
            readyState = assertIs<UIState.Ready>(store.state.value)
            val serverState = readyState.servers.first()
            assertEquals(UiServerConnStatus.Disabled, serverState.status)
            assertTrue(store.listEnabledServerCaps().isEmpty())
            assertEquals(listOf("s1"), store.listSelectableServerCaps().map { it.serverId })

            storeScope.cancel()
        }

    @org.junit.Test
    fun updateAdapterMode_notifiesRemotePresetChangeWhenProxyRunning() =
        runTest {
            val preset =
                Preset(
                    id = "dev",
                    name = "Dev",
                    tools = emptyList(),
                )
            val repository =
                FakeConfigurationRepository(
                    config = McpServersConfig(defaultPresetId = "dev"),
                    presets = mutableMapOf(preset.id to preset),
                )
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = TestRemoteConnector(defaultRemoteState())
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = { _, _, _, _ -> Result.success(ServerCapabilities()) },
                    logger = logger,
                    aiClientConnectors = emptyList(),
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                )

            store.start()
            storeScope.advanceUntilIdle()

            val intents = (store.state.value as UIState.Ready).intents
            intents.updateAdapterMode(true)
            storeScope.advanceUntilIdle()

            assertEquals("dev", remoteConnector.lastPresetId)
            assertEquals(RemotePresetChange.COMPOSITION, remoteConnector.lastChangeType)

            storeScope.cancel()
        }

    @org.junit.Test
    fun toggleServerUsesCachedCapabilitiesWithoutImmediateRefresh() =
        runTest {
            val server =
                McpServerConfig(
                    id = "s1",
                    name = "Server 1",
                    transport = TransportConfig.StdioTransport(command = "cmd"),
                    env = emptyMap(),
                    enabled = false,
                )
            val config = McpServersConfig(servers = listOf(server))
            val preset = Preset("main", "Main", emptyList())
            val repository = FakeConfigurationRepository(config, mutableMapOf(preset.id to preset))
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(ServerCapabilities()))
            val proxyController = FakeProxyController().apply { startResult = Result.failure(IllegalStateException("boom")) }
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val cachedSnapshot = ServerCapsSnapshot(serverId = "s1", name = "Server 1")
            val persistence =
                TestCapabilityCachePersistence(
                    listOf(
                        CapabilityCacheEntry(
                            serverId = "s1",
                            timestampMillis = 0L,
                            snapshot = cachedSnapshot,
                        ),
                    ),
                )
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    aiClientConnectors = emptyList(),
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { 0L },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                    capabilityCachePersistence = persistence,
                )

            store.start()
            storeScope.advanceUntilIdle()

            val readyState = store.state.value as UIState.Ready
            readyState.intents.toggleServer("s1", enabled = true)
            storeScope.advanceUntilIdle()

            assertTrue(capabilityFetcher.requestedIds.isEmpty())
            val updated = store.state.value as UIState.Ready
            assertEquals(UiServerConnStatus.Available, updated.servers.first().status)

            storeScope.cancel()
        }

    @org.junit.Test
    fun toggleServerRollsBackSnapshotWhenPersistFails() =
        runTest {
            val server =
                McpServerConfig(
                    id = "s1",
                    name = "Server 1",
                    transport = TransportConfig.StdioTransport(command = "cmd"),
                    env = emptyMap(),
                    enabled = true,
                )
            val repository =
                ToggleableConfigurationRepository(
                    config = McpServersConfig(servers = listOf(server)),
                    presets = mutableMapOf(),
                )
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(ServerCapabilities()))
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    aiClientConnectors = emptyList(),
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = NoOpRemoteConnector(defaultRemoteState()),
                )

            store.start()
            storeScope.advanceUntilIdle()
            val readyState = assertIs<UIState.Ready>(store.state.value)

            repository.failSave = true
            readyState.intents.toggleServer("s1", enabled = false)
            storeScope.advanceUntilIdle()

            assertIs<UIState.Error>(store.state.value)
            assertEquals(true, store.getServerDraft("s1")?.enabled)
            assertEquals(true, repository.config.servers[0].enabled)

            storeScope.cancel()
        }

    @org.junit.Test
    fun getServerDraft_returnsAuthConfiguration() =
        runTest {
            val server =
                McpServerConfig(
                    id = "s1",
                    name = "Server 1",
                    transport = TransportConfig.StreamableHttpTransport(url = "https://example.com/mcp"),
                    env = emptyMap(),
                    enabled = true,
                    auth =
                        AuthConfig.OAuth(
                            clientId = "client-id",
                            clientSecret = "client-secret",
                            callbackPort = 3118,
                            tokenEndpointAuthMethod = "client_secret_post",
                            allowDynamicRegistration = false,
                        ),
                )
            val repository =
                FakeConfigurationRepository(
                    config = McpServersConfig(servers = listOf(server)),
                    presets = mutableMapOf(),
                )
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(ServerCapabilities()))
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    aiClientConnectors = emptyList(),
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = NoOpRemoteConnector(defaultRemoteState()),
                )

            store.start()
            storeScope.advanceUntilIdle()

            val draft = assertNotNull(store.getServerDraft("s1"))
            val auth = assertIs<UiAuthConfig.OAuth>(assertNotNull(draft.auth))
            assertEquals("client-id", auth.clientId)
            assertEquals("client-secret", auth.clientSecret)
            assertEquals(3118, auth.callbackPort)
            assertEquals("client_secret_post", auth.tokenEndpointAuthMethod)
            assertEquals(false, auth.allowDynamicRegistration)

            storeScope.cancel()
        }

    @org.junit.Test
    fun connectAndDisconnectAiClientRefreshesStatusAndImports() =
        runTest {
            val connector =
                StatefulAiClientConnector(
                    id = "codex",
                    name = "Codex",
                    iconId = "codex",
                    importableServers = listOf(importServer(sourceServerId = "ext", name = "External")),
                )
            val server =
                McpServerConfig(
                    id = "s1",
                    name = "Server 1",
                    transport = TransportConfig.StdioTransport(command = "cmd"),
                    env = emptyMap(),
                    enabled = true,
                )
            val repository =
                FakeConfigurationRepository(
                    config = McpServersConfig(servers = listOf(server)),
                    presets = mutableMapOf(),
                )
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(ServerCapabilities()))
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    aiClientConnectors = listOf(connector),
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = NoOpRemoteConnector(defaultRemoteState()),
                )

            store.start()
            storeScope.advanceUntilIdle()
            var ready = assertIs<UIState.Ready>(store.state.value)
            val initialImportCalls = connector.loadImportableServersCalls
            assertEquals(false, ready.clients.single { it.id == "codex" }.isConnected)

            ready.intents.connectAiClient("codex")
            storeScope.advanceUntilIdle()
            ready = assertIs<UIState.Ready>(store.state.value)
            val afterConnectImportCalls = connector.loadImportableServersCalls
            assertEquals(true, ready.clients.single { it.id == "codex" }.isConnected)
            assertTrue(afterConnectImportCalls > initialImportCalls)

            ready.intents.disconnectAiClient("codex")
            storeScope.advanceUntilIdle()
            ready = assertIs<UIState.Ready>(store.state.value)
            assertEquals(false, ready.clients.single { it.id == "codex" }.isConnected)
            assertTrue(connector.loadImportableServersCalls > afterConnectImportCalls)

            storeScope.cancel()
        }

    @org.junit.Test
    fun addServerUpdatesDownstreamsWithoutRestart() =
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
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(ServerCapabilities()))
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    aiClientConnectors = emptyList(),
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                )

            store.start()
            storeScope.advanceUntilIdle()

            val readyState = store.state.value as UIState.Ready
            readyState.intents.addServerBasic("s2", "Server 2")
            storeScope.advanceUntilIdle()
            val updated = assertIs<UIState.Ready>(store.state.value)

            assertEquals(1, proxyController.startCalls.size)
            assertEquals(1, proxyController.updateServersCalls.size)
            assertEquals(listOf("s2", "s1"), updated.servers.map { it.id })
            assertEquals("s2", updated.pendingCatalogInstalledServerId)
            assertTrue(updated.pendingCatalogInstalledServerRequestId > 0L)
            assertEquals(
                setOf("s1", "s2"),
                proxyController.updateServersCalls
                    .last()
                    .map { it.id }
                    .toSet(),
            )

            storeScope.cancel()
        }

    @org.junit.Test
    fun refreshServerCapabilitiesForcesFetchWhenProxyNotRunning() =
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
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(ServerCapabilities()))
            val proxyController = FakeProxyController().apply { startResult = Result.failure(IllegalStateException("boom")) }
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val cachedSnapshot = ServerCapsSnapshot(serverId = "s1", name = "Server 1")
            val persistence =
                TestCapabilityCachePersistence(
                    listOf(
                        CapabilityCacheEntry(
                            serverId = "s1",
                            timestampMillis = 0L,
                            snapshot = cachedSnapshot,
                        ),
                    ),
                )
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    aiClientConnectors = emptyList(),
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { 0L },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                    capabilityCachePersistence = persistence,
                )

            store.start()
            storeScope.advanceUntilIdle()

            val readyState = store.state.value as UIState.Ready
            readyState.intents.refreshServerCapabilities("s1")
            storeScope.advanceUntilIdle()

            assertEquals(listOf("s1"), capabilityFetcher.requestedIds)

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
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(ServerCapabilities()))
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    aiClientConnectors = emptyList(),
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
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
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(ServerCapabilities()))
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    aiClientConnectors = emptyList(),
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
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
            assertEquals("alt", updated.activeProxyPresetId)
            assertEquals("alt", repository.config.defaultPresetId)

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
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(ServerCapabilities()))
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    aiClientConnectors = emptyList(),
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                )

            store.start()
            storeScope.advanceUntilIdle()
            assertEquals(1, proxyController.startCalls.size)
            assertEquals(
                "main",
                proxyController.startCalls
                    .last()
                    .preset.id,
            )

            val ready = store.state.value as UIState.Ready
            ready.intents.selectProxyPreset(UiPresetCore.EMPTY_PRESET_ID)
            storeScope.advanceUntilIdle()

            assertEquals(1, proxyController.startCalls.size)
            assertEquals(listOf(Preset.EMPTY_PRESET_ID), proxyController.appliedPresets)

            val updated = store.state.value as UIState.Ready
            assertEquals(UiPresetCore.EMPTY_PRESET_ID, updated.activeProxyPresetId)
            assertEquals(UiPresetCore.EMPTY_PRESET_ID, repository.config.defaultPresetId)
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
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(ServerCapabilities()))
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    aiClientConnectors = emptyList(),
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                )

            store.start()
            storeScope.advanceUntilIdle()

            assertEquals(1, proxyController.startCalls.size)
            assertEquals(
                "main",
                proxyController.startCalls
                    .last()
                    .preset.id,
            )

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
            assertEquals("renamed", updated.activeProxyPresetId)
            assertEquals(listOf("renamed"), updated.presets.map { it.id }.sorted())

            assertEquals(1, proxyController.startCalls.size)
            assertEquals(listOf("renamed"), proxyController.appliedPresets)

            storeScope.cancel()
        }

    @org.junit.Test
    fun reorderServersPersistsServerOrder() =
        runTest {
            val s1 =
                McpServerConfig(
                    id = "s1",
                    name = "Server 1",
                    transport = TransportConfig.StdioTransport(command = "cmd"),
                )
            val s2 =
                McpServerConfig(
                    id = "s2",
                    name = "Server 2",
                    transport = TransportConfig.StdioTransport(command = "cmd"),
                )
            val s3 =
                McpServerConfig(
                    id = "s3",
                    name = "Server 3",
                    transport = TransportConfig.StdioTransport(command = "cmd"),
                )
            val config = McpServersConfig(servers = listOf(s1, s2, s3))
            val repository = FakeConfigurationRepository(config = config, presets = mutableMapOf())
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(ServerCapabilities()))
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    aiClientConnectors = emptyList(),
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                )

            store.start()
            storeScope.advanceUntilIdle()

            val ready = store.state.value as UIState.Ready
            ready.intents.reorderServers(listOf("s3", "s1", "s2"))
            storeScope.advanceUntilIdle()

            val updated = store.state.value as UIState.Ready
            assertEquals(listOf("s3", "s1", "s2"), updated.servers.map { it.id })
            assertEquals(listOf("s3", "s1", "s2"), repository.config.servers.map { it.id })

            storeScope.cancel()
        }

    @org.junit.Test
    fun reorderPresetsUpdatesOrderIndex() =
        runTest {
            val server =
                McpServerConfig(
                    id = "s1",
                    name = "Server 1",
                    transport = TransportConfig.StdioTransport(command = "cmd"),
                )
            val config = McpServersConfig(servers = listOf(server))
            val p1 = Preset(id = "p1", name = "Preset 1", tools = emptyList(), orderIndex = 0)
            val p2 = Preset(id = "p2", name = "Preset 2", tools = emptyList(), orderIndex = 1)
            val p3 = Preset(id = "p3", name = "Preset 3", tools = emptyList(), orderIndex = 2)
            val repository =
                FakeConfigurationRepository(
                    config = config,
                    presets = mutableMapOf("p1" to p1, "p2" to p2, "p3" to p3),
                )
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(ServerCapabilities()))
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    aiClientConnectors = emptyList(),
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                )

            store.start()
            storeScope.advanceUntilIdle()

            val ready = store.state.value as UIState.Ready
            ready.intents.reorderPresets(listOf("p3", "p1", "p2"))
            storeScope.advanceUntilIdle()

            val updated = store.state.value as UIState.Ready
            assertEquals(listOf("p3", "p1", "p2"), updated.presets.map { it.id })

            val persistedOrder =
                repository
                    .listPresets()
                    .sortedBy { it.orderIndex }
                    .map { it.id }
            assertEquals(listOf("p3", "p1", "p2"), persistedOrder)

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
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(ServerCapabilities()))
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
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    aiClientConnectors = emptyList(),
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
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
            val config = McpServersConfig(servers = listOf(server), inboundHttpPort = 3335)
            val preset = Preset("main", "Main", emptyList())
            val repository =
                FakeConfigurationRepository(
                    config = config,
                    presets = mutableMapOf(preset.id to preset),
                )
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(ServerCapabilities()))
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    aiClientConnectors = emptyList(),
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                )

            store.start()
            storeScope.advanceUntilIdle()

            assertEquals(1, proxyController.startCalls.size)

            val readyState = store.state.value as UIState.Ready
            readyState.intents.updateInboundHttpPort(4444)
            storeScope.advanceUntilIdle()

            assertEquals(2, proxyController.startCalls.size)
            val inbound = proxyController.startCalls.last().inbound as TransportConfig.StreamableHttpTransport
            assertTrue(inbound.url.contains(":4444/"))
            assertEquals(4444, repository.config.inboundHttpPort)

            storeScope.cancel()
        }

    @org.junit.Test
    fun runtimeSettingsIntentsPersistAndPropagate() =
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
            val repository = FakeConfigurationRepository(config = config, presets = mutableMapOf())
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(ServerCapabilities()))
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val uiSettingsRepository = FakeUiSettingsRepository(UiSettings(showTrayIcon = true))
            val store =
                AppStore(
                    configurationRepository = repository,
                    uiSettingsRepository = uiSettingsRepository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    aiClientConnectors = emptyList(),
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = NoOpRemoteConnector(defaultRemoteState()),
                )

            store.start()
            storeScope.advanceUntilIdle()

            val ready = assertIs<UIState.Ready>(store.state.value)
            ready.intents.updateCapabilitiesTimeout(12)
            ready.intents.updateConnectionRetryCount(7)
            ready.intents.updateIgnoreHttpsCertificateErrors(true)
            ready.intents.updateFallbackPromptsAndResourcesToTools(true)
            ready.intents.updateTrayIconVisibility(false)
            ready.intents.updateCapabilitiesRefreshInterval(1)
            storeScope.advanceUntilIdle()

            assertEquals(12, repository.config.capabilitiesTimeoutSeconds)
            assertEquals(7, repository.config.connectionRetryCount)
            assertEquals(true, repository.config.ignoreHttpsCertificateErrors)
            assertEquals(true, repository.config.fallbackPromptsAndResourcesToTools)
            assertEquals(30, repository.config.capabilitiesRefreshIntervalSeconds)
            assertEquals(false, uiSettingsRepository.loadUiSettings().showTrayIcon)
            assertEquals(12, proxyController.capabilityTimeoutUpdates.last())
            assertEquals(7, proxyController.connectionRetryUpdates.last())
            assertEquals(true, proxyController.ignoreHttpsCertificateErrorsUpdates.last())
            assertEquals(true, proxyController.fallbackPromptResourceUpdates.last())

            storeScope.cancel()
        }

    @org.junit.Test
    fun updateMcpFilePathRollsBackStateWhenReloadFails() =
        runTest {
            val config =
                McpServersConfig(
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
                    mcpFilePath = "mcp.json",
                )
            val repository = ToggleableConfigurationRepository(config = config, presets = mutableMapOf())
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = { _, _, _, _ -> Result.success(ServerCapabilities()) },
                    logger = logger,
                    aiClientConnectors = emptyList(),
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = NoOpRemoteConnector(defaultRemoteState()),
                )

            store.start()
            storeScope.advanceUntilIdle()
            val initial = assertIs<UIState.Ready>(store.state.value).mcpFilePath
            repository.failLoad = true

            assertIs<UIState.Ready>(store.state.value).intents.updateMcpFilePath("custom.json")
            storeScope.advanceUntilIdle()

            val updatedState = store.state.value
            if (updatedState is UIState.Ready) {
                assertEquals(initial, updatedState.mcpFilePath)
            }
            assertEquals("custom.json", repository.config.mcpFilePath)

            storeScope.cancel()
        }

    @org.junit.Test
    fun toggleProxyServerStopsAndStarts() =
        runTest {
            val config =
                McpServersConfig(
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
            val repository = FakeConfigurationRepository(config = config, presets = mutableMapOf())
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val storeScope = TestScope(testScheduler)
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = { _, _, _, _ -> Result.success(ServerCapabilities()) },
                    logger = CollectingLogger(delegate = noopLogger),
                    aiClientConnectors = emptyList(),
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = NoOpRemoteConnector(defaultRemoteState()),
                )

            store.start()
            storeScope.advanceUntilIdle()

            assertIs<UIState.Ready>(store.state.value).intents.toggleProxyServer()
            storeScope.advanceUntilIdle()
            assertIs<UIState.Ready>(store.state.value).intents.toggleProxyServer()
            storeScope.advanceUntilIdle()

            assertEquals(1, proxyController.stopCalls)
            assertTrue(proxyController.startCalls.size >= 2)

            storeScope.cancel()
        }

    @org.junit.Test
    fun serverIntentsHandleIconsRefreshAndReorderFailure() =
        runTest {
            val config =
                McpServersConfig(
                    servers =
                        listOf(
                            McpServerConfig(
                                id = "s1",
                                name = "Server 1",
                                transport = TransportConfig.StdioTransport(command = "cmd"),
                                env = emptyMap(),
                                enabled = true,
                                iconPath = "icons/original.png",
                            ),
                            McpServerConfig(
                                id = "s2",
                                name = "Server 2",
                                transport = TransportConfig.StdioTransport(command = "cmd"),
                                env = emptyMap(),
                                enabled = true,
                            ),
                            McpServerConfig(
                                id = "s3",
                                name = "Server 3",
                                transport = TransportConfig.StdioTransport(command = "cmd"),
                                env = emptyMap(),
                                enabled = true,
                            ),
                        ),
                )
            val repository = ToggleableConfigurationRepository(config = config, presets = mutableMapOf())
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val storeScope = TestScope(testScheduler)
            val iconRepository = FakeServerIconRepository()
            val store =
                AppStore(
                    configurationRepository = repository,
                    serverIconRepository = iconRepository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = { _, _, _, _ -> Result.success(ServerCapabilities()) },
                    logger = CollectingLogger(delegate = noopLogger),
                    aiClientConnectors = emptyList(),
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = NoOpRemoteConnector(defaultRemoteState()),
                )

            store.start()
            storeScope.advanceUntilIdle()

            val initialOrder = assertIs<UIState.Ready>(store.state.value).servers.map { it.id }
            val intents = assertIs<UIState.Ready>(store.state.value).intents
            intents.pickServerIcon("s1")
            storeScope.advanceUntilIdle()
            assertEquals(
                "icons/picked.png",
                repository.config.servers
                    .first { it.id == "s1" }
                    .iconPath,
            )

            intents.clearServerIcon("s1")
            storeScope.advanceUntilIdle()
            assertEquals(
                null,
                repository.config.servers
                    .first { it.id == "s1" }
                    .iconPath,
            )
            assertTrue("icons/picked.png" in iconRepository.deletedIcons)

            intents.refreshServerCapabilities("s1")
            storeScope.advanceUntilIdle()
            assertEquals(listOf("s1"), proxyController.refreshServerCapabilitiesCalls)

            repository.failSave = true
            intents.reorderServers(listOf("s3", "s1", "s2"))
            storeScope.advanceUntilIdle()
            val afterFailedReorder = store.state.value
            if (afterFailedReorder is UIState.Ready) {
                assertEquals(initialOrder, afterFailedReorder.servers.map { it.id })
            }

            repository.failSave = false
            intents.reorderServers(listOf("s1", "s1", "s2"))
            storeScope.advanceUntilIdle()
            val afterInvalidReorder = store.state.value
            if (afterInvalidReorder is UIState.Ready) {
                assertEquals(initialOrder, afterInvalidReorder.servers.map { it.id })
            }

            storeScope.cancel()
        }

    @org.junit.Test
    fun presetAndRemoteAndAuthorizationIntentsCoverAdditionalPaths() =
        runTest {
            val config =
                McpServersConfig(
                    servers =
                        listOf(
                            McpServerConfig(
                                id = "s1",
                                name = "Server 1",
                                transport = TransportConfig.StreamableHttpTransport(url = "https://api.example.com/mcp"),
                                env = emptyMap(),
                                enabled = true,
                            ),
                        ),
                    defaultPresetId = "base",
                )
            val preset = Preset(id = "base", name = "Base", tools = listOf(ToolReference(serverId = "s1", toolName = "tool")))
            val repository =
                FakeConfigurationRepository(
                    config = config,
                    presets = mutableMapOf(preset.id to preset),
                )
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = TestRemoteConnector(defaultRemoteState())
            val aiConnector = FailingAiClientConnector(id = "failing")
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = { _, _, _, _ -> Result.success(ServerCapabilities()) },
                    logger = CollectingLogger(delegate = noopLogger),
                    aiClientConnectors = listOf(aiConnector),
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                )

            store.start()
            storeScope.advanceUntilIdle()

            val intents = assertIs<UIState.Ready>(store.state.value).intents
            intents.addOrUpdatePreset(UiPreset(id = "extra", name = "Extra"))
            storeScope.advanceUntilIdle()
            assertTrue(repository.listPresets().any { it.id == "extra" })

            intents.removePreset("extra")
            storeScope.advanceUntilIdle()
            assertTrue(repository.listPresets().none { it.id == "extra" })

            intents.startRemoteAuthorization()
            intents.connectRemote()
            intents.disconnectRemote()
            intents.logoutRemote()
            intents.openExternalUrl("javascript:alert(1)")
            intents.connectAiClient("failing")
            intents.disconnectAiClient("failing")
            intents.openAiClientInfo("failing")
            storeScope.advanceUntilIdle()
            assertEquals(1, remoteConnector.beginAuthorizationCalls)
            assertEquals(1, remoteConnector.connectCalls)
            assertTrue(remoteConnector.disconnectCalls >= 1)
            assertEquals(1, remoteConnector.logoutCalls)
            assertEquals(1, aiConnector.connectCalls)
            assertEquals(1, aiConnector.disconnectCalls)

            AuthorizationPresenterRegistry
                .current()
                ?.onAuthorizationRequest(
                    AuthorizationRequest(
                        resourceUrl = "https://api.example.com/mcp",
                        authorizationUrl = "https://auth.example.com/authorize",
                        redirectUri = "://bad",
                    ),
                )
            storeScope.advanceUntilIdle()
            intents.openAuthorizationInBrowser("s1", "not a valid url")
            intents.dismissAuthorizationPopup("s1")
            storeScope.advanceUntilIdle()

            AuthorizationPresenterRegistry
                .current()
                ?.onAuthorizationRequest(
                    AuthorizationRequest(
                        resourceUrl = "https://api.example.com/mcp",
                        authorizationUrl = "https://auth.example.com/authorize",
                        redirectUri = "://bad",
                    ),
                )
            storeScope.advanceUntilIdle()
            intents.cancelAuthorization("s1")
            storeScope.advanceUntilIdle()
            assertEquals(
                false,
                repository.config.servers
                    .first()
                    .enabled,
            )

            store.stop()
            storeScope.cancel()
        }

    @org.junit.Test
    fun authorizationPopup_queue_progresses_sequentially_and_cancel_disables_only_target_server() =
        runTest {
            val config =
                McpServersConfig(
                    servers =
                        listOf(
                            McpServerConfig(
                                id = "s1",
                                name = "Server 1",
                                transport = TransportConfig.StreamableHttpTransport(url = "https://api.example.com/mcp"),
                                env = emptyMap(),
                                enabled = true,
                            ),
                            McpServerConfig(
                                id = "s2",
                                name = "Server 2",
                                transport = TransportConfig.StreamableHttpTransport(url = "https://api2.example.com/mcp"),
                                env = emptyMap(),
                                enabled = true,
                            ),
                        ),
                    defaultPresetId = "base",
                )
            val preset = Preset(id = "base", name = "Base", tools = emptyList())
            val repository =
                FakeConfigurationRepository(
                    config = config,
                    presets = mutableMapOf(preset.id to preset),
                )
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val storeScope = TestScope(testScheduler)
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = { _, _, _, _ -> Result.success(ServerCapabilities()) },
                    logger = CollectingLogger(delegate = noopLogger),
                    aiClientConnectors = emptyList(),
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = NoOpRemoteConnector(defaultRemoteState()),
                )

            store.start()
            storeScope.advanceUntilIdle()

            val intents = assertIs<UIState.Ready>(store.state.value).intents
            AuthorizationPresenterRegistry
                .current()
                ?.onAuthorizationRequest(
                    AuthorizationRequest(
                        resourceUrl = "https://api.example.com/mcp",
                        authorizationUrl = "https://auth.example.com/authorize/s1",
                        redirectUri = "://bad",
                    ),
                )
            AuthorizationPresenterRegistry
                .current()
                ?.onAuthorizationRequest(
                    AuthorizationRequest(
                        resourceUrl = "https://api2.example.com/mcp",
                        authorizationUrl = "https://auth.example.com/authorize/s2",
                        redirectUri = "://bad",
                    ),
                )
            storeScope.advanceUntilIdle()

            var ready = assertIs<UIState.Ready>(store.state.value)
            assertEquals("s1", ready.authorizationPopup?.serverId)
            assertEquals(UiAuthorizationPopupStatus.AwaitingBrowserPermission, ready.authorizationPopup?.status)

            intents.openAuthorizationInBrowser("s1", "not a valid url")
            storeScope.advanceUntilIdle()
            ready = assertIs<UIState.Ready>(store.state.value)
            assertEquals(UiAuthorizationPopupStatus.AwaitingBrowserPermission, ready.authorizationPopup?.status)

            intents.dismissAuthorizationPopup("s1")
            storeScope.advanceUntilIdle()
            ready = assertIs<UIState.Ready>(store.state.value)
            assertEquals("s2", ready.authorizationPopup?.serverId)
            assertEquals(UiAuthorizationPopupStatus.AwaitingBrowserPermission, ready.authorizationPopup?.status)

            intents.cancelAuthorization("s2")
            storeScope.advanceUntilIdle()

            assertEquals(
                true,
                repository.config.servers
                    .first { it.id == "s1" }
                    .enabled,
            )
            assertEquals(
                false,
                repository.config.servers
                    .first { it.id == "s2" }
                    .enabled,
            )

            store.stop()
            storeScope.cancel()
        }

    @org.junit.Test
    fun stopCancelsBackgroundRefresh() =
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
                    capabilitiesRefreshIntervalSeconds = 30,
                )
            val preset = Preset("main", "Main", emptyList())
            val repository =
                FakeConfigurationRepository(
                    config = config,
                    presets = mutableMapOf(preset.id to preset),
                )
            val capabilityFetcher = RecordingCapabilityFetcher(Result.success(ServerCapabilities()))
            val proxyController = FakeProxyController()
            proxyController.startResult = Result.failure(IllegalStateException("boom"))
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope()
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            val store =
                AppStore(
                    configurationRepository = repository,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = capabilityFetcher::invoke,
                    logger = logger,
                    aiClientConnectors = emptyList(),
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { storeScope.testScheduler.currentTime },
                    enableBackgroundRefresh = true,
                    remoteConnector = remoteConnector,
                )

            try {
                store.start()
                storeScope.runCurrent()

                val initialRequests = capabilityFetcher.requestedIds.size
                assertEquals(1, initialRequests)

                store.stop()
                storeScope.advanceTimeBy(35_000)
                storeScope.runCurrent()

                assertEquals(initialRequests, capabilityFetcher.requestedIds.size)
            } finally {
                storeScope.cancel()
            }
        }

    @org.junit.Test
    fun pickAgentWorkspaceDirectory_delegatesToSystemPicker_andLogsFailure() =
        runTest {
            val repository = FakeConfigurationRepository(McpServersConfig(), mutableMapOf())
            val proxyController = FakeProxyController()
            val proxyLifecycle = ProxyLifecycle(proxyController, noopLogger)
            val logger = CollectingLogger(delegate = noopLogger)
            val storeScope = TestScope(testScheduler)
            val remoteConnector = NoOpRemoteConnector(defaultRemoteState())
            var recordedInitialPath: String? = null
            val picker =
                object : SystemPicker {
                    override fun pickDirectory(initialPath: String?): Result<String?> {
                        recordedInitialPath = initialPath
                        return Result.failure(IllegalStateException("picker_failed"))
                    }

                    override fun pickFile(request: FilePickRequest): Result<String?> = Result.success(null)
                }
            val store =
                AppStore(
                    configurationRepository = repository,
                    systemPicker = picker,
                    proxyRuntime = proxyLifecycle,
                    capabilityFetcher = { _, _, _, _ -> Result.success(ServerCapabilities()) },
                    logger = logger,
                    aiClientConnectors = emptyList(),
                    scope = storeScope,
                    ioDispatcher = ioDispatcher(storeScope),
                    now = { testScheduler.currentTime },
                    enableBackgroundRefresh = false,
                    remoteConnector = remoteConnector,
                )

            val result = store.pickAgentWorkspaceDirectory("/tmp/workspace")
            storeScope.advanceUntilIdle()

            assertTrue(result.isFailure)
            assertEquals("/tmp/workspace", recordedInitialPath)
            val errorState = assertIs<UIState.Error>(store.state.value)
            assertEquals("picker_failed", errorState.message)
            assertTrue(
                logger.events.replayCache.any {
                    it.message.contains("[AppStore] pickAgentWorkspaceDirectory(initialPath=/tmp/workspace) failed: picker_failed")
                },
            )

            storeScope.cancel()
        }

    private fun importServer(
        sourceServerId: String,
        name: String,
    ): AiClientImportServer =
        AiClientImportServer(
            sourceServerId = sourceServerId,
            name = name,
            enabled = true,
            transport = UiStreamableHttpTransport(url = "http://localhost:9000/mcp"),
            env = emptyMap(),
        )

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

    private class FakeUiSettingsRepository(
        private var settings: UiSettings = UiSettings(),
    ) : UiSettingsRepository {
        override fun loadUiSettings(): UiSettings = settings

        override fun saveUiSettings(settings: UiSettings) {
            this.settings = settings
        }
    }

    private class FakeCatalogRepository(
        private val bundle: CatalogBundle,
    ) : CatalogRepository {
        override suspend fun loadCatalog(): Result<CatalogBundle> = Result.success(bundle)

        override suspend fun refreshCatalog(): Result<CatalogBundle?> = Result.success(null)
    }

    private class ToggleableConfigurationRepository(
        var config: McpServersConfig,
        private val presets: MutableMap<String, Preset>,
    ) : ConfigurationRepository {
        var failLoad: Boolean = false
        var failSave: Boolean = false

        override fun loadMcpConfig(): McpServersConfig {
            if (failLoad) {
                throw IllegalStateException("boom")
            }
            return config
        }

        override fun saveMcpConfig(config: McpServersConfig) {
            if (failSave) {
                throw IllegalStateException("save boom")
            }
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

    private class StatefulAiClientConnector(
        id: String,
        name: String,
        iconId: String,
        private val importableServers: List<AiClientImportServer>,
    ) : AiClientConnector {
        override val descriptor: AiClientDescriptor =
            AiClientDescriptor(
                id = id,
                name = name,
                description = "",
                iconId = iconId,
                infoUrl = "https://example.com",
            )
        private var isConnected: Boolean = false
        var loadImportableServersCalls: Int = 0
            private set

        override suspend fun loadStatus(request: AiClientConnectionRequest): Result<AiClientStatus> =
            Result.success(
                AiClientStatus(
                    isConnected = isConnected,
                    canConnect = true,
                ),
            )

        override suspend fun loadImportableServers(): Result<List<AiClientImportServer>> {
            loadImportableServersCalls += 1
            return Result.success(importableServers)
        }

        override suspend fun connect(request: AiClientConnectionRequest): Result<Unit> {
            isConnected = true
            return Result.success(Unit)
        }

        override suspend fun disconnect(request: AiClientConnectionRequest): Result<Unit> {
            isConnected = false
            return Result.success(Unit)
        }
    }

    private class FakeAiClientConnector(
        id: String,
        name: String,
        iconId: String,
        private val importableServers: List<AiClientImportServer>,
    ) : AiClientConnector {
        override val descriptor: AiClientDescriptor =
            AiClientDescriptor(
                id = id,
                name = name,
                description = "",
                iconId = iconId,
                infoUrl = "https://example.com",
            )

        override suspend fun loadStatus(request: AiClientConnectionRequest): Result<AiClientStatus> =
            Result.success(
                AiClientStatus(
                    isConnected = false,
                    canConnect = true,
                ),
            )

        override suspend fun loadImportableServers(): Result<List<AiClientImportServer>> = Result.success(importableServers)

        override suspend fun connect(request: AiClientConnectionRequest): Result<Unit> = Result.success(Unit)

        override suspend fun disconnect(request: AiClientConnectionRequest): Result<Unit> = Result.success(Unit)
    }

    private class FakeImportedServerHideRepository(
        val hiddenKeys: MutableSet<String> = mutableSetOf(),
    ) : ImportedServerHideRepository {
        override fun loadHiddenServerKeys(): Set<String> = hiddenKeys.toSet()

        override fun hideServer(key: String) {
            hiddenKeys += key
        }

        override fun clearHiddenServers() {
            hiddenKeys.clear()
        }
    }

    private class FakeImportedServerInstallRepository(
        val installedMappings: MutableMap<String, String> = mutableMapOf(),
    ) : ImportedServerInstallRepository {
        override fun loadInstalledMappings(): Map<String, String> = installedMappings.toMap()

        override fun saveInstalledMapping(
            importKey: String,
            serverId: String,
        ) {
            installedMappings[importKey] = serverId
        }
    }

    private class FakeServerIconRepository : ServerIconRepository {
        var pickResult: Result<String?> = Result.success("icons/picked.png")
        val deletedIcons = mutableListOf<String>()

        override suspend fun pickAndImportIcon(): Result<String?> = pickResult

        override suspend fun deleteIcon(iconPath: String): Result<Unit> {
            deletedIcons += iconPath
            return Result.success(Unit)
        }
    }

    private class FailingAiClientConnector(
        id: String,
    ) : AiClientConnector {
        override val descriptor: AiClientDescriptor =
            AiClientDescriptor(
                id = id,
                name = "Failing",
                description = "",
                iconId = "failing",
                infoUrl = "not a valid url",
            )
        var connectCalls: Int = 0
        var disconnectCalls: Int = 0

        override suspend fun loadStatus(request: AiClientConnectionRequest): Result<AiClientStatus> =
            Result.success(
                AiClientStatus(
                    isConnected = false,
                    canConnect = true,
                ),
            )

        override suspend fun loadImportableServers(): Result<List<AiClientImportServer>> = Result.success(emptyList())

        override suspend fun connect(request: AiClientConnectionRequest): Result<Unit> {
            connectCalls += 1
            return Result.failure(IllegalStateException("connect failed"))
        }

        override suspend fun disconnect(request: AiClientConnectionRequest): Result<Unit> {
            disconnectCalls += 1
            return Result.failure(IllegalStateException("disconnect failed"))
        }
    }

    private class FakeProxyController : ProxyController {
        private val _logs = MutableSharedFlow<LogEvent>(extraBufferCapacity = 16)
        override val logs = _logs
        private val _capabilityUpdates = MutableSharedFlow<Map<String, ServerCapabilities>>(replay = 1)
        override val capabilityUpdates = _capabilityUpdates
        private val statusUpdates = MutableSharedFlow<ServerConnectionUpdate>(extraBufferCapacity = 8)
        override val serverStatusUpdates = statusUpdates

        data class StartParams(
            val servers: List<McpServerConfig>,
            val preset: Preset,
            val inbound: TransportConfig,
            val callTimeoutSeconds: Int,
            val capabilitiesTimeoutSeconds: Int,
            val authorizationTimeoutSeconds: Int,
            val connectionRetryCount: Int,
            val ignoreHttpsCertificateErrors: Boolean,
            val capabilitiesRefreshIntervalSeconds: Int,
            val fallbackPromptsAndResourcesToTools: Boolean,
            val adapterMode: Boolean,
            val logsSubscriptionActive: Boolean,
        )

        var startResult: Result<Unit> = Result.success(Unit)
        var stopResult: Result<Unit> = Result.success(Unit)
        var updateServersResult: Result<Unit> = Result.success(Unit)
        var refreshServerCapabilitiesResult: Result<Unit> = Result.success(Unit)
        var refreshFilteredCapabilitiesResult: Result<Unit> = Result.success(Unit)
        val startCalls = mutableListOf<StartParams>()
        var stopCalls: Int = 0
        val callTimeoutUpdates = mutableListOf<Int>()
        val capabilityTimeoutUpdates = mutableListOf<Int>()
        val connectionRetryUpdates = mutableListOf<Int>()
        val ignoreHttpsCertificateErrorsUpdates = mutableListOf<Boolean>()
        val fallbackPromptResourceUpdates = mutableListOf<Boolean>()
        val adapterModeUpdates = mutableListOf<Boolean>()
        val appliedPresets = mutableListOf<String>()
        val updateServersCalls = mutableListOf<List<McpServerConfig>>()
        val refreshServerCapabilitiesCalls = mutableListOf<String>()
        var refreshFilteredCapabilitiesCalls: Int = 0

        override fun start(
            servers: List<McpServerConfig>,
            preset: Preset,
            inbound: TransportConfig,
            callTimeoutSeconds: Int,
            capabilitiesTimeoutSeconds: Int,
            authorizationTimeoutSeconds: Int,
            connectionRetryCount: Int,
            ignoreHttpsCertificateErrors: Boolean,
            capabilitiesRefreshIntervalSeconds: Int,
            fallbackPromptsAndResourcesToTools: Boolean,
            adapterMode: Boolean,
        ): Result<Unit> {
            startCalls +=
                StartParams(
                    servers = servers,
                    preset = preset,
                    inbound = inbound,
                    callTimeoutSeconds = callTimeoutSeconds,
                    capabilitiesTimeoutSeconds = capabilitiesTimeoutSeconds,
                    authorizationTimeoutSeconds = authorizationTimeoutSeconds,
                    connectionRetryCount = connectionRetryCount,
                    ignoreHttpsCertificateErrors = ignoreHttpsCertificateErrors,
                    capabilitiesRefreshIntervalSeconds = capabilitiesRefreshIntervalSeconds,
                    fallbackPromptsAndResourcesToTools = fallbackPromptsAndResourcesToTools,
                    adapterMode = adapterMode,
                    logsSubscriptionActive = true,
                )
            return startResult
        }

        override fun stop(): Result<Unit> {
            stopCalls += 1
            return stopResult
        }

        override fun applyPreset(preset: Preset): Result<Unit> {
            appliedPresets += preset.id
            return Result.success(Unit)
        }

        override fun updateServers(
            servers: List<McpServerConfig>,
            callTimeoutSeconds: Int,
            capabilitiesTimeoutSeconds: Int,
            authorizationTimeoutSeconds: Int,
            connectionRetryCount: Int,
            ignoreHttpsCertificateErrors: Boolean,
            capabilitiesRefreshIntervalSeconds: Int,
            fallbackPromptsAndResourcesToTools: Boolean,
            adapterMode: Boolean,
        ): Result<Unit> {
            updateServersCalls += servers
            return updateServersResult
        }

        override fun updateCallTimeout(seconds: Int) {
            callTimeoutUpdates += seconds
        }

        override fun updateCapabilitiesTimeout(seconds: Int) {
            capabilityTimeoutUpdates += seconds
        }

        override fun updateConnectionRetryCount(count: Int) {
            connectionRetryUpdates += count
        }

        override fun updateIgnoreHttpsCertificateErrors(enabled: Boolean) {
            ignoreHttpsCertificateErrorsUpdates += enabled
        }

        override fun updateFallbackPromptsAndResourcesToTools(enabled: Boolean) {
            fallbackPromptResourceUpdates += enabled
        }

        override fun updateAdapterMode(enabled: Boolean) {
            adapterModeUpdates += enabled
        }

        override fun registerPresetManagementBackend(backend: PresetManagementBackend) {}

        override fun clearPresetManagementBackend() {}

        override fun refreshServerCapabilities(serverId: String): Result<Unit> {
            refreshServerCapabilitiesCalls += serverId
            return refreshServerCapabilitiesResult
        }

        override fun refreshFilteredCapabilities(): Result<Unit> {
            refreshFilteredCapabilitiesCalls += 1
            return refreshFilteredCapabilitiesResult
        }

        fun emitCapabilities(capabilitiesById: Map<String, ServerCapabilities>) {
            _capabilityUpdates.tryEmit(capabilitiesById)
        }
    }

    private class RecordingCapabilityFetcher(
        private val result: Result<ServerCapabilities>,
    ) {
        val requestedIds = mutableListOf<String>()
        val requestedTimeouts = mutableListOf<Int>()
        val requestedRetries = mutableListOf<Int>()

        suspend fun invoke(
            config: McpServerConfig,
            timeoutSeconds: Int,
            connectionRetryCount: Int,
            authorizationStatusListener: io.qent.broxy.core.mcp.auth.AuthorizationStatusListener?,
        ): Result<ServerCapabilities> {
            requestedIds += config.id
            requestedTimeouts += timeoutSeconds
            requestedRetries += connectionRetryCount
            return result
        }
    }

    private class TestCapabilityCachePersistence(
        private val entries: List<CapabilityCacheEntry>,
    ) : CapabilityCachePersistence {
        override fun loadAll(): List<CapabilityCacheEntry> = entries

        override fun save(entry: CapabilityCacheEntry) {
        }

        override fun remove(serverId: String) {
        }

        override fun retain(validIds: Set<String>) {
        }
    }

    private class TestRemoteConnector(
        initial: UiRemoteConnectionState,
    ) : RemoteConnector {
        private val _state = MutableStateFlow(initial)
        override val state: StateFlow<UiRemoteConnectionState> = _state
        override val isEnabled: Boolean = true
        var lastPresetId: String? = null
        var lastChangeType: String? = null
        var startCalls: Int = 0
        var beginAuthorizationCalls: Int = 0
        var connectCalls: Int = 0
        var disconnectCalls: Int = 0
        var logoutCalls: Int = 0
        val proxyRunningUpdates = mutableListOf<Boolean>()

        fun emit(state: UiRemoteConnectionState) {
            _state.value = state
        }

        override fun start() {
            startCalls += 1
        }

        override fun beginAuthorization() {
            beginAuthorizationCalls += 1
        }

        override fun connect() {
            connectCalls += 1
        }

        override fun disconnect() {
            disconnectCalls += 1
        }

        override fun logout() {
            logoutCalls += 1
        }

        override fun onProxyRunningChanged(running: Boolean) {
            proxyRunningUpdates += running
        }

        override fun notifyPresetChanged(
            presetId: String?,
            changeType: String,
        ) {
            lastPresetId = presetId
            lastChangeType = changeType
        }
    }
}
