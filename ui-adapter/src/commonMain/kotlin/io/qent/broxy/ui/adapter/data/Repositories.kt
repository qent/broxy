package io.qent.broxy.ui.adapter.data

import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.ui.adapter.capabilities.CapabilityCachePersistence
import io.qent.broxy.ui.adapter.icons.ServerIconRepository

// Provide ConfigurationRepository from the adapter to keep UI decoupled from core
expect fun provideConfigurationRepository(): ConfigurationRepository

expect fun provideUiSettingsRepository(): UiSettingsRepository

expect fun provideDefaultLogger(): CollectingLogger

expect fun provideCapabilityCachePersistence(logger: CollectingLogger): CapabilityCachePersistence

expect fun provideServerIconRepository(): ServerIconRepository

expect fun openLogsFolder(): Result<Unit>

expect fun openExternalUrl(url: String): Result<Unit>
