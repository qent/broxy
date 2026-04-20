package io.qent.broxy.core.presetmanagement

@Suppress("TooManyFunctions", "MaxLineLength")
interface PresetManagementBackend {
    val agenticModeEnabled: Boolean
        get() = false

    suspend fun getPresetCreationAlgorithm(): PresetCreationAlgorithmResponse

    suspend fun listServerNames(): ListServerNamesResponse

    suspend fun listCatalogServerNames(): ListCatalogServerNamesResponse = throw PresetManagementException("Agentic mode is disabled")

    suspend fun installCatalogServer(request: InstallCatalogServerRequest): InstallCatalogServerResponse =
        throw PresetManagementException("Agentic mode is disabled")

    suspend fun getCatalogServerInstallStatus(request: GetCatalogServerInstallStatusRequest): CatalogServerInstallStatusResponse =
        throw PresetManagementException("Agentic mode is disabled")

    suspend fun setServerEnabled(request: SetServerEnabledRequest): SetServerEnabledResponse =
        throw PresetManagementException("Agentic mode is disabled")

    suspend fun getServerDescription(request: ServerDescriptionRequest): ServerDescriptionResponse

    suspend fun listPresetNames(): ListPresetNamesResponse

    suspend fun getPresetDescription(request: PresetDescriptionRequest): PresetDescriptionResponse

    suspend fun createPreset(request: CreatePresetRequest): CreatePresetResponse

    fun availableToolNames(): List<String> =
        if (agenticModeEnabled) {
            PresetManagementToolNames.allWithAgentic
        } else {
            PresetManagementToolNames.base
        }
}

open class PresetManagementException(
    message: String,
) : IllegalArgumentException(message)

class PresetManagementNotFoundException(
    message: String,
) : PresetManagementException(message)

class PresetManagementAmbiguityException(
    message: String,
    val candidates: List<NamedPresetManagementItem>,
) : PresetManagementException(message)
