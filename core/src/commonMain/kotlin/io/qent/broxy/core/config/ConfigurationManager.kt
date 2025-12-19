package io.qent.broxy.core.config

import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.Logger

class ConfigurationManager(
    private val repository: ConfigurationRepository,
    private val logger: Logger
) {
    fun upsertServer(config: McpServersConfig, server: McpServerConfig): Result<McpServersConfig> =
        saveConfig(mutateServers(config) { servers ->
            val idx = servers.indexOfFirst { it.id == server.id }
            if (idx >= 0) servers[idx] = server else servers += server
        })

    fun renameServer(
        config: McpServersConfig,
        oldId: String,
        server: McpServerConfig
    ): Result<McpServersConfig> = saveConfig(mutateServers(config) { servers ->
        if (oldId.isNotBlank() && oldId != server.id) {
            servers.removeAll { it.id == oldId }
        }
        val idx = servers.indexOfFirst { it.id == server.id }
        if (idx >= 0) servers[idx] = server else servers += server
    })

    fun removeServer(config: McpServersConfig, serverId: String): Result<McpServersConfig> =
        saveConfig(mutateServers(config) { servers ->
            servers.removeAll { it.id == serverId }
        })

    fun toggleServer(config: McpServersConfig, serverId: String, enabled: Boolean): Result<McpServersConfig> =
        saveConfig(mutateServers(config) { servers ->
            val idx = servers.indexOfFirst { it.id == serverId }
            if (idx >= 0) servers[idx] = servers[idx].copy(enabled = enabled)
        })

    fun updateRequestTimeout(config: McpServersConfig, seconds: Int): Result<McpServersConfig> =
        saveConfig(config.copy(requestTimeoutSeconds = seconds))

    fun updateCapabilitiesTimeout(config: McpServersConfig, seconds: Int): Result<McpServersConfig> =
        saveConfig(config.copy(capabilitiesTimeoutSeconds = seconds))

    fun updateInboundSsePort(config: McpServersConfig, port: Int): Result<McpServersConfig> =
        saveConfig(config.copy(inboundSsePort = port.coerceIn(1, 65535)))

    fun updateRefreshInterval(config: McpServersConfig, seconds: Int): Result<McpServersConfig> =
        saveConfig(config.copy(capabilitiesRefreshIntervalSeconds = seconds))

    fun updateTrayIconVisibility(config: McpServersConfig, visible: Boolean): Result<McpServersConfig> =
        saveConfig(config.copy(showTrayIcon = visible))

    fun updateDefaultPresetId(config: McpServersConfig, presetId: String?): Result<McpServersConfig> =
        saveConfig(config.copy(defaultPresetId = presetId?.takeIf { it.isNotBlank() }))

    fun savePreset(preset: Preset): Result<Preset> = runCatching {
        repository.savePreset(preset)
        preset
    }.onFailure { logger.warn("Failed to save preset '${preset.id}': ${it.message}", it) }

    fun deletePreset(id: String): Result<Unit> = runCatching {
        repository.deletePreset(id)
    }.onFailure { logger.warn("Failed to delete preset '$id': ${it.message}", it) }

    private fun mutateServers(
        config: McpServersConfig,
        block: (MutableList<McpServerConfig>) -> Unit
    ): McpServersConfig {
        val servers = config.servers.toMutableList()
        block(servers)
        return config.copy(servers = servers)
    }

    private fun saveConfig(updated: McpServersConfig): Result<McpServersConfig> =
        runCatching {
            repository.saveMcpConfig(updated)
            updated
        }.onFailure {
            logger.warn("Failed to save configuration: ${it.message}", it)
        }
}
