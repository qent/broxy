package io.qent.broxy.registry.catalog

enum class CatalogConnectionType(
    val label: String,
) {
    StreamableHttp("HTTP"),
    Sse("SSE"),
    StdioPackage("STDIO"),
}

enum class CatalogFieldFormat {
    String,
    Number,
    Boolean,
    Filepath,
}

data class CatalogServerEntry(
    val detail: CatalogServerDetail,
    val connectionType: CatalogConnectionType?,
    val canInstallWithoutInput: Boolean,
    val connectionTypeLabel: String,
    val capabilities: List<String>,
    val iconUrl: String?,
)

data class CatalogServerItem(
    val id: String,
    val title: String,
    val canonicalName: String,
    val canInstallWithoutInput: Boolean,
    val description: String,
    val connectionTypeLabel: String,
    val capabilities: List<String>,
    val iconUrl: String?,
    val websiteUrl: String?,
    val repositoryUrl: String?,
    val installed: Boolean,
)

data class CatalogInstallField(
    val id: String,
    val label: String,
    val description: String? = null,
    val format: CatalogFieldFormat = CatalogFieldFormat.String,
    val isRequired: Boolean = false,
    val isSecret: Boolean = false,
    val isRepeated: Boolean = false,
    val choices: List<String> = emptyList(),
    val placeholder: String? = null,
    val defaultValue: String? = null,
)

data class CatalogInstallSession(
    val serverId: String,
    val defaultName: String,
    val transportLabel: String,
    val connectionType: CatalogConnectionType,
    val detail: CatalogServerDetail,
    val installSteps: List<String>,
    val fields: List<CatalogInstallField>,
)

sealed interface RegistryTransportDraft

data class RegistryStreamableHttpDraft(
    val url: String,
    val headers: Map<String, String>,
) : RegistryTransportDraft

data class RegistryHttpDraft(
    val url: String,
    val headers: Map<String, String>,
) : RegistryTransportDraft

data class RegistryStdioDraft(
    val command: String,
    val args: List<String>,
) : RegistryTransportDraft

data class RegistryOAuthDraft(
    val clientId: String? = null,
    val clientSecret: String? = null,
    val callbackPort: Int? = null,
    val clientIdMetadataUrl: String? = null,
    val authServerMetadataUrl: String? = null,
    val redirectUri: String? = null,
    val clientName: String? = null,
    val tokenEndpointAuthMethod: String? = null,
    val authorizationServer: String? = null,
    val scopes: List<String>? = null,
    val allowDynamicRegistration: Boolean = true,
    val stdioBootstrap: RegistryStdioBootstrapDraft? = null,
)

data class RegistryStdioBootstrapDraft(
    val tool: String,
    val args: Map<String, String> = emptyMap(),
)

data class RegistryServerDraft(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val transport: RegistryTransportDraft,
    val env: Map<String, String>,
    val auth: RegistryOAuthDraft? = null,
)

data class CatalogInstallResult(
    val draft: RegistryServerDraft,
)
