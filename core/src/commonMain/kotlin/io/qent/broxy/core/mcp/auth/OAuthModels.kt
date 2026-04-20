package io.qent.broxy.core.mcp.auth

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class ProtectedResourceMetadata(
    @Serializable(with = ResourceUriSerializer::class)
    val resource: String? = null,
    @SerialName("resource_name")
    val resourceName: String? = null,
    @SerialName("authorization_servers")
    val authorizationServers: List<String> = emptyList(),
    @SerialName("scopes_supported")
    val scopesSupported: List<String>? = null,
)

/**
 * Some providers publish OAuth protected-resource metadata `resource` as either:
 * - string (RFC draft form)
 * - array of strings (provider-specific extension)
 *
 * We normalize both shapes to a single canonical string value and use the first non-blank
 * item when an array is returned.
 */
object ResourceUriSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ResourceUri", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        val jsonDecoder = decoder as? JsonDecoder
        if (jsonDecoder == null) {
            return runCatching { decoder.decodeString() }.getOrNull()
        }
        return when (val element = jsonDecoder.decodeJsonElement()) {
            JsonNull -> null
            is JsonPrimitive -> element.contentOrNull
            is JsonArray ->
                element
                    .asSequence()
                    .filterIsInstance<JsonPrimitive>()
                    .mapNotNull { it.contentOrNull?.trim() }
                    .firstOrNull { it.isNotBlank() }
            else -> null
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(
        encoder: Encoder,
        value: String?,
    ) {
        if (value == null) {
            encoder.encodeNull()
            return
        }
        encoder.encodeString(value)
    }
}

@Serializable
data class AuthorizationServerMetadata(
    val issuer: String? = null,
    @SerialName("authorization_endpoint")
    val authorizationEndpoint: String? = null,
    @SerialName("token_endpoint")
    val tokenEndpoint: String? = null,
    @SerialName("registration_endpoint")
    val registrationEndpoint: String? = null,
    @SerialName("code_challenge_methods_supported")
    val codeChallengeMethodsSupported: List<String>? = null,
    @SerialName("client_id_metadata_document_supported")
    val clientIdMetadataDocumentSupported: Boolean? = null,
    @SerialName("scopes_supported")
    val scopesSupported: List<String>? = null,
    @SerialName("token_endpoint_auth_methods_supported")
    val tokenEndpointAuthMethodsSupported: List<String>? = null,
)
