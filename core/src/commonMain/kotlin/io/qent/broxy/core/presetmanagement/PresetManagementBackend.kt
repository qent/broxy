package io.qent.broxy.core.presetmanagement

interface PresetManagementBackend {
    suspend fun getPresetCreationAlgorithm(): PresetCreationAlgorithmResponse

    suspend fun listServerNames(): ListServerNamesResponse

    suspend fun getServerDescription(request: ServerDescriptionRequest): ServerDescriptionResponse

    suspend fun listPresetNames(): ListPresetNamesResponse

    suspend fun getPresetDescription(request: PresetDescriptionRequest): PresetDescriptionResponse

    suspend fun createPreset(request: CreatePresetRequest): CreatePresetResponse
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
