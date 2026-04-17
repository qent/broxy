package io.qent.broxy.core.presetmanagement

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NamedPresetManagementItem(
    val id: String,
    val name: String,
)

@Serializable
data class PresetCreationAlgorithmResponse(
    val prompt: String,
    val steps: List<String>,
)

@Serializable
data class ListServerNamesResponse(
    val servers: List<NamedPresetManagementItem>,
)

@Serializable
data class ListCatalogServerNamesResponse(
    val servers: List<NamedPresetManagementItem>,
)

@Serializable
data class ListPresetNamesResponse(
    val presets: List<NamedPresetManagementItem>,
)

@Serializable
data class InstallCatalogServerRequest(
    @SerialName("server_id")
    val serverId: String,
)

@Serializable
data class GetCatalogServerInstallStatusRequest(
    @SerialName("server_id")
    val serverId: String,
)

@Serializable
data class SetServerEnabledRequest(
    @SerialName("server_id")
    val serverId: String,
    val enabled: Boolean,
)

@Serializable
enum class CatalogServerInstallState {
    @SerialName("not_installed")
    NotInstalled,

    @SerialName("installing")
    Installing,

    @SerialName("installed")
    Installed,
}

@Serializable
data class InstallCatalogServerResponse(
    @SerialName("server_id")
    val serverId: String,
    val state: CatalogServerInstallState,
    val message: String? = null,
)

@Serializable
data class CatalogServerInstallStatusResponse(
    @SerialName("server_id")
    val serverId: String,
    val state: CatalogServerInstallState,
    val installed: Boolean,
    val ready: Boolean,
    val message: String? = null,
)

@Serializable
data class SetServerEnabledResponse(
    @SerialName("server_id")
    val serverId: String,
    val enabled: Boolean,
)

@Serializable
data class ServerDescriptionRequest(
    @SerialName("server_name")
    val serverName: String,
    @SerialName("server_id")
    val serverId: String? = null,
)

@Serializable
data class PresetDescriptionRequest(
    @SerialName("preset_name")
    val presetName: String,
    @SerialName("preset_id")
    val presetId: String? = null,
)

@Serializable
data class PresetToolSelection(
    @SerialName("server_id")
    val serverId: String,
    @SerialName("tool_name")
    val toolName: String,
)

@Serializable
data class CreatePresetRequest(
    @SerialName("preset_id")
    val presetId: String,
    @SerialName("preset_name")
    val presetName: String,
    val tools: List<PresetToolSelection>,
)

@Serializable
data class CreatePresetResponse(
    @SerialName("preset_id")
    val presetId: String,
    @SerialName("preset_name")
    val presetName: String,
)

@Serializable
enum class CapabilitySourceStatus {
    @SerialName("live")
    Live,

    @SerialName("cached")
    Cached,

    @SerialName("missing")
    Missing,
}

@Serializable
data class CapabilityArgumentPayload(
    val name: String,
    val type: String = "unspecified",
    val required: Boolean = false,
)

@Serializable
data class ToolCapabilityPayload(
    val name: String,
    val description: String,
    val arguments: List<CapabilityArgumentPayload> = emptyList(),
)

@Serializable
data class PromptCapabilityPayload(
    val name: String,
    val description: String,
    val arguments: List<CapabilityArgumentPayload> = emptyList(),
)

@Serializable
data class ResourceCapabilityPayload(
    val key: String,
    val name: String,
    val description: String,
    val arguments: List<CapabilityArgumentPayload> = emptyList(),
)

@Serializable
data class ServerDescriptionResponse(
    @SerialName("server_id")
    val serverId: String,
    @SerialName("server_name")
    val serverName: String,
    val description: String,
    @SerialName("capabilities_source")
    val capabilitiesSource: CapabilitySourceStatus,
    val tools: List<ToolCapabilityPayload> = emptyList(),
    val prompts: List<PromptCapabilityPayload> = emptyList(),
    val resources: List<ResourceCapabilityPayload> = emptyList(),
)

@Serializable
data class SourcedToolCapabilityPayload(
    val name: String,
    val description: String,
    val arguments: List<CapabilityArgumentPayload> = emptyList(),
    @SerialName("source_server_id")
    val sourceServerId: String,
    @SerialName("source_server_name")
    val sourceServerName: String,
)

@Serializable
data class SourcedPromptCapabilityPayload(
    val name: String,
    val description: String,
    val arguments: List<CapabilityArgumentPayload> = emptyList(),
    @SerialName("source_server_id")
    val sourceServerId: String,
    @SerialName("source_server_name")
    val sourceServerName: String,
)

@Serializable
data class SourcedResourceCapabilityPayload(
    val key: String,
    val name: String,
    val description: String,
    val arguments: List<CapabilityArgumentPayload> = emptyList(),
    @SerialName("source_server_id")
    val sourceServerId: String,
    @SerialName("source_server_name")
    val sourceServerName: String,
)

@Serializable
data class MissingCapabilityPayload(
    val type: String,
    val key: String,
    @SerialName("source_server_id")
    val sourceServerId: String,
    @SerialName("source_server_name")
    val sourceServerName: String?,
)

@Serializable
data class PresetDescriptionResponse(
    @SerialName("preset_id")
    val presetId: String,
    @SerialName("preset_name")
    val presetName: String,
    val description: String,
    val tools: List<SourcedToolCapabilityPayload> = emptyList(),
    val prompts: List<SourcedPromptCapabilityPayload> = emptyList(),
    val resources: List<SourcedResourceCapabilityPayload> = emptyList(),
    @SerialName("missing_capabilities")
    val missingCapabilities: List<MissingCapabilityPayload> = emptyList(),
)
