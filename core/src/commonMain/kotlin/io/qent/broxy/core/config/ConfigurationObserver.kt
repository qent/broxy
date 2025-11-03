package io.qent.broxy.core.config

import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.Preset

interface ConfigurationObserver {
    fun onConfigurationChanged(config: McpServersConfig)
    fun onPresetChanged(preset: Preset)
}

