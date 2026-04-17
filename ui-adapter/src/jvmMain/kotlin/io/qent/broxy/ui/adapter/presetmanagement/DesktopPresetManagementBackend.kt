package io.qent.broxy.ui.adapter.presetmanagement

import io.qent.broxy.core.capabilities.PersistedCapabilityCacheStore
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.models.AuthConfig
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.presetmanagement.CatalogServerInstallState
import io.qent.broxy.core.presetmanagement.CatalogServerInstallStatusResponse
import io.qent.broxy.core.presetmanagement.CreatePresetRequest
import io.qent.broxy.core.presetmanagement.CreatePresetResponse
import io.qent.broxy.core.presetmanagement.GetCatalogServerInstallStatusRequest
import io.qent.broxy.core.presetmanagement.InstallCatalogServerRequest
import io.qent.broxy.core.presetmanagement.InstallCatalogServerResponse
import io.qent.broxy.core.presetmanagement.JvmPresetManagementBackend
import io.qent.broxy.core.presetmanagement.ListCatalogServerNamesResponse
import io.qent.broxy.core.presetmanagement.ListPresetNamesResponse
import io.qent.broxy.core.presetmanagement.ListServerNamesResponse
import io.qent.broxy.core.presetmanagement.NamedPresetManagementItem
import io.qent.broxy.core.presetmanagement.PresetCreationAlgorithmResponse
import io.qent.broxy.core.presetmanagement.PresetDescriptionRequest
import io.qent.broxy.core.presetmanagement.PresetDescriptionResponse
import io.qent.broxy.core.presetmanagement.PresetManagementBackend
import io.qent.broxy.core.presetmanagement.PresetManagementException
import io.qent.broxy.core.presetmanagement.ServerDescriptionRequest
import io.qent.broxy.core.presetmanagement.ServerDescriptionResponse
import io.qent.broxy.core.presetmanagement.SetServerEnabledRequest
import io.qent.broxy.core.presetmanagement.SetServerEnabledResponse
import io.qent.broxy.core.proxy.runtime.ProxyRuntimeFacade
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.Logger
import io.qent.broxy.registry.catalog.CatalogInstallSession
import io.qent.broxy.registry.catalog.CatalogServerDetail
import io.qent.broxy.registry.catalog.RegistryHttpDraft
import io.qent.broxy.registry.catalog.RegistryOAuthDraft
import io.qent.broxy.registry.catalog.RegistryServerDraft
import io.qent.broxy.registry.catalog.RegistryStdioDraft
import io.qent.broxy.registry.catalog.RegistryStreamableHttpDraft
import io.qent.broxy.registry.catalog.RegistryTransportDraft
import io.qent.broxy.registry.data.CatalogRepository
import io.qent.broxy.ui.adapter.models.UiCatalogInstallPermissionRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.qent.broxy.registry.catalog.CatalogInstallPlanner as RegistryCatalogInstallPlanner

private data class CatalogInstallStatusEntry(
    val message: String? = null,
)

