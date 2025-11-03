package io.qent.broxy.ui.adapter.data

import io.qent.broxy.core.repository.ConfigurationRepository

// Provide ConfigurationRepository from the adapter to keep UI decoupled from core
expect fun provideConfigurationRepository(): ConfigurationRepository

