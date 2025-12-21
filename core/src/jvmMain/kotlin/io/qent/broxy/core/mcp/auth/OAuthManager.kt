package io.qent.broxy.core.mcp.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.qent.broxy.core.models.AuthConfig
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.math.max

private const val DEFAULT_OAUTH_TIMEOUT_MILLIS = 30_000L
private const val DEFAULT_AUTH_CODE_TIMEOUT_MILLIS = 120_000L
private const val EXPIRY_SKEW_MILLIS = 30_000L

class OAuthManager(
    private val config: AuthConfig.OAuth,
    private val state: OAuthState,
    private val resourceUrl: String,
    private val logger: Logger,
    private val httpClientFactory: () -> HttpClient = { createDefaultHttpClient() },
    private val authorizationCodeReceiverFactory: (String?) -> AuthorizationCodeReceiver = { redirectUri ->
        LoopbackAuthorizationCodeReceiver(redirectUri, logger)
    },
    private val browserLauncher: BrowserLauncher = DesktopBrowserLauncher(logger),
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) : OAuthAuthorizer {
    private data class DiscoveryResult(
        val resourceMetadata: ProtectedResourceMetadata?,
        val authorizationMetadata: AuthorizationServerMetadata,
        val authorizationServer: String,
    )

    @Serializable
    private data class OAuthTokenResponse(
        @SerialName("access_token")
        val accessToken: String,
        @SerialName("token_type")
        val tokenType: String? = null,
        @SerialName("refresh_token")
        val refreshToken: String? = null,
        @SerialName("expires_in")
        val expiresIn: Long? = null,
        val scope: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()
    private var httpClient: HttpClient? = null

    override fun currentAccessToken(): String? = state.token?.accessToken

    override suspend fun ensureAuthorized(challenge: OAuthChallenge?): Result<String?> =
        runCatching {
            state.mutex.withLock {
                val requiredScope = challenge?.scope ?: state.lastRequestedScope
                val now = clockMillis()
                val token = state.token
                if (token != null && !isExpired(token, now) && tokenSatisfiesScope(token, requiredScope)) {
                    return@withLock token.accessToken
                }
                if (token != null && token.refreshToken != null && isExpired(token, now)) {
                    val refreshed = refreshToken(token.refreshToken, requiredScope)
                    if (refreshed != null && tokenSatisfiesScope(refreshed, requiredScope)) {
                        state.token = refreshed
                        return@withLock refreshed.accessToken
                    }
                }

                val discovery = discoverMetadata(challenge)
                if (discovery == null) {
                    logger.info("OAuth metadata not available for $resourceUrl; skipping auth.")
                    return@withLock null
                }

                val scope = selectScope(challenge?.scope, discovery.resourceMetadata?.scopesSupported, config.scopes)
                state.lastRequestedScope = scope
                val codeVerifier = generateCodeVerifier()
                val codeChallenge = generateCodeChallenge(codeVerifier)
                val stateValue = generateState()

                val receiver = authorizationCodeReceiverFactory(config.redirectUri)
                val redirectUri = receiver.redirectUri
                val registration = resolveRegistration(discovery.authorizationMetadata, redirectUri)
                val authUrl =
                    buildAuthorizationUrl(
                        discovery.authorizationMetadata.authorizationEndpoint,
                        registration.clientId,
                        redirectUri,
                        scope,
                        stateValue,
                        codeChallenge,
                    )
                browserLauncher.open(authUrl).onFailure {
                    logger.info("Open this URL to authorize access: $authUrl")
                }
                val code =
                    try {
                        receiver.awaitCode(authUrl, stateValue, DEFAULT_AUTH_CODE_TIMEOUT_MILLIS).getOrThrow()
                    } finally {
                        receiver.close()
                    }
                val exchanged =
                    exchangeAuthorizationCode(
                        discovery.authorizationMetadata,
                        registration,
                        code,
                        redirectUri,
                        codeVerifier,
                    )
                state.token = exchanged
                exchanged.accessToken
            }
        }

    override fun close() {
        runCatching { httpClient?.close() }
        httpClient = null
    }

    private fun httpClient(): HttpClient {
        val existing = httpClient
        if (existing != null) return existing
        val created = httpClientFactory()
        httpClient = created
        return created
    }

    private suspend fun discoverMetadata(challenge: OAuthChallenge?): DiscoveryResult? {
        val client = httpClient()
        val force = challenge != null
        val resourceMetadata =
            state.resourceMetadata ?: fetchProtectedResourceMetadata(client, challenge).also {
                if (it != null) {
                    state.resourceMetadata = it
                }
            }

        if (resourceMetadata == null && config.authorizationServer.isNullOrBlank()) {
            if (force) {
                error("Protected resource metadata not found for $resourceUrl")
            }
            return null
        }

        val authorizationServers =
            resourceMetadata?.authorizationServers?.filter { it.isNotBlank() }.orEmpty().ifEmpty {
                listOfNotNull(config.authorizationServer?.takeIf { it.isNotBlank() })
            }
        if (authorizationServers.isEmpty()) {
            error("OAuth authorization server list is empty for $resourceUrl")
        }

        val cachedIssuer = state.authorizationServer
        val cachedMeta = state.authorizationMetadata
        val (issuer, authMeta) =
            if (!cachedIssuer.isNullOrBlank() && cachedMeta != null) {
                cachedIssuer to cachedMeta
            } else {
                discoverAuthorizationServerMetadata(client, authorizationServers)
            }
        state.authorizationServer = issuer
        state.authorizationMetadata = authMeta
        validatePkceSupport(authMeta)
        return DiscoveryResult(resourceMetadata, authMeta, issuer)
    }

    private suspend fun fetchProtectedResourceMetadata(
        client: HttpClient,
        challenge: OAuthChallenge?,
    ): ProtectedResourceMetadata? {
        val urls =
            when {
                !challenge?.resourceMetadataUrl.isNullOrBlank() -> listOf(challenge?.resourceMetadataUrl!!)
                !state.resourceMetadataUrl.isNullOrBlank() -> listOf(state.resourceMetadataUrl!!)
                else -> buildProtectedResourceMetadataUrls(resourceUrl)
            }
        for (url in urls) {
            val response =
                client.get(url) {
                    headers { append(HttpHeaders.Accept, ContentType.Application.Json.toString()) }
                }
            if (!response.status.isSuccess()) continue
            val body = response.bodyAsText()
            val metadata = json.decodeFromString(ProtectedResourceMetadata.serializer(), body)
            state.resourceMetadataUrl = url
            return metadata
        }
        return null
    }

    private suspend fun discoverAuthorizationServerMetadata(
        client: HttpClient,
        authorizationServers: List<String>,
    ): Pair<String, AuthorizationServerMetadata> {
        val failures = mutableListOf<String>()
        for (issuer in authorizationServers) {
            for (candidate in buildAuthorizationServerMetadataUrls(issuer)) {
                val response =
                    client.get(candidate) {
                        headers { append(HttpHeaders.Accept, ContentType.Application.Json.toString()) }
                    }
                if (!response.status.isSuccess()) {
                    failures.add("${response.status.value} $candidate")
                    continue
                }
                val body = response.bodyAsText()
                val metadata = json.decodeFromString(AuthorizationServerMetadata.serializer(), body)
                if (metadata.authorizationEndpoint.isNullOrBlank() || metadata.tokenEndpoint.isNullOrBlank()) {
                    failures.add("missing endpoints $candidate")
                    continue
                }
                return issuer to metadata
            }
        }
        error("Failed to discover OAuth authorization server metadata: ${failures.joinToString()}")
    }

    private suspend fun resolveRegistration(
        authMeta: AuthorizationServerMetadata,
        redirectUri: String,
    ): OAuthClientRegistration {
        config.clientId?.takeIf { it.isNotBlank() }?.let { clientId ->
            val registration =
                OAuthClientRegistration(
                    clientId = clientId,
                    clientSecret = config.clientSecret,
                    tokenEndpointAuthMethod =
                        resolveTokenEndpointAuthMethod(
                            config.tokenEndpointAuthMethod,
                            config.clientSecret,
                            authMeta.tokenEndpointAuthMethodsSupported,
                        ),
                )
            state.registration = registration
            return registration
        }
        config.clientIdMetadataUrl?.takeIf { it.isNotBlank() }?.let { metadataUrl ->
            if (authMeta.clientIdMetadataDocumentSupported != true) {
                error("Authorization server does not support client ID metadata documents.")
            }
            val registration =
                OAuthClientRegistration(
                    clientId = metadataUrl,
                    clientSecret = config.clientSecret,
                    tokenEndpointAuthMethod =
                        resolveTokenEndpointAuthMethod(
                            config.tokenEndpointAuthMethod,
                            config.clientSecret,
                            authMeta.tokenEndpointAuthMethodsSupported,
                        ),
                )
            state.registration = registration
            return registration
        }
        if (config.allowDynamicRegistration && !authMeta.registrationEndpoint.isNullOrBlank()) {
            if (config.redirectUri != null) {
                state.registration?.let { return it }
            }
            val registration = registerDynamicClient(authMeta.registrationEndpoint, redirectUri)
            if (config.redirectUri != null) {
                state.registration = registration
            }
            return registration
        }
        error("No OAuth client registration available; configure clientId or clientIdMetadataUrl.")
    }

    private suspend fun registerDynamicClient(
        registrationEndpoint: String,
        redirectUri: String,
    ): OAuthClientRegistration {
        val authMethod = resolveTokenEndpointAuthMethod(config.tokenEndpointAuthMethod, null, null)
        val payload =
            buildJsonObject {
                put("client_name", JsonPrimitive(config.clientName ?: "broxy"))
                putJsonArray("redirect_uris") { add(JsonPrimitive(redirectUri)) }
                putJsonArray("grant_types") { add(JsonPrimitive("authorization_code")) }
                putJsonArray("response_types") { add(JsonPrimitive("code")) }
                put("token_endpoint_auth_method", JsonPrimitive(authMethod))
            }
        val response =
            httpClient().post(registrationEndpoint) {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), payload))
            }
        if (!response.status.isSuccess()) {
            error("Dynamic client registration failed: ${response.status} ${response.bodyAsText()}")
        }
        val responseBody = response.bodyAsText()
        val registration = json.decodeFromString(DynamicClientRegistrationResponse.serializer(), responseBody)
        return OAuthClientRegistration(
            clientId = registration.clientId,
            clientSecret = registration.clientSecret,
            tokenEndpointAuthMethod = registration.tokenEndpointAuthMethod?.lowercase() ?: authMethod,
        )
    }

    @Serializable
    private data class DynamicClientRegistrationResponse(
        @SerialName("client_id")
        val clientId: String,
        @SerialName("client_secret")
        val clientSecret: String? = null,
        @SerialName("token_endpoint_auth_method")
        val tokenEndpointAuthMethod: String? = null,
    )

    private fun resolveTokenEndpointAuthMethod(
        configured: String?,
        clientSecret: String?,
        supported: List<String>?,
    ): String {
        val method =
            configured?.trim()?.lowercase()
                ?: if (!clientSecret.isNullOrBlank()) "client_secret_basic" else "none"
        if (supported != null && supported.none { it.equals(method, ignoreCase = true) }) {
            error("Token endpoint auth method '$method' is not supported by the authorization server.")
        }
        return method
    }

    private fun validatePkceSupport(metadata: AuthorizationServerMetadata) {
        val supported = metadata.codeChallengeMethodsSupported?.any { it.equals("S256", ignoreCase = true) } == true
        if (!supported) {
            error("Authorization server does not advertise PKCE S256 support.")
        }
    }

    private suspend fun exchangeAuthorizationCode(
        authMeta: AuthorizationServerMetadata,
        registration: OAuthClientRegistration,
        code: String,
        redirectUri: String,
        codeVerifier: String,
    ): OAuthToken {
        val params =
            Parameters.build {
                append("grant_type", "authorization_code")
                append("code", code)
                append("redirect_uri", redirectUri)
                append("client_id", registration.clientId)
                append("code_verifier", codeVerifier)
                append("resource", canonicalResourceUri())
                if (registration.tokenEndpointAuthMethod == "client_secret_post" &&
                    !registration.clientSecret.isNullOrBlank()
                ) {
                    append("client_secret", registration.clientSecret)
                }
            }
        val response =
            httpClient().submitForm(
                url = authMeta.tokenEndpoint ?: error("Missing token endpoint"),
                formParameters = params,
                encodeInQuery = false,
            ) {
                headers { append(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString()) }
                applyClientAuthHeaders(registration)
            }
        if (!response.status.isSuccess()) {
            error("Token request failed: ${response.status} ${response.bodyAsText()}")
        }
        val body = response.bodyAsText()
        return toToken(json.decodeFromString(OAuthTokenResponse.serializer(), body))
    }

    private suspend fun refreshToken(
        refreshToken: String,
        scope: String?,
    ): OAuthToken? {
        val registration = state.registration ?: return null
        val params =
            Parameters.build {
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
                append("client_id", registration.clientId)
                append("resource", canonicalResourceUri())
                if (!scope.isNullOrBlank()) append("scope", scope)
                if (registration.tokenEndpointAuthMethod == "client_secret_post" &&
                    !registration.clientSecret.isNullOrBlank()
                ) {
                    append("client_secret", registration.clientSecret)
                }
            }
        val response =
            httpClient().submitForm(
                url = state.authorizationMetadata?.tokenEndpoint ?: return null,
                formParameters = params,
                encodeInQuery = false,
            ) {
                headers { append(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString()) }
                applyClientAuthHeaders(registration)
            }
        if (!response.status.isSuccess()) return null
        val body = response.bodyAsText()
        return toToken(json.decodeFromString(OAuthTokenResponse.serializer(), body))
    }

    private fun HttpRequestBuilder.applyClientAuthHeaders(registration: OAuthClientRegistration) {
        if (registration.tokenEndpointAuthMethod == "client_secret_basic" && !registration.clientSecret.isNullOrBlank()) {
            val raw = "${registration.clientId}:${registration.clientSecret}"
            val encoded = Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
            headers { append(HttpHeaders.Authorization, "Basic $encoded") }
        }
    }

    private fun toToken(response: OAuthTokenResponse): OAuthToken {
        val now = clockMillis()
        val expiry = response.expiresIn?.let { now + max(0, it * 1_000L - EXPIRY_SKEW_MILLIS) }
        return OAuthToken(
            accessToken = response.accessToken,
            tokenType = response.tokenType ?: "Bearer",
            refreshToken = response.refreshToken,
            scope = response.scope,
            expiresAtEpochMillis = expiry,
        )
    }

    private fun selectScope(
        challengeScope: String?,
        scopesSupported: List<String>?,
        fallback: List<String>?,
    ): String? {
        if (!challengeScope.isNullOrBlank()) return challengeScope
        val supported = scopesSupported?.filter { it.isNotBlank() }?.joinToString(" ")
        if (!supported.isNullOrBlank()) return supported
        return fallback?.filter { it.isNotBlank() }?.joinToString(" ")
    }

    private fun isExpired(
        token: OAuthToken,
        nowMillis: Long,
    ): Boolean {
        val expiry = token.expiresAtEpochMillis ?: return false
        return nowMillis >= expiry
    }

    private fun tokenSatisfiesScope(
        token: OAuthToken,
        requiredScope: String?,
    ): Boolean {
        if (requiredScope.isNullOrBlank()) return true
        val tokenScope = token.scope ?: return false
        val required = requiredScope.split(" ").filter { it.isNotBlank() }.toSet()
        val available = tokenScope.split(" ").filter { it.isNotBlank() }.toSet()
        return available.containsAll(required)
    }

    private fun buildAuthorizationUrl(
        authorizationEndpoint: String?,
        clientId: String,
        redirectUri: String,
        scope: String?,
        state: String,
        codeChallenge: String,
    ): String {
        val endpoint = authorizationEndpoint ?: error("Missing authorization endpoint")
        val builder = URLBuilder(endpoint)
        builder.parameters.append("response_type", "code")
        builder.parameters.append("client_id", clientId)
        builder.parameters.append("redirect_uri", redirectUri)
        builder.parameters.append("state", state)
        builder.parameters.append("code_challenge", codeChallenge)
        builder.parameters.append("code_challenge_method", "S256")
        builder.parameters.append("resource", canonicalResourceUri())
        if (!scope.isNullOrBlank()) {
            builder.parameters.append("scope", scope)
        }
        return builder.buildString()
    }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashed = digest.digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed)
    }

    private fun generateState(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun canonicalResourceUri(): String {
        val rawResource = state.resourceMetadata?.resource?.takeIf { it.isNotBlank() } ?: resourceUrl
        val uri = URI(rawResource)
        val scheme = uri.scheme?.lowercase() ?: error("Resource URL is missing scheme: $rawResource")
        val host = uri.host?.lowercase() ?: error("Resource URL is missing host: $rawResource")
        var path = uri.path ?: ""
        if (path == "/") {
            path = ""
        } else if (path.endsWith("/")) {
            path = path.removeSuffix("/")
        }
        return URI(scheme, null, host, uri.port, if (path.isBlank()) null else path, null, null).toString()
    }

    private fun buildProtectedResourceMetadataUrls(resource: String): List<String> {
        val uri = URI(resource)
        val scheme = uri.scheme ?: error("Resource URL is missing scheme: $resource")
        val host = uri.host ?: error("Resource URL is missing host: $resource")
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val origin = "$scheme://$host$port"
        val path = uri.path?.takeIf { it.isNotBlank() && it != "/" } ?: ""
        val urls = mutableListOf<String>()
        if (path.isNotBlank()) {
            urls.add("$origin/.well-known/oauth-protected-resource$path")
        }
        urls.add("$origin/.well-known/oauth-protected-resource")
        return urls
    }

    private fun buildAuthorizationServerMetadataUrls(issuer: String): List<String> {
        val uri = URI(issuer)
        val scheme = uri.scheme ?: error("Authorization server URL is missing scheme: $issuer")
        val host = uri.host ?: error("Authorization server URL is missing host: $issuer")
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val origin = "$scheme://$host$port"
        val path = uri.path?.takeIf { it.isNotBlank() && it != "/" } ?: ""
        return if (path.isNotBlank()) {
            listOf(
                "$origin/.well-known/oauth-authorization-server$path",
                "$origin/.well-known/openid-configuration$path",
                "$origin$path/.well-known/openid-configuration",
            )
        } else {
            listOf(
                "$origin/.well-known/oauth-authorization-server",
                "$origin/.well-known/openid-configuration",
            )
        }
    }
}

private fun createDefaultHttpClient(): HttpClient =
    HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = DEFAULT_OAUTH_TIMEOUT_MILLIS
            socketTimeoutMillis = DEFAULT_OAUTH_TIMEOUT_MILLIS
            connectTimeoutMillis = DEFAULT_OAUTH_TIMEOUT_MILLIS
        }
    }