internal class DesktopPresetManagementBackend(
    private val configurationRepository: ConfigurationRepository,
    private val liveCapabilitiesProvider: () -> Map<String, ServerCapabilities>,
    private val capabilityCacheStore: PersistedCapabilityCacheStore,
    logger: Logger,
    configuredServersProvider: () -> List<McpServerConfig>,
    savedPresetNamesProvider: () -> List<NamedPresetManagementItem>,
    private val refreshPresetListAfterCreate: suspend () -> Unit,
    private val catalogRepository: CatalogRepository,
    private val proxyRuntime: ProxyRuntimeFacade,
    private val coroutineScope: CoroutineScope,
    private val requestInstallPermission: suspend (UiCatalogInstallPermissionRequest) -> Boolean,
    private val refreshUiAfterServerMutation: suspend () -> Unit,
    private val agenticModeEnabledProvider: () -> Boolean,
) : PresetManagementBackend {
    private val installLock = Mutex()
    private val installStatuses: MutableMap<String, CatalogInstallStatusEntry> = mutableMapOf()
    private val installJobs: MutableMap<String, Job> = mutableMapOf()
    private val delegate =
        JvmPresetManagementBackend(
            configurationRepository = configurationRepository,
            liveCapabilitiesProvider = liveCapabilitiesProvider,
            capabilityCacheStore = capabilityCacheStore,
            logger = logger,
            configuredServersProvider = configuredServersProvider,
            savedPresetNamesProvider = savedPresetNamesProvider,
            agenticModeProvider = agenticModeEnabledProvider,
        )

    override val agenticModeEnabled: Boolean
        get() = agenticModeEnabledProvider()

    override suspend fun getPresetCreationAlgorithm(): PresetCreationAlgorithmResponse = delegate.getPresetCreationAlgorithm()

    override suspend fun listServerNames(): ListServerNamesResponse = delegate.listServerNames()

    override suspend fun getServerDescription(request: ServerDescriptionRequest): ServerDescriptionResponse =
        delegate.getServerDescription(request)

    override suspend fun listPresetNames(): ListPresetNamesResponse = delegate.listPresetNames()

    override suspend fun getPresetDescription(request: PresetDescriptionRequest): PresetDescriptionResponse =
        delegate.getPresetDescription(request)

    override suspend fun createPreset(request: CreatePresetRequest): CreatePresetResponse {
        val created = delegate.createPreset(request)
        refreshPresetListAfterCreate()
        return created
    }

    override suspend fun listCatalogServerNames(): ListCatalogServerNamesResponse {
        ensureAgenticMode()
        val catalog = loadCatalogOrThrow()
        return ListCatalogServerNamesResponse(
            servers =
                catalog.servers
                    .sortedBy { it.displayName().lowercase() }
                    .map { detail ->
                        NamedPresetManagementItem(
                            id = detail.name,
                            name = detail.displayName(),
                        )
                    },
        )
    }

    override suspend fun installCatalogServer(request: InstallCatalogServerRequest): InstallCatalogServerResponse {
        ensureAgenticMode()
        val serverId = request.serverId.trim()
        if (serverId.isEmpty()) {
            throw PresetManagementException("install_catalog_server requires non-empty server_id")
        }
        val detail = resolveCatalogServerDetail(serverId)
        val existingStatus = resolveInstallStatusInternal(serverId)
        if (existingStatus.state == CatalogServerInstallState.Installed) {
            return InstallCatalogServerResponse(
                serverId = serverId,
                state = CatalogServerInstallState.Installed,
                message = "Server '$serverId' is already installed.",
            )
        }
        if (existingStatus.state == CatalogServerInstallState.Installing) {
            if (isInstallJobActive(serverId)) {
                return InstallCatalogServerResponse(
                    serverId = serverId,
                    state = CatalogServerInstallState.Installing,
                    message = existingStatus.message ?: "Installation already in progress.",
                )
            }
            if (isServerInstalled(serverId)) {
                return InstallCatalogServerResponse(
                    serverId = serverId,
                    state = CatalogServerInstallState.Installing,
                    message = "Server '$serverId' is already configured and waiting for capabilities.",
                )
            }
        }
        val session = resolveOneClickInstallSession(detail)
        val approved =
            requestInstallPermission(
                UiCatalogInstallPermissionRequest(
                    serverId = serverId,
                    serverName = detail.displayName(),
                    serverDescription = detail.description,
                    iconUrl = detail.iconUrl(),
                ),
            )
        if (!approved) {
            throw PresetManagementException("Installation of '$serverId' was denied by user")
        }
        val installJob =
            coroutineScope.launch {
                runInstall(serverId = serverId, session = session)
            }
        installLock.withLock {
            installStatuses[serverId] =
                CatalogInstallStatusEntry(
                    message = "Installation started.",
                )
            installJobs[serverId] = installJob
        }
        return InstallCatalogServerResponse(
            serverId = serverId,
            state = CatalogServerInstallState.Installing,
            message = "Installation started.",
        )
    }

    override suspend fun getCatalogServerInstallStatus(request: GetCatalogServerInstallStatusRequest): CatalogServerInstallStatusResponse {
        ensureAgenticMode()
        val serverId = request.serverId.trim()
        if (serverId.isEmpty()) {
            throw PresetManagementException("get_catalog_server_install_status requires non-empty server_id")
        }
        resolveCatalogServerDetail(serverId)
        return resolveInstallStatusInternal(serverId)
    }

    override suspend fun setServerEnabled(request: SetServerEnabledRequest): SetServerEnabledResponse {
        ensureAgenticMode()
        val serverId = request.serverId.trim()
        if (serverId.isEmpty()) {
            throw PresetManagementException("set_server_enabled requires non-empty server_id")
        }
        val config = loadConfigOrThrow()
        val target =
            config.servers.firstOrNull { it.id == serverId }
                ?: throw PresetManagementException("set_server_enabled could not find installed server '$serverId'")
        val savedConfig = saveConfig(toggleServerEnabled(config, serverId, request.enabled))
        if (proxyRuntime.isRunning) {
            proxyRuntime
                .updateServers(savedConfig)
                .getOrElse { error ->
                    throw PresetManagementException(error.message ?: "Failed to apply server update to runtime")
                }
            if (request.enabled) {
                proxyRuntime.refreshServerCapabilities(serverId)
            }
        }
        refreshUiAfterServerMutation()
        val updatedEnabled = savedConfig.servers.firstOrNull { it.id == serverId }?.enabled ?: target.enabled
        return SetServerEnabledResponse(
            serverId = serverId,
            enabled = updatedEnabled,
        )
    }

    private suspend fun runInstall(
        serverId: String,
        session: CatalogInstallSession,
    ) {
        try {
            val fieldValues = RegistryCatalogInstallPlanner.buildInitialFieldValues(session)
            val installResult =
                RegistryCatalogInstallPlanner.buildInstallResult(
                    session = session,
                    displayName = "",
                    fieldValues = fieldValues,
                )
            if (installResult.isFailure) {
                updateInstallStatus(
                    serverId = serverId,
                    message =
                        installResult.exceptionOrNull()?.message?.let { "Installation failed: $it" }
                            ?: "Installation failed: failed to build install configuration.",
                )
                return
            }
            val serverDraft = installResult.getOrThrow().draft
            val coreServer = serverDraft.toCoreServerConfig()
            val savedConfig = saveConfig(upsertServer(loadConfigOrThrow(), coreServer))
            if (proxyRuntime.isRunning) {
                proxyRuntime
                    .updateServers(savedConfig)
                    .getOrElse { error ->
                        throw PresetManagementException(error.message ?: "Failed to apply installed server to runtime")
                    }
                proxyRuntime.refreshServerCapabilities(coreServer.id)
            }
            refreshUiAfterServerMutation()
            val ready = hasCapabilities(coreServer.id)
            updateInstallStatus(
                serverId = serverId,
                message =
                    if (ready) {
                        "Server installed."
                    } else {
                        "Server installed. Waiting for capabilities."
                    },
            )
        } catch (error: Throwable) {
            updateInstallStatus(
                serverId = serverId,
                message = error.message?.let { "Installation failed: $it" } ?: "Installation failed",
            )
        } finally {
            installLock.withLock { installJobs.remove(serverId) }
        }
    }

    private suspend fun resolveInstallStatusInternal(serverId: String): CatalogServerInstallStatusResponse {
        val installed = isServerInstalled(serverId)
        val ready = installed && hasCapabilities(serverId)
        val activeInstallJob = installLock.withLock { installJobs[serverId] }
        val statusEntry = installLock.withLock { installStatuses[serverId] }
        val state =
            when {
                !installed -> CatalogServerInstallState.NotInstalled
                ready -> CatalogServerInstallState.Installed
                installed -> CatalogServerInstallState.Installing
                else -> CatalogServerInstallState.NotInstalled
            }
        val message =
            when (state) {
                CatalogServerInstallState.NotInstalled ->
                    statusEntry?.message ?: "Server is not installed."
                CatalogServerInstallState.Installing ->
                    if (activeInstallJob?.isActive == true) {
                        statusEntry?.message ?: "Installation in progress."
                    } else {
                        statusEntry?.message ?: "Server is configured and waiting for capabilities."
                    }
                CatalogServerInstallState.Installed ->
                    statusEntry?.message ?: "Server is installed."
            }
        return CatalogServerInstallStatusResponse(
            serverId = serverId,
            state = state,
            installed = installed,
            ready = ready,
            message = message,
        )
    }

    private suspend fun updateInstallStatus(
        serverId: String,
        message: String?,
    ) {
        installLock.withLock {
            installStatuses[serverId] =
                CatalogInstallStatusEntry(
                    message = message,
                )
        }
    }

    private suspend fun isInstallJobActive(serverId: String): Boolean = installLock.withLock { installJobs[serverId]?.isActive == true }

    private suspend fun isServerInstalled(serverId: String): Boolean =
        runCatching { loadConfigOrThrow().servers.any { it.id == serverId } }.getOrDefault(false)

    private suspend fun hasCapabilities(serverId: String): Boolean {
        if (liveCapabilitiesProvider()[serverId] != null) return true
        return capabilityCacheStore.loadAll().any { it.serverId == serverId }
    }

    private suspend fun resolveCatalogServerDetail(serverId: String): CatalogServerDetail =
        loadCatalogOrThrow()
            .servers
            .firstOrNull { it.name == serverId }
            ?: throw PresetManagementException("Catalog server '$serverId' was not found")

    private suspend fun resolveOneClickInstallSession(detail: CatalogServerDetail): CatalogInstallSession {
        val session =
            RegistryCatalogInstallPlanner
                .buildInstallSession(detail)
                .getOrElse { error ->
                    throw PresetManagementException(error.message ?: "Failed to resolve catalog install profile")
                }
        val initialFieldValues = RegistryCatalogInstallPlanner.buildInitialFieldValues(session)
        val requiresForm = RegistryCatalogInstallPlanner.requiresInstallForm(session, initialFieldValues)
        val isOneClick = !requiresForm && session.installSteps.isEmpty()
        if (!isOneClick) {
            throw PresetManagementException(
                "Catalog server '${detail.name}' requires manual install form and cannot be installed via install_catalog_server",
            )
        }
        return session
    }

    private suspend fun loadCatalogOrThrow() =
        catalogRepository
            .loadCatalog()
            .getOrElse { error ->
                throw PresetManagementException(error.message ?: "Failed to load catalog")
            }

    private suspend fun loadConfigOrThrow(): McpServersConfig =
        runCatching { configurationRepository.loadMcpConfig() }
            .getOrElse { error ->
                throw PresetManagementException(error.message ?: "Failed to load configuration")
            }

    private fun toggleServerEnabled(
        config: McpServersConfig,
        serverId: String,
        enabled: Boolean,
    ): McpServersConfig =
        config.copy(
            servers =
                config.servers.map { server ->
                    if (server.id == serverId) {
                        server.copy(enabled = enabled)
                    } else {
                        server
                    }
                },
        )

    private fun upsertServer(
        config: McpServersConfig,
        server: McpServerConfig,
    ): McpServersConfig {
        val updatedServers = config.servers.toMutableList()
        val existingIndex = updatedServers.indexOfFirst { it.id == server.id }
        if (existingIndex >= 0) {
            updatedServers[existingIndex] = server
        } else {
            updatedServers.add(0, server)
        }
        return config.copy(servers = updatedServers)
    }

    private suspend fun saveConfig(config: McpServersConfig): McpServersConfig =
        runCatching {
            configurationRepository.saveMcpConfig(config)
            config
        }.getOrElse { error ->
            throw PresetManagementException(error.message ?: "Failed to save configuration")
        }

    private fun ensureAgenticMode() {
        if (!agenticModeEnabled) {
            throw PresetManagementException("Agentic mode is disabled")
        }
    }

    private fun RegistryServerDraft.toCoreServerConfig(): McpServerConfig =
        McpServerConfig(
            id = id,
            name = name,
            transport = transport.toCoreTransport(),
            env = env,
            enabled = enabled,
            auth = auth?.toCoreAuth(),
            iconPath = null,
        )

    private fun RegistryTransportDraft.toCoreTransport(): TransportConfig =
        when (this) {
            is RegistryStreamableHttpDraft -> TransportConfig.StreamableHttpTransport(url = url, headers = headers)
            is RegistryHttpDraft -> TransportConfig.HttpTransport(url = url, headers = headers)
            is RegistryStdioDraft -> TransportConfig.StdioTransport(command = command, args = args)
        }

    private fun RegistryOAuthDraft.toCoreAuth(): AuthConfig.OAuth =
        AuthConfig.OAuth(
            clientId = clientId,
            clientSecret = clientSecret,
            callbackPort = callbackPort,
            clientIdMetadataUrl = clientIdMetadataUrl,
            authServerMetadataUrl = authServerMetadataUrl,
            redirectUri = redirectUri,
            clientName = clientName,
            tokenEndpointAuthMethod = tokenEndpointAuthMethod,
            authorizationServer = authorizationServer,
            scopes = scopes,
            allowDynamicRegistration = allowDynamicRegistration,
        )
}
