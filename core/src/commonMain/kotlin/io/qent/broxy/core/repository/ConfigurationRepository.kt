package io.qent.broxy.core.repository

import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.Preset

interface ConfigurationRepository {
    fun loadMcpConfig(): McpServersConfig

    fun saveMcpConfig(config: McpServersConfig)

    fun loadPreset(id: String): Preset

    fun savePreset(preset: Preset)

    fun listPresets(): List<Preset>

    fun deletePreset(id: String)
}
