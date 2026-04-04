package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.core.config.ConfigurationManager
import io.qent.broxy.ui.adapter.models.UiMcpServerConfig
import io.qent.broxy.ui.adapter.models.UiMcpServersConfig
import io.qent.broxy.ui.adapter.models.UiPresetCore
import io.qent.broxy.ui.adapter.models.toCore
import io.qent.broxy.ui.adapter.models.toUi

internal data class StoreServerRenameResult(
    val config: UiMcpServersConfig,
    val presetMigrationError: Throwable?,
)

internal interface StoreConfigGateway {
    fun upsertServer(
        config: UiMcpServersConfig,
        server: UiMcpServerConfig,
        insertAtBeginning: Boolean = false,
    ): Result<UiMcpServersConfig>

    fun renameServer(
        config: UiMcpServersConfig,
        oldId: String,
        server: UiMcpServerConfig,
    ): Result<StoreServerRenameResult>

    fun removeServer(
        config: UiMcpServersConfig,
        serverId: String,
    ): Result<UiMcpServersConfig>

    fun reorderServers(
        config: UiMcpServersConfig,
        orderedServerIds: List<String>,
    ): Result<UiMcpServersConfig>

    fun toggleServer(
        config: UiMcpServersConfig,
        serverId: String,
        enabled: Boolean,
    ): Result<UiMcpServersConfig>

    fun savePreset(preset: UiPresetCore): Result<Unit>

    fun deletePreset(id: String): Result<Unit>

    fun reorderPresets(orderedPresetIds: List<String>): Result<Unit>

    fun updateDefaultPresetId(
        config: UiMcpServersConfig,
        presetId: String?,
    ): Result<UiMcpServersConfig>

    fun updateRequestTimeout(
        config: UiMcpServersConfig,
        seconds: Int,
    ): Result<UiMcpServersConfig>

    fun updateCapabilitiesTimeout(
        config: UiMcpServersConfig,
        seconds: Int,
    ): Result<UiMcpServersConfig>

    fun updateConnectionRetryCount(
        config: UiMcpServersConfig,
        count: Int,
    ): Result<UiMcpServersConfig>

    fun updateIgnoreHttpsCertificateErrors(
        config: UiMcpServersConfig,
        enabled: Boolean,
    ): Result<UiMcpServersConfig>

    fun updateInboundHttpPort(
        config: UiMcpServersConfig,
        port: Int,
    ): Result<UiMcpServersConfig>

    fun updateRefreshInterval(
        config: UiMcpServersConfig,
        seconds: Int,
    ): Result<UiMcpServersConfig>

    fun updateMcpFilePath(
        config: UiMcpServersConfig,
        path: String,
    ): Result<UiMcpServersConfig>

    fun updateFallbackPromptsAndResourcesToTools(
        config: UiMcpServersConfig,
        enabled: Boolean,
    ): Result<UiMcpServersConfig>

    fun updateAdapterMode(
        config: UiMcpServersConfig,
        enabled: Boolean,
    ): Result<UiMcpServersConfig>
}

