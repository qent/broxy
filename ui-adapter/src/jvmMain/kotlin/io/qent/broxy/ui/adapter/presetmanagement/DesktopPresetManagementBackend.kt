package io.qent.broxy.ui.adapter.presetmanagement

import io.qent.broxy.core.capabilities.PersistedCapabilityCacheStore
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.presetmanagement.CreatePresetRequest
import io.qent.broxy.core.presetmanagement.CreatePresetResponse
import io.qent.broxy.core.presetmanagement.JvmPresetManagementBackend
import io.qent.broxy.core.presetmanagement.ListPresetNamesResponse
import io.qent.broxy.core.presetmanagement.ListServerNamesResponse
import io.qent.broxy.core.presetmanagement.NamedPresetManagementItem
import io.qent.broxy.core.presetmanagement.PresetCreationAlgorithmResponse
import io.qent.broxy.core.presetmanagement.PresetDescriptionRequest
import io.qent.broxy.core.presetmanagement.PresetDescriptionResponse
import io.qent.broxy.core.presetmanagement.PresetManagementBackend
import io.qent.broxy.core.presetmanagement.ServerDescriptionRequest
import io.qent.broxy.core.presetmanagement.ServerDescriptionResponse
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.Logger

internal class DesktopPresetManagementBackend(
    configurationRepository: ConfigurationRepository,
    liveCapabilitiesProvider: () -> Map<String, ServerCapabilities>,
    capabilityCacheStore: PersistedCapabilityCacheStore,
    logger: Logger,
    configuredServersProvider: () -> List<McpServerConfig>,
    savedPresetNamesProvider: () -> List<NamedPresetManagementItem>,
    private val refreshPresetListAfterCreate: suspend () -> Unit,
) : PresetManagementBackend {
    private val delegate =
        JvmPresetManagementBackend(
            configurationRepository = configurationRepository,
            liveCapabilitiesProvider = liveCapabilitiesProvider,
            capabilityCacheStore = capabilityCacheStore,
            logger = logger,
            configuredServersProvider = configuredServersProvider,
            savedPresetNamesProvider = savedPresetNamesProvider,
        )

    override suspend fun getPresetCreationAlgorithm(): PresetCreationAlgorithmResponse = delegate.getPresetCreationAlgorithm()

    override suspend fun listServerNames(): ListServerNamesResponse = delegate.listServerNames()

    override suspend fun getServerDescription(request: ServerDescriptionRequest): ServerDescriptionResponse =
        delegate.getServerDescription(request)

    override suspend fun listPresetNames(): ListPresetNamesResponse = delegate.listPresetNames()

    override suspend fun getPresetDescription(request: PresetDescriptionRequest): PresetDescriptionResponse =
        delegate.getPresetDescription(request)

    override suspend fun createPreset(request: CreatePresetRequest): CreatePresetResponse {
        val created = delegate.createPreset(request)
        refreshPresetListAfterCreate()
        return created
    }
}
