package io.qent.broxy.ui.adapter.data

import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.CollectingLogger

// Provide ConfigurationRepository from the adapter to keep UI decoupled from core
expect fun provideConfigurationRepository(): ConfigurationRepository

expect fun provideDefaultLogger(): CollectingLogger

expect fun openLogsFolder(): Result<Unit>
