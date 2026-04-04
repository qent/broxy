package io.qent.broxy.registry.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

const val CATALOG_META_KEY = "io.qent.broxy/catalog"

@Serializable
data class CatalogRegistryIndex(
    val schemaVersion: Int = 1,
    val servers: List<CatalogRegistryServerRef> = emptyList(),
)

@Serializable
data class CatalogRegistryServerRef(
    val id: String,
    val path: String,
)

@Serializable
data class CatalogBundle(
    val source: String? = null,
    val updatedAtEpochMillis: Long? = null,
    val servers: List<CatalogServerDetail> = emptyList(),
)

@Serializable
data class CatalogServerDetail(
    val name: String,
    val description: String,
    val version: String,
    val title: String? = null,
    val websiteUrl: String? = null,
    val icons: List<CatalogIcon> = emptyList(),
    val packages: List<CatalogPackage> = emptyList(),
    val remotes: List<CatalogRemoteTransport> = emptyList(),
    val repository: CatalogRepositoryMetadata? = null,
    @SerialName("_meta")
    val meta: JsonObject? = null,
) {
    fun displayName(): String = title?.trim()?.takeIf { it.isNotEmpty() } ?: name

    fun iconUrl(): String? =
        icons
            .firstOrNull()
            ?.src
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    fun capabilities(): List<String> {
        val root = meta ?: return emptyList()
        val ext = root[CATALOG_META_KEY] as? JsonObject ?: return emptyList()
        val capabilities = ext["capabilities"] as? JsonArray ?: return emptyList()
        return capabilities
            .mapNotNull { it as? JsonPrimitive }
            .mapNotNull { it.contentOrNull?.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    fun installSteps(): List<String> {
        val root = meta ?: return emptyList()
        val installSteps = root["install_steps"] as? JsonArray ?: return emptyList()
        return installSteps
            .mapNotNull { it as? JsonPrimitive }
            .mapNotNull { it.contentOrNull?.trim() }
            .filter { it.isNotEmpty() }
    }
}

@Serializable
data class CatalogIcon(
    val src: String,
    val mimeType: String? = null,
    val sizes: List<String> = emptyList(),
    val theme: String? = null,
)

@Serializable
data class CatalogPackage(
    val registryType: String,
    val identifier: String,
    val version: String? = null,
    val transport: CatalogLocalTransport,
    val runtimeHint: String? = null,
    val runtimeArguments: List<CatalogArgument> = emptyList(),
    val packageArguments: List<CatalogArgument> = emptyList(),
    val environmentVariables: List<CatalogKeyValueInput> = emptyList(),
)

@Serializable
data class CatalogRepositoryMetadata(
    val url: String,
    val source: String,
    val id: String? = null,
    val subfolder: String? = null,
)

@Serializable
data class CatalogRemoteTransport(
    val type: String,
    val url: String,
    val headers: List<CatalogKeyValueInput> = emptyList(),
    val oauth: CatalogRemoteOAuth? = null,
    val variables: Map<String, CatalogInput> = emptyMap(),
)

@Serializable
data class CatalogRemoteOAuth(
    val type: String = "oauth",
    val clientId: String? = null,
    val clientSecret: String? = null,
    val callbackPort: JsonElement? = null,
    val clientIdMetadataUrl: String? = null,
    val authServerMetadataUrl: String? = null,
    val redirectUri: String? = null,
    val clientName: String? = null,
    val tokenEndpointAuthMethod: String? = null,
    val authorizationServer: String? = null,
    val scopes: List<String>? = null,
    val allowDynamicRegistration: Boolean = true,
)

@Serializable
data class CatalogLocalTransport(
    val type: String,
    val url: String? = null,
    val headers: List<CatalogKeyValueInput> = emptyList(),
)

@Serializable
data class CatalogArgument(
    val type: String,
    val name: String? = null,
    val value: String? = null,
    val valueHint: String? = null,
    val description: String? = null,
    val default: String? = null,
    val placeholder: String? = null,
    val format: String? = null,
    val isRequired: Boolean = false,
    val isSecret: Boolean = false,
    val isRepeated: Boolean = false,
    val choices: List<String> = emptyList(),
    val variables: Map<String, CatalogInput> = emptyMap(),
)

@Serializable
data class CatalogInput(
    val value: String? = null,
    val description: String? = null,
    val default: String? = null,
    val placeholder: String? = null,
    val format: String? = null,
    val isRequired: Boolean = false,
    val isSecret: Boolean = false,
    val choices: List<String> = emptyList(),
)

@Serializable
data class CatalogKeyValueInput(
    val name: String,
    val value: String? = null,
    val description: String? = null,
    val default: String? = null,
    val placeholder: String? = null,
    val format: String? = null,
    val isRequired: Boolean = false,
    val isSecret: Boolean = false,
    val choices: List<String> = emptyList(),
    val variables: Map<String, CatalogInput> = emptyMap(),
)
