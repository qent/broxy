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
    private val authorizationPresenter: AuthorizationPresenter? = AuthorizationPresenterRegistry.current(),
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

    override suspend fun ensureAuthorized(challenge: OAuthChallenge?): Result<String?> {
        val presenter = authorizationPresenter
        var shouldNotifyPresenter = false
        return runCatching {
            state.mutex.withLock {
                logger.debug("OAuth ensureAuthorized start for $resourceUrl (challenge=${challenge != null})")
                val requiredScope = challenge?.scope ?: state.lastRequestedScope
                val now = clockMillis()
                val token = state.token
                if (token != null && !isExpired(token, now) && tokenSatisfiesScope(token, requiredScope)) {
                    logger.debug("OAuth token valid for $resourceUrl; skipping authorization")
                    return@withLock token.accessToken
                }
                if (token != null && token.refreshToken != null && isExpired(token, now)) {
                    logger.debug("OAuth token expired; attempting refresh for $resourceUrl")
                    val refreshed = refreshToken(token.refreshToken, requiredScope)
                    if (refreshed != null && tokenSatisfiesScope(refreshed, requiredScope)) {
                        state.token = refreshed
                        logger.debug("OAuth token refresh succeeded for $resourceUrl")
                        return@withLock refreshed.accessToken
                    }
                    logger.debug("OAuth token refresh failed or insufficient scope for $resourceUrl")
                }

                val discovery = discoverMetadata(challenge)
                if (discovery == null) {
                    logger.info("OAuth metadata not available for $resourceUrl; skipping auth.")
                    return@withLock null
                }

                val scope = selectScope(challenge?.scope, discovery.resourceMetadata?.scopesSupported, config.scopes)
                state.lastRequestedScope = scope
                logger.debug("OAuth scope selected for $resourceUrl: ${scope ?: "none"}")
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
                shouldNotifyPresenter = presenter != null
                if (presenter != null) {
                    runCatching {
                        presenter.onAuthorizationRequest(
                            AuthorizationRequest(
                                resourceUrl = resourceUrl,
                                authorizationUrl = authUrl,
                                redirectUri = redirectUri,
                            ),
                        )
                    }.onFailure { ex ->
                        logger.warn("OAuth presenter failed to open authorization UI for $resourceUrl", ex)
                        throw ex
                    }
                } else {
                    logger.debug("OAuth authorization URL prepared for $resourceUrl; launching browser.")
                    browserLauncher.open(authUrl).onSuccess {
                        logger.debug("OAuth browser launch succeeded for $resourceUrl")
                    }.onFailure {
                        logger.info("Open this URL to authorize access: $authUrl")
                    }
                }
                val code =
                    try {
                        val timeoutMillis =
                            if (presenter != null) {
                                0L
                            } else {
                                resolveAuthorizationTimeoutMillis()
                            }
                        val timeoutLabel = if (timeoutMillis <= 0L) "none" else "${timeoutMillis}ms"
                        logger.debug("OAuth awaiting authorization code for $resourceUrl timeout=$timeoutLabel")
                        receiver.awaitCode(authUrl, stateValue, timeoutMillis).getOrThrow()
                    } finally {
                        receiver.close()
                    }
                logger.debug("OAuth authorization code received for $resourceUrl; exchanging token.")
                val exchanged =
                    exchangeAuthorizationCode(
                        discovery.authorizationMetadata,
                        registration,
                        code,
                        redirectUri,
                        codeVerifier,
                    )
                state.token = exchanged
                logger.debug("OAuth token exchange complete for $resourceUrl")
                if (presenter != null && shouldNotifyPresenter) {
                    runCatching {
                        presenter.onAuthorizationResult(AuthorizationResult.Success(resourceUrl))
                    }.onFailure { ex ->
                        logger.warn("OAuth presenter failed to report success for $resourceUrl", ex)
                    }
                }
                exchanged.accessToken
            }
        }.onFailure { ex ->
            if (presenter != null && shouldNotifyPresenter) {
                val result =
                    when (ex) {
                        is kotlinx.coroutines.CancellationException ->
                            AuthorizationResult.Cancelled(resourceUrl, ex.message)
                        else -> AuthorizationResult.Failure(resourceUrl, ex.message)
                    }
                runCatching {
                    presenter.onAuthorizationResult(result)
                }.onFailure { notifyError ->
                    logger.warn("OAuth presenter failed to report failure for $resourceUrl", notifyError)
                }
            }
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
        logger.debug("OAuth discovery start for $resourceUrl (force=$force)")
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
                logger.debug("OAuth using cached authorization metadata for $resourceUrl")
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
            logger.debug("OAuth fetching protected resource metadata from $url")
            val response =
                client.get(url) {
                    headers { append(HttpHeaders.Accept, ContentType.Application.Json.toString()) }
                }
            if (!response.status.isSuccess()) continue
            val body = response.bodyAsText()
            val metadata = json.decodeFromString(ProtectedResourceMetadata.serializer(), body)
            state.resourceMetadataUrl = url
            logger.debug("OAuth protected resource metadata loaded from $url")
            return metadata
        }
        logger.debug("OAuth protected resource metadata not found for $resourceUrl")
        return null
    }

    private suspend fun discoverAuthorizationServerMetadata(
        client: HttpClient,
        authorizationServers: List<String>,
    ): Pair<String, AuthorizationServerMetadata> {
        val failures = mutableListOf<String>()
        for (issuer in authorizationServers) {
            logger.debug("OAuth discovery probing authorization server $issuer")
            for (candidate in buildAuthorizationServerMetadataUrls(issuer)) {
                logger.debug("OAuth fetching authorization server metadata from $candidate")
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
                logger.debug("OAuth authorization server metadata resolved from $candidate")
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
            logger.debug("OAuth using configured client_id for $resourceUrl")
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
            logger.debug("OAuth using client_id metadata URL for $resourceUrl")
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
            state.registration?.let { existing ->
                val registeredRedirect = state.registeredRedirectUri
                if (registeredRedirect.isNullOrBlank() || registeredRedirect == redirectUri) {
                    logger.debug("OAuth reusing cached dynamic registration for $resourceUrl")
                    return existing
                }
            }
            logger.debug("OAuth starting dynamic client registration for $resourceUrl")
            val registration = registerDynamicClient(authMeta.registrationEndpoint, redirectUri)
            state.registration = registration
            state.registeredRedirectUri = redirectUri
            return registration
        }
        error("No OAuth client registration available; configure clientId or clientIdMetadataUrl.")
    }

    private suspend fun registerDynamicClient(
        registrationEndpoint: String,
        redirectUri: String,
    ): OAuthClientRegistration {
        logger.debug("OAuth dynamic client registration request to $registrationEndpoint")
        val authMethod = resolveTokenEndpointAuthMethod(config.tokenEndpointAuthMethod, null, null)
        val payload =
            buildJsonObject {
                put("client_name", JsonPrimitive(config.clientName ?: "Broxy"))
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
        logger.debug("OAuth dynamic client registration succeeded for $resourceUrl")
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
        logger.debug("OAuth exchanging authorization code for token at ${authMeta.tokenEndpoint}")
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
        logger.debug("OAuth token endpoint response received for $resourceUrl")
        return toToken(json.decodeFromString(OAuthTokenResponse.serializer(), body))
    }

    private suspend fun refreshToken(
        refreshToken: String,
        scope: String?,
    ): OAuthToken? {
        val registration = state.registration ?: return null
        logger.debug("OAuth refreshing token for $resourceUrl")
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
        if (!response.status.isSuccess()) {
            logger.debug("OAuth refresh token request failed: ${response.status}")
            return null
        }
        val body = response.bodyAsText()
        logger.debug("OAuth refresh token response received for $resourceUrl")
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
        val tokenScope = token.scope ?: return true
        val required = requiredScope.split(" ").filter { it.isNotBlank() }.toSet()
        val available = tokenScope.split(" ").filter { it.isNotBlank() }.toSet()
        return available.containsAll(required)
    }

    private fun resolveAuthorizationTimeoutMillis(): Long {
        val configured = state.authorizationTimeoutMillis
        return if (configured != null && configured > 0) configured else DEFAULT_AUTH_CODE_TIMEOUT_MILLIS
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
