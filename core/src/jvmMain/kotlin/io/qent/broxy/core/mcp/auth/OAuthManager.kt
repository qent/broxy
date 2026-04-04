package io.qent.broxy.core.mcp.auth

import io.ktor.client.HttpClient
import io.qent.broxy.core.models.AuthConfig
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.security.SecureRandom

@Suppress("LongParameterList")
class OAuthManager(
    private val config: AuthConfig.OAuth,
    private val state: OAuthState,
    private val resourceUrl: String,
    private val logger: Logger,
    private val httpClientFactory: () -> HttpClient = { createDefaultHttpClient() },
    private val authorizationCodeReceiverFactory: (String?, String) -> AuthorizationCodeReceiver =
        { redirectUri, authResourceUrl ->
            LoopbackAuthorizationCodeReceiver(redirectUri, logger, authResourceUrl)
        },
    private val browserLauncher: BrowserLauncher = DesktopBrowserLauncher(logger),
    private val authorizationPresenter: AuthorizationPresenter? = AuthorizationPresenterRegistry.current(),
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) : OAuthAuthorizer {
    private data class AuthorizationFlow(
        val receiver: AuthorizationCodeReceiver,
        val redirectUri: String,
        val registration: OAuthClientRegistration,
        val authUrl: String,
        val codeVerifier: String,
        val stateValue: String,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()
    private var httpClient: HttpClient? = null
    private val discoveryService =
        OAuthDiscoveryService(
            config = config,
            state = state,
            resourceUrl = resourceUrl,
            logger = logger,
            json = json,
            httpClientProvider = ::httpClient,
        )
    private val registrationService =
        OAuthRegistrationService(
            config = config,
            state = state,
            resourceUrl = resourceUrl,
            logger = logger,
            json = json,
            httpClientProvider = ::httpClient,
        )
    private val tokenService =
        OAuthTokenService(
            state = state,
            resourceUrl = resourceUrl,
            logger = logger,
            json = json,
            httpClientProvider = ::httpClient,
            clockMillis = clockMillis,
        )

    override fun currentAccessToken(): String? = state.peekAccessToken()

    override suspend fun ensureAuthorized(challenge: OAuthChallenge?): Result<String?> {
        val presenter = authorizationPresenter
        var shouldNotifyPresenter = false
        return runCatching {
            state.mutex.withLock {
                logger.debug(
                    "OAuth ensureAuthorized start for $resourceUrl (challenge=${challenge != null})",
                )
                val requiredScope = challenge?.scope
                val invalidTokenChallenge = challenge?.error?.equals("invalid_token", ignoreCase = true) == true
                if (invalidTokenChallenge) {
                    logger.debug("OAuth challenge reported invalid_token for $resourceUrl; clearing cached token.")
                    state.token = null
                }
                val scopeHint = requiredScope ?: state.lastRequestedScope
                val nowMillis = clockMillis()
                var accessToken =
                    resolveAccessTokenLocked(
                        state,
                        requiredScope,
                        nowMillis,
                        logger,
                        resourceUrl,
                        { token, _ -> tokenService.refreshToken(token, scopeHint) },
                    )
                if (accessToken == null) {
                    val discovery = discoveryService.discover(challenge)
                    if (discovery == null) {
                        logger.info("OAuth metadata not available for $resourceUrl; skipping auth.")
                    } else {
                        shouldNotifyPresenter = presenter != null
                        accessToken = authorizeWithDiscovery(presenter, discovery, scopeHint)
                    }
                }
                accessToken
            }
        }.onFailure { ex ->
            if (shouldNotifyPresenter && presenter != null) {
                val result =
                    when (ex) {
                        is CancellationException ->
                            AuthorizationResult.Cancelled(resourceUrl, ex.message)
                        else -> AuthorizationResult.Failure(resourceUrl, ex.message)
                    }
                runCatching {
                    presenter.onAuthorizationResult(result)
                }.onFailure { notifyError ->
                    logger.warn(
                        "OAuth presenter failed to report failure for $resourceUrl",
                        notifyError,
                    )
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

    private suspend fun authorizeWithDiscovery(
        presenter: AuthorizationPresenter?,
        discovery: OAuthDiscoveryResult,
        challengeScope: String?,
    ): String {
        val scope = selectScope(challengeScope, discovery.resourceMetadata?.scopesSupported, config.scopes)
        state.lastRequestedScope = scope
        logger.debug("OAuth scope selected for $resourceUrl: ${scope ?: "none"}")
        val flow = createAuthorizationFlow(discovery, scope)
        sendAuthorizationRequest(
            presenter,
            AuthorizationRequest(
                resourceUrl = resourceUrl,
                authorizationUrl = flow.authUrl,
                redirectUri = flow.redirectUri,
            ),
            browserLauncher,
            logger,
            resourceUrl,
        )
        val timeoutMillis =
            if (presenter == null) resolveAuthorizationTimeoutMillis(state.authorizationTimeoutMillis) else 0L
        val code =
            awaitAuthorizationCode(
                flow.receiver,
                flow.authUrl,
                flow.stateValue,
                timeoutMillis,
                logger,
                resourceUrl,
            )
        logger.debug("OAuth authorization code received for $resourceUrl; exchanging token.")
        val exchanged =
            tokenService.exchangeAuthorizationCode(
                discovery.authorizationMetadata,
                flow.registration,
                code,
                flow.redirectUri,
                flow.codeVerifier,
            )
        state.token = exchanged
        logger.debug("OAuth token exchange complete for $resourceUrl")
        if (presenter != null) {
            runCatching {
                presenter.onAuthorizationResult(
                    AuthorizationResult.Success(resourceUrl),
                )
            }.onFailure { ex ->
                logger.warn(
                    "OAuth presenter failed to report success for $resourceUrl",
                    ex,
                )
            }
        }
        return exchanged.accessToken
    }

    private suspend fun createAuthorizationFlow(
        discovery: OAuthDiscoveryResult,
        scope: String?,
    ): AuthorizationFlow {
        val codeVerifier = generateCodeVerifier(random)
        val codeChallenge = generateCodeChallenge(codeVerifier)
        val stateValue = generateState(random)
        val receiver = authorizationCodeReceiverFactory(resolveRedirectUriOverride(config), resourceUrl)
        val redirectUri = receiver.redirectUri
        val registration = registrationService.resolveRegistration(discovery.authorizationMetadata, redirectUri)
        val resourceUri = canonicalResourceUri(resourceUrl, discovery.resourceMetadata)
        val authUrl =
            buildAuthorizationUrl(
                discovery.authorizationMetadata.authorizationEndpoint,
                registration.clientId,
                redirectUri,
                scope,
                stateValue,
                codeChallenge,
                resourceUri,
            )
        return AuthorizationFlow(
            receiver = receiver,
            redirectUri = redirectUri,
            registration = registration,
            authUrl = authUrl,
            codeVerifier = codeVerifier,
            stateValue = stateValue,
        )
    }
}

private fun resolveRedirectUriOverride(config: AuthConfig.OAuth): String? {
    val explicit = config.redirectUri?.takeIf { it.isNotBlank() }
    val fromPort = config.callbackPort?.let { callbackPort -> "http://localhost:$callbackPort/callback" }
    return explicit ?: fromPort
}
