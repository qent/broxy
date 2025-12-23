package io.qent.broxy.ui.adapter.data

import io.qent.broxy.core.capabilities.CapabilityCachePersistence
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.CollectingLogger

// Provide ConfigurationRepository from the adapter to keep UI decoupled from core
expect fun provideConfigurationRepository(): ConfigurationRepository

expect fun provideDefaultLogger(): CollectingLogger

expect fun provideCapabilityCachePersistence(logger: CollectingLogger): CapabilityCachePersistence

expect fun openLogsFolder(): Result<Unit>
