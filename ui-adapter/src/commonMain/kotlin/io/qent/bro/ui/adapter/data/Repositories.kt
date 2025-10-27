package io.qent.bro.ui.adapter.data

import io.qent.bro.core.repository.ConfigurationRepository

// Provide ConfigurationRepository from the adapter to keep UI decoupled from core
expect fun provideConfigurationRepository(): ConfigurationRepository

