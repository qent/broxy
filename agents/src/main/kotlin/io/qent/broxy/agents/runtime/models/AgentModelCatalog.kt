package io.qent.broxy.agents.runtime.models

import io.qent.broxy.agents.AgentProviderSettings
import io.qent.broxy.agents.LlmProvider
import io.qent.broxy.agents.baseUrlFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.X509TrustManager

private const val ANTHROPIC_VERSION_HEADER_VALUE = "2023-06-01"
private const val HTTP_STATUS_MIN_SUCCESS = 200
private const val HTTP_STATUS_MAX_SUCCESS = 299
private const val ERROR_BODY_LIMIT = 256

interface AgentModelCatalog {
    suspend fun listModels(
        provider: LlmProvider,
        providerSettings: AgentProviderSettings,
        apiKey: String?,
        requestTimeoutSeconds: Int,
        ignoreHttpsCertificateErrors: Boolean,
    ): Result<List<String>>
}

class HttpAgentModelCatalog(
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
        },
) : AgentModelCatalog {
    override suspend fun listModels(
        provider: LlmProvider,
        providerSettings: AgentProviderSettings,
        apiKey: String?,
        requestTimeoutSeconds: Int,
        ignoreHttpsCertificateErrors: Boolean,
    ): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val timeout = Duration.ofSeconds(requestTimeoutSeconds.coerceAtLeast(1).toLong())
                val baseUrl = providerSettings.baseUrlFor(provider)
                val url = modelsUrl(provider, baseUrl)
                val requestBuilder =
                    HttpRequest
                        .newBuilder(URI(url))
                        .GET()
                        .timeout(timeout)
                        .header("Accept", "application/json")
                if (provider == LlmProvider.LM_STUDIO) {
                    requestBuilder.version(HttpClient.Version.HTTP_1_1)
                }
                when (provider) {
                    LlmProvider.OPENAI -> {
                        val key = requireProviderApiKey(provider, apiKey)
                        requestBuilder.header("Authorization", "Bearer $key")
                    }
                    LlmProvider.ANTHROPIC -> {
                        val key = requireProviderApiKey(provider, apiKey)
                        requestBuilder.header("x-api-key", key)
                        requestBuilder.header("anthropic-version", ANTHROPIC_VERSION_HEADER_VALUE)
                    }
                    LlmProvider.LM_STUDIO -> Unit
                }

                val client = buildHttpClient(timeout, ignoreHttpsCertificateErrors)
                val response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
                val statusCode = response.statusCode()
                val body = response.body().orEmpty()
                if (statusCode !in HTTP_STATUS_MIN_SUCCESS..HTTP_STATUS_MAX_SUCCESS) {
                    val snippet = body.trim().take(ERROR_BODY_LIMIT)
                    error("Provider models request failed ($statusCode): $snippet")
                }
                parseModelIds(body)
            }
        }

    private fun requireProviderApiKey(
        provider: LlmProvider,
        apiKey: String?,
    ): String =
        apiKey?.trim()?.takeIf { it.isNotBlank() }
            ?: error("Missing API key for provider $provider")

    private fun parseModelIds(body: String): List<String> {
        if (body.isBlank()) {
            return emptyList()
        }
        val root = json.parseToJsonElement(body)
        val dataItems =
            when (root) {
                is JsonObject -> {
                    when {
                        root["data"] is JsonArray -> root["data"]!!.jsonArray
                        root["models"] is JsonArray -> root["models"]!!.jsonArray
                        else -> JsonArray(emptyList())
                    }
                }
                is JsonArray -> root
                else -> JsonArray(emptyList())
            }

        return dataItems
            .mapNotNull(::extractModelId)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    private fun extractModelId(element: JsonElement): String? =
        when (element) {
            is JsonObject ->
                element["id"]?.jsonPrimitive?.contentOrNull
                    ?: element["name"]?.jsonPrimitive?.contentOrNull
                    ?: element["model"]?.jsonPrimitive?.contentOrNull
            else -> element.jsonPrimitive.contentOrNull
        }

    private fun modelsUrl(
        provider: LlmProvider,
        baseUrl: String,
    ): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return when (provider) {
            LlmProvider.OPENAI,
            LlmProvider.LM_STUDIO,
            -> if (trimmed.endsWith("/models", ignoreCase = true)) trimmed else "$trimmed/models"

            LlmProvider.ANTHROPIC ->
                when {
                    trimmed.endsWith("/models", ignoreCase = true) -> trimmed
                    trimmed.endsWith("/v1", ignoreCase = true) -> "$trimmed/models"
                    else -> "$trimmed/v1/models"
                }
        }
    }

    private fun buildHttpClient(
        timeout: Duration,
        ignoreHttpsCertificateErrors: Boolean,
    ): HttpClient {
        val builder = HttpClient.newBuilder().connectTimeout(timeout)
        if (!ignoreHttpsCertificateErrors) {
            return builder.build()
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(ModelCatalogTrustAllX509TrustManager), SecureRandom())
        val sslParameters = SSLParameters().apply { endpointIdentificationAlgorithm = "" }
        return builder.sslContext(sslContext).sslParameters(sslParameters).build()
    }
}

private object ModelCatalogTrustAllX509TrustManager : X509TrustManager {
    override fun checkClientTrusted(
        chain: Array<X509Certificate>,
        authType: String,
    ) = Unit

    override fun checkServerTrusted(
        chain: Array<X509Certificate>,
        authType: String,
    ) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
