package io.qent.broxy.ui.adapter.store

import io.qent.broxy.core.capabilities.FilePersistedCapabilityCacheStore
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.proxy.runtime.ProxyController
import io.qent.broxy.core.proxy.runtime.ProxyLifecycle
import io.qent.broxy.core.proxy.runtime.createProxyController
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.AppCacheDir
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.ui.adapter.capabilities.CapabilityCachePersistence
import io.qent.broxy.ui.adapter.capabilities.CapabilityFetcher
import io.qent.broxy.ui.adapter.clients.provideAiClientConnectors
import io.qent.broxy.ui.adapter.data.UiSettingsRepository
import io.qent.broxy.ui.adapter.data.provideCapabilityCachePersistence
import io.qent.broxy.ui.adapter.data.provideCatalogRepository
import io.qent.broxy.ui.adapter.data.provideConfigurationRepository
import io.qent.broxy.ui.adapter.data.provideDefaultLogger
import io.qent.broxy.ui.adapter.data.provideImportedServerHideRepository
import io.qent.broxy.ui.adapter.data.provideImportedServerInstallRepository
import io.qent.broxy.ui.adapter.data.provideServerIconRepository
import io.qent.broxy.ui.adapter.data.provideUiSettingsRepository
import io.qent.broxy.ui.adapter.icons.ServerIconRepository
import io.qent.broxy.ui.adapter.models.toUi
import io.qent.broxy.ui.adapter.presetmanagement.DesktopPresetManagementBackend
import io.qent.broxy.ui.adapter.remote.createRemoteConnector
import io.qent.broxy.ui.adapter.services.fetchServerCapabilities
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

fun createAppStore(
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    logger: CollectingLogger = provideDefaultLogger(),
    repository: ConfigurationRepository = provideConfigurationRepository(),
    uiSettingsRepository: UiSettingsRepository = provideUiSettingsRepository(),
    serverIconRepository: ServerIconRepository = provideServerIconRepository(),
    proxyFactory: (CollectingLogger) -> ProxyController = { createProxyController(it) },
    capabilityFetcher: CapabilityFetcher =
        { config, timeout, retries, authorizationStatusListener ->
            val ignoreHttpsCertificateErrors =
                runCatching { repository.loadMcpConfig().ignoreHttpsCertificateErrors }
                    .getOrDefault(false)
            fetchServerCapabilities(
                config = config.toUi(),
                timeoutSeconds = timeout,
                connectionRetryCount = retries,
                ignoreHttpsCertificateErrors = ignoreHttpsCertificateErrors,
                logger = logger,
                authorizationStatusListener = authorizationStatusListener,
            )
        },
    now: () -> Long = { System.currentTimeMillis() },
    enableBackgroundRefresh: Boolean = true,
    capabilityCachePersistence: CapabilityCachePersistence = provideCapabilityCachePersistence(logger),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): AppStore {
    val proxyController = proxyFactory(logger)
    val proxyLifecycle = ProxyLifecycle(proxyController, logger)
    val catalogRepository = provideCatalogRepository()
    val remoteConnector =
        createRemoteConnector(
            logger = logger,
            proxyRuntime = proxyLifecycle,
            scope = scope,
        )
    var latestCapabilities: Map<String, ServerCapabilities> = emptyMap()
    scope.launch {
        proxyLifecycle.capabilityUpdates.collect { caps ->
            latestCapabilities = caps
        }
    }
    val store =
        AppStore(
            configurationRepository = repository,
            uiSettingsRepository = uiSettingsRepository,
            serverIconRepository = serverIconRepository,
            proxyRuntime = proxyLifecycle,
            capabilityFetcher = capabilityFetcher,
            logger = logger,
            aiClientConnectors = provideAiClientConnectors(),
            scope = scope,
            ioDispatcher = ioDispatcher,
            now = now,
            enableBackgroundRefresh = enableBackgroundRefresh,
            remoteConnector = remoteConnector,
            importedServerHideRepository = provideImportedServerHideRepository(),
            importedServerInstallRepository = provideImportedServerInstallRepository(),
            catalogRepository = catalogRepository,
            capabilityCachePersistence = capabilityCachePersistence,
        )
    proxyLifecycle.registerPresetManagementBackend(
        DesktopPresetManagementBackend(
            configurationRepository = repository,
            liveCapabilitiesProvider = { latestCapabilities },
            capabilityCacheStore =
                FilePersistedCapabilityCacheStore(
                    baseDir = AppCacheDir.resolve(),
                    logger = logger,
                ),
            logger = logger,
            configuredServersProvider = { store.currentServersForPresetManagement() },
            savedPresetNamesProvider = { store.currentPresetNamesForPresetManagement() },
            refreshPresetListAfterCreate = { store.refreshPresetsForPresetManagement().getOrThrow() },
            catalogRepository = catalogRepository,
            proxyRuntime = proxyLifecycle,
            coroutineScope = scope,
            requestInstallPermission = { request ->
                store.requestAgenticInstallPermission(request)
            },
            refreshUiAfterServerMutation = { store.refreshServersForPresetManagement().getOrThrow() },
            agenticModeEnabledProvider = { store.isPresetManagementAgenticModeEnabled() },
        ),
    )
    return store
}
