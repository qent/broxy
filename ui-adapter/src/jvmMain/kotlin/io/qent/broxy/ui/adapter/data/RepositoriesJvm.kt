package io.qent.broxy.ui.adapter.data

import io.qent.broxy.core.config.JsonConfigurationRepository
import io.qent.broxy.core.repository.ConfigurationRepository

actual fun provideConfigurationRepository(): ConfigurationRepository = JsonConfigurationRepository()

