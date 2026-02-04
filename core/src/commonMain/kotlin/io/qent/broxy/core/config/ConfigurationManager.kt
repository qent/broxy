package io.qent.broxy.core.config

import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.proxy.isSafeNamespaceServerId
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.ConfigurationException
import io.qent.broxy.core.utils.Logger

class ConfigurationManager(
    private val repository: ConfigurationRepository,
    private val logger: Logger,
) {
    val settings = SettingsManager()

    data class ServerRenameResult(
        val config: McpServersConfig,
        val presetMigrationError: Throwable? = null,
    )

    fun upsertServer(
        config: McpServersConfig,
        server: McpServerConfig,
    ): Result<McpServersConfig> =
        validateNamespaceServerId(config, server.id).let { validation ->
            if (validation.isFailure) {
                Result.failure(
                    validation.exceptionOrNull()
                        ?: ConfigurationException("Invalid server id '$server.id'"),
                )
            } else {
                saveConfig(
                    mutateServers(config) { servers ->
                        val idx = servers.indexOfFirst { it.id == server.id }
                        if (idx >= 0) servers[idx] = server else servers += server
                    },
                )
            }
        }

    fun renameServer(
        config: McpServersConfig,
        oldId: String,
        server: McpServerConfig,
    ): Result<ServerRenameResult> {
        val validation = validateNamespaceServerId(config, server.id)
        val result: Result<ServerRenameResult> =
            if (validation.isFailure) {
                Result.failure(
                    validation.exceptionOrNull()
                        ?: ConfigurationException("Invalid server id '${server.id}'"),
                )
            } else {
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
                    saveResult.map { ServerRenameResult(it) }
                } else {
                    val migrationError =
                        if (oldId.isNotBlank() && oldId != server.id) {
                            runCatching { migratePresetsServerId(oldId, server.id) }.exceptionOrNull()
                        } else {
                            null
                        }
                    if (migrationError != null) {
                        logger.warn(
                            "Failed to update presets after renaming server '$oldId' -> '${server.id}': " +
                                "${migrationError.message}",
                            migrationError,
                        )
                    }
                    Result.success(ServerRenameResult(updated, migrationError))
                }
            }
        return result
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

    fun savePreset(preset: Preset): Result<Preset> =
        runCatching {
            repository.savePreset(preset)
            preset
        }.onFailure { logger.warn("Failed to save preset '${preset.id}': ${it.message}", it) }

    fun deletePreset(id: String): Result<Unit> =
        runCatching {
            repository.deletePreset(id)
        }.onFailure { logger.warn("Failed to delete preset '$id': ${it.message}", it) }

    inner class SettingsManager {
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
        ): Result<McpServersConfig> =
            saveConfig(
                config.copy(connectionRetryCount = count.coerceAtLeast(MIN_RETRY_COUNT)),
            )

        fun updateInboundHttpPort(
            config: McpServersConfig,
            port: Int,
        ): Result<McpServersConfig> =
            saveConfig(
                config.copy(inboundHttpPort = port.coerceIn(MIN_PORT, MAX_PORT)),
            )

        fun updateRefreshInterval(
            config: McpServersConfig,
            seconds: Int,
        ): Result<McpServersConfig> = saveConfig(config.copy(capabilitiesRefreshIntervalSeconds = seconds))

        fun updateFallbackPromptsAndResourcesToTools(
            config: McpServersConfig,
            enabled: Boolean,
        ): Result<McpServersConfig> = saveConfig(config.copy(fallbackPromptsAndResourcesToTools = enabled))

        fun updateAdapterMode(
            config: McpServersConfig,
            enabled: Boolean,
        ): Result<McpServersConfig> = saveConfig(config.copy(adapterMode = enabled))

        fun updateDefaultPresetId(
            config: McpServersConfig,
            presetId: String?,
        ): Result<McpServersConfig> =
            saveConfig(
                config.copy(defaultPresetId = presetId?.takeIf { it.isNotBlank() }),
            )
    }

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

    private fun validateNamespaceServerId(
        config: McpServersConfig,
        serverId: String,
    ): Result<Unit> {
        if (isSafeNamespaceServerId(serverId)) return Result.success(Unit)
        val existing = config.servers.any { it.id == serverId }
        return if (existing) {
            logger.warn(
                "Server id '$serverId' contains '_' which conflicts with tool namespaces. " +
                    "Existing configs are supported, but new ids should avoid underscores.",
            )
            Result.success(Unit)
        } else {
            Result.failure(
                ConfigurationException(
                    "Server id '$serverId' cannot contain '_' because it conflicts with tool namespaces",
                ),
            )
        }
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

private const val MIN_RETRY_COUNT = 1
private const val MIN_PORT = 1
private const val MAX_PORT = 65535
