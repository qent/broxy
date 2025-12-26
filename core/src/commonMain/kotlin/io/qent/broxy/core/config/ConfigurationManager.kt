package io.qent.broxy.core.config

import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.Logger

class ConfigurationManager(
    private val repository: ConfigurationRepository,
    private val logger: Logger,
) {
    data class ServerRenameResult(
        val config: McpServersConfig,
        val presetMigrationError: Throwable? = null,
    )

    fun upsertServer(
        config: McpServersConfig,
        server: McpServerConfig,
    ): Result<McpServersConfig> =
        saveConfig(
            mutateServers(config) { servers ->
                val idx = servers.indexOfFirst { it.id == server.id }
                if (idx >= 0) servers[idx] = server else servers += server
            },
        )

    fun renameServer(
        config: McpServersConfig,
        oldId: String,
        server: McpServerConfig,
    ): Result<ServerRenameResult> {
        val updated =
            mutateServers(config) { servers ->
                val oldIndex = servers.indexOfFirst { it.id == oldId }
                val existingIndex = servers.indexOfFirst { it.id == server.id }
                if (oldId.isNotBlank() && oldId != server.id) {
                    if (existingIndex >= 0) {
                        servers[existingIndex] = server
                        if (oldIndex >= 0 && oldIndex != existingIndex) {
                            servers.removeAt(oldIndex)
                        }
                    } else if (oldIndex >= 0) {
                        servers.removeAt(oldIndex)
                        servers.add(oldIndex.coerceAtMost(servers.size), server)
                    } else {
                        servers += server
                    }
                } else {
                    if (existingIndex >= 0) servers[existingIndex] = server else servers += server
                }
            }
        val saveResult = saveConfig(updated)
        if (saveResult.isFailure) {
            return saveResult.map { ServerRenameResult(it) }
        }
        val migrationError =
            if (oldId.isNotBlank() && oldId != server.id) {
                runCatching { migratePresetsServerId(oldId, server.id) }.exceptionOrNull()
            } else {
                null
            }
        if (migrationError != null) {
            logger.warn(
                "Failed to update presets after renaming server '$oldId' -> '${server.id}': ${migrationError.message}",
                migrationError,
            )
        }
        return Result.success(ServerRenameResult(updated, migrationError))
    }

    fun removeServer(
        config: McpServersConfig,
        serverId: String,
    ): Result<McpServersConfig> =
        saveConfig(
            mutateServers(config) { servers ->
                servers.removeAll { it.id == serverId }
            },
        )

    fun toggleServer(
        config: McpServersConfig,
        serverId: String,
        enabled: Boolean,
    ): Result<McpServersConfig> =
        saveConfig(
            mutateServers(config) { servers ->
                val idx = servers.indexOfFirst { it.id == serverId }
                if (idx >= 0) servers[idx] = servers[idx].copy(enabled = enabled)
            },
        )

    fun updateRequestTimeout(
        config: McpServersConfig,
        seconds: Int,
    ): Result<McpServersConfig> = saveConfig(config.copy(requestTimeoutSeconds = seconds))

    fun updateCapabilitiesTimeout(
        config: McpServersConfig,
        seconds: Int,
    ): Result<McpServersConfig> = saveConfig(config.copy(capabilitiesTimeoutSeconds = seconds))

    fun updateConnectionRetryCount(
        config: McpServersConfig,
        count: Int,
    ): Result<McpServersConfig> = saveConfig(config.copy(connectionRetryCount = count.coerceAtLeast(1)))

    fun updateInboundSsePort(
        config: McpServersConfig,
        port: Int,
    ): Result<McpServersConfig> = saveConfig(config.copy(inboundSsePort = port.coerceIn(1, 65535)))

    fun updateRefreshInterval(
        config: McpServersConfig,
        seconds: Int,
    ): Result<McpServersConfig> = saveConfig(config.copy(capabilitiesRefreshIntervalSeconds = seconds))

    fun updateTrayIconVisibility(
        config: McpServersConfig,
        visible: Boolean,
    ): Result<McpServersConfig> = saveConfig(config.copy(showTrayIcon = visible))

    fun updateFallbackPromptsAndResourcesToTools(
        config: McpServersConfig,
        enabled: Boolean,
    ): Result<McpServersConfig> = saveConfig(config.copy(fallbackPromptsAndResourcesToTools = enabled))

    fun updateDefaultPresetId(
        config: McpServersConfig,
        presetId: String?,
    ): Result<McpServersConfig> = saveConfig(config.copy(defaultPresetId = presetId?.takeIf { it.isNotBlank() }))

    fun savePreset(preset: Preset): Result<Preset> =
        runCatching {
            repository.savePreset(preset)
            preset
        }.onFailure { logger.warn("Failed to save preset '${preset.id}': ${it.message}", it) }

    fun deletePreset(id: String): Result<Unit> =
        runCatching {
            repository.deletePreset(id)
        }.onFailure { logger.warn("Failed to delete preset '$id': ${it.message}", it) }

    private fun mutateServers(
        config: McpServersConfig,
        block: (MutableList<McpServerConfig>) -> Unit,
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

    private fun migratePresetsServerId(
        oldId: String,
        newId: String,
    ) {
        if (oldId == newId) return
        val presets = repository.listPresets()
        presets.forEach { preset ->
            val updated =
                preset.copy(
                    tools =
                        preset.tools.map { ref ->
                            if (ref.serverId == oldId) ref.copy(serverId = newId) else ref
                        },
                    prompts =
                        preset.prompts?.map { ref ->
                            if (ref.serverId == oldId) ref.copy(serverId = newId) else ref
                        },
                    resources =
                        preset.resources?.map { ref ->
                            if (ref.serverId == oldId) ref.copy(serverId = newId) else ref
                        },
                )
            if (updated != preset) {
                repository.savePreset(updated)
            }
        }
    }
}