internal class ConfigurationManagerStoreConfigGateway(
    private val configurationManager: ConfigurationManager,
) : StoreConfigGateway {
    override fun upsertServer(
        config: UiMcpServersConfig,
        server: UiMcpServerConfig,
        insertAtBeginning: Boolean,
    ): Result<UiMcpServersConfig> =
        configurationManager
            .upsertServer(config.toCore(), server.toCore(), insertAtBeginning)
            .map { it.toUi() }

    override fun renameServer(
        config: UiMcpServersConfig,
        oldId: String,
        server: UiMcpServerConfig,
    ): Result<StoreServerRenameResult> =
        configurationManager
            .renameServer(config.toCore(), oldId = oldId, server = server.toCore())
            .map { result ->
                StoreServerRenameResult(
                    config = result.config.toUi(),
                    presetMigrationError = result.presetMigrationError,
                )
            }

    override fun removeServer(
        config: UiMcpServersConfig,
        serverId: String,
    ): Result<UiMcpServersConfig> =
        configurationManager
            .removeServer(config.toCore(), serverId)
            .map { it.toUi() }

    override fun reorderServers(
        config: UiMcpServersConfig,
        orderedServerIds: List<String>,
    ): Result<UiMcpServersConfig> =
        configurationManager
            .reorderServers(config.toCore(), orderedServerIds)
            .map { it.toUi() }

    override fun toggleServer(
        config: UiMcpServersConfig,
        serverId: String,
        enabled: Boolean,
    ): Result<UiMcpServersConfig> =
        configurationManager
            .toggleServer(config.toCore(), serverId, enabled)
            .map { it.toUi() }

    override fun savePreset(preset: UiPresetCore): Result<Unit> =
        configurationManager
            .savePreset(preset.toCore())
            .map { Unit }

    override fun deletePreset(id: String): Result<Unit> = configurationManager.deletePreset(id)

    override fun reorderPresets(orderedPresetIds: List<String>): Result<Unit> =
        configurationManager.reorderPresets(orderedPresetIds).map { Unit }

    override fun updateDefaultPresetId(
        config: UiMcpServersConfig,
        presetId: String?,
    ): Result<UiMcpServersConfig> =
        configurationManager
            .settings
            .updateDefaultPresetId(config.toCore(), presetId)
            .map { it.toUi() }

    override fun updateRequestTimeout(
        config: UiMcpServersConfig,
        seconds: Int,
    ): Result<UiMcpServersConfig> =
        configurationManager
            .settings
            .updateRequestTimeout(config.toCore(), seconds)
            .map { it.toUi() }

    override fun updateCapabilitiesTimeout(
        config: UiMcpServersConfig,
        seconds: Int,
    ): Result<UiMcpServersConfig> =
        configurationManager
            .settings
            .updateCapabilitiesTimeout(config.toCore(), seconds)
            .map { it.toUi() }

    override fun updateConnectionRetryCount(
        config: UiMcpServersConfig,
        count: Int,
    ): Result<UiMcpServersConfig> =
        configurationManager
            .settings
            .updateConnectionRetryCount(config.toCore(), count)
            .map { it.toUi() }

    override fun updateIgnoreHttpsCertificateErrors(
        config: UiMcpServersConfig,
        enabled: Boolean,
    ): Result<UiMcpServersConfig> =
        configurationManager
            .settings
            .updateIgnoreHttpsCertificateErrors(config.toCore(), enabled)
            .map { it.toUi() }

    override fun updateInboundHttpPort(
        config: UiMcpServersConfig,
        port: Int,
    ): Result<UiMcpServersConfig> =
        configurationManager
            .settings
            .updateInboundHttpPort(config.toCore(), port)
            .map { it.toUi() }

    override fun updateRefreshInterval(
        config: UiMcpServersConfig,
        seconds: Int,
    ): Result<UiMcpServersConfig> =
        configurationManager
            .settings
            .updateRefreshInterval(config.toCore(), seconds)
            .map { it.toUi() }

    override fun updateMcpFilePath(
        config: UiMcpServersConfig,
        path: String,
    ): Result<UiMcpServersConfig> =
        configurationManager
            .settings
            .updateMcpFilePath(config.toCore(), path)
            .map { it.toUi() }

    override fun updateFallbackPromptsAndResourcesToTools(
        config: UiMcpServersConfig,
        enabled: Boolean,
    ): Result<UiMcpServersConfig> =
        configurationManager
            .settings
            .updateFallbackPromptsAndResourcesToTools(config.toCore(), enabled)
            .map { it.toUi() }

    override fun updateAdapterMode(
        config: UiMcpServersConfig,
        enabled: Boolean,
    ): Result<UiMcpServersConfig> =
        configurationManager
            .settings
            .updateAdapterMode(config.toCore(), enabled)
            .map { it.toUi() }
}
