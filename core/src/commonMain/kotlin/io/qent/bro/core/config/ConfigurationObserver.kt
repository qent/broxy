package io.qent.bro.core.config

import io.qent.bro.core.models.McpServersConfig
import io.qent.bro.core.models.Preset

interface ConfigurationObserver {
    fun onConfigurationChanged(config: McpServersConfig)
    fun onPresetChanged(preset: Preset)
}

