package io.qent.bro.ui.adapter.data

import io.qent.bro.core.config.JsonConfigurationRepository
import io.qent.bro.core.repository.ConfigurationRepository

actual fun provideConfigurationRepository(): ConfigurationRepository = JsonConfigurationRepository()

