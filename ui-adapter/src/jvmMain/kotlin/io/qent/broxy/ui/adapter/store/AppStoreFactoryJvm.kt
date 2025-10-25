package io.qent.broxy.ui.adapter.store

import io.qent.broxy.core.proxy.runtime.ProxyController
import io.qent.broxy.core.proxy.runtime.ProxyLifecycle
import io.qent.broxy.core.proxy.runtime.createProxyController
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.ui.adapter.capabilities.CapabilityCachePersistence
import io.qent.broxy.ui.adapter.capabilities.CapabilityFetcher
import io.qent.broxy.ui.adapter.clients.provideAiClientConnectors
import io.qent.broxy.ui.adapter.data.UiSettingsRepository
import io.qent.broxy.ui.adapter.data.provideCapabilityCachePersistence
import io.qent.broxy.ui.adapter.data.provideConfigurationRepository
import io.qent.broxy.ui.adapter.data.provideDefaultLogger
import io.qent.broxy.ui.adapter.data.provideUiSettingsRepository
import io.qent.broxy.ui.adapter.models.toUi
import io.qent.broxy.ui.adapter.remote.createRemoteConnector
import io.qent.broxy.ui.adapter.services.fetchServerCapabilities
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

fun createAppStore(
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    logger: CollectingLogger = provideDefaultLogger(),
    repository: ConfigurationRepository = provideConfigurationRepository(),
    uiSettingsRepository: UiSettingsRepository = provideUiSettingsRepository(),
    proxyFactory: (CollectingLogger) -> ProxyController = { createProxyController(it) },
    capabilityFetcher: CapabilityFetcher =
        { config, timeout, retries, authorizationStatusListener ->
            fetchServerCapabilities(config.toUi(), timeout, retries, logger, authorizationStatusListener)
        },
    now: () -> Long = { System.currentTimeMillis() },
    enableBackgroundRefresh: Boolean = true,
    capabilityCachePersistence: CapabilityCachePersistence = provideCapabilityCachePersistence(logger),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): AppStore {
    val proxyController = proxyFactory(logger)
    val proxyLifecycle = ProxyLifecycle(proxyController, logger)
    val remoteConnector =
        createRemoteConnector(
            logger = logger,
            proxyRuntime = proxyLifecycle,
            scope = scope,
        )
    return AppStore(
        configurationRepository = repository,
        uiSettingsRepository = uiSettingsRepository,
        proxyRuntime = proxyLifecycle,
        capabilityFetcher = capabilityFetcher,
        logger = logger,
        aiClientConnectors = provideAiClientConnectors(),
        scope = scope,
        ioDispatcher = ioDispatcher,
        now = now,
        enableBackgroundRefresh = enableBackgroundRefresh,
        remoteConnector = remoteConnector,
        capabilityCachePersistence = capabilityCachePersistence,
    )
}
