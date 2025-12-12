package io.qent.broxy.ui.adapter.remote

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.parseQueryString
import io.ktor.serialization.kotlinx.json.json
import io.qent.broxy.core.proxy.runtime.ProxyLifecycle
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.ui.adapter.models.UiRemoteConnectionState
import io.qent.broxy.ui.adapter.models.UiRemoteStatus
import io.qent.broxy.ui.adapter.remote.auth.LoopbackCallbackServer
import io.qent.broxy.ui.adapter.remote.auth.OAuthCallback
import io.qent.broxy.ui.adapter.remote.net.CallbackRequest
import io.qent.broxy.ui.adapter.remote.net.LoginResponse
import io.qent.broxy.ui.adapter.remote.net.RegisterRequest
import io.qent.broxy.ui.adapter.remote.net.RegisterResponse
import io.qent.broxy.ui.adapter.remote.net.TokenResponse
import io.qent.broxy.ui.adapter.remote.storage.LoadedRemoteConfig
import io.qent.broxy.ui.adapter.remote.storage.RemoteConfigStore
import io.qent.broxy.ui.adapter.remote.storage.SecureStorageProvider
import io.qent.broxy.ui.adapter.remote.storage.SecureStore
import io.qent.broxy.ui.adapter.remote.ws.RemoteWsClient
import io.qent.broxy.ui.adapter.remote.ws.RemoteWsClientFactory
import io.qent.broxy.ui.adapter.remote.defaultRemoteState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI
import java.nio.file.Paths
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.coroutines.CoroutineContext
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val BASE_URL = "https://broxy.run"
private const val WS_PATH = "/ws"
private const val WS_BASE_URL = "wss://broxy.run"
private const val REDIRECT_URI = "http://127.0.0.1:${LoopbackCallbackServer.DEFAULT_PORT}/oauth/callback"

class RemoteConnectorImpl(
    private val logger: CollectingLogger,
    private val proxyLifecycle: ProxyLifecycle,
    private val scope: CoroutineScope,
    private val ioContext: CoroutineContext = Dispatchers.IO,
    private val httpClient: HttpClient = defaultHttpClient(),
    private val secureStore: SecureStore = SecureStorageProvider(
        fallbackDir = Paths.get(System.getProperty("user.home"), ".config", "broxy", "secrets")
    ).provide(),
    private val configStore: RemoteConfigStore = RemoteConfigStore(
        file = Paths.get(System.getProperty("user.home"), ".config", "broxy", "remote.json"),
        secureStore = secureStore
    ),
    private val now: () -> Instant = { Instant.now() },
    private val callbackServer: LoopbackCallbackServer = LoopbackCallbackServer(logger = logger),
    private val wsFactory: RemoteWsClientFactory = RemoteWsClientFactory()
) : RemoteConnector {

    private val jsonParser = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(defaultRemoteState())
    override val state: StateFlow<UiRemoteConnectionState> = _state

    private var cachedConfig: LoadedRemoteConfig? = null
    @Volatile
    private var proxyRunning: Boolean = false
    private var wsClient: RemoteWsClient? = null

    override fun start() {
        scope.launch(ioContext) {
            proxyRunning = proxyLifecycle.isRunning()
            cachedConfig = configStore.load()
            val loaded = cachedConfig
            if (loaded != null) {
                logger.info("[RemoteAuth] Loaded cached remote config: server=${loaded.serverIdentifier}, email=${loaded.email}, accessExp=${loaded.accessTokenExpiresAt}, wsExp=${loaded.wsTokenExpiresAt}")
                logTokenSnapshot("Loaded cached tokens", loaded.accessToken, loaded.wsToken)
                if (!hasValidAccessToken(loaded) && !hasValidWsToken(loaded)) {
                    logger.warn("[RemoteAuth] Cached credentials are expired; clearing and waiting for authorization")
                    cachedConfig = null
                    configStore.clear()
                    _state.value = defaultRemoteState().copy(serverIdentifier = loaded.serverIdentifier)
                    return@launch
                }
                _state.value = loaded.toUi().copy(
                    status = if (hasCredentials(loaded)) UiRemoteStatus.Registered else UiRemoteStatus.NotAuthorized,
                    message = null
                )
                if (hasCredentials(loaded) && proxyRunning) {
                    logger.info("[RemoteAuth] Cached credentials found; attempting auto-connect")
                    connectWithCachedTokens(auto = true)
                } else if (hasCredentials(loaded)) {
                    logger.info("[RemoteAuth] Cached credentials found; waiting for MCP proxy to start before connecting")
                }
            } else {
                logger.debug("[RemoteAuth] No cached remote config found; awaiting manual authorization")
            }
        }
    }

    override fun updateServerIdentifier(value: String) {
        val normalized = normalizeIdentifier(value)
        logger.info("[RemoteAuth] Updating server identifier to '$normalized'")
        _state.value = _state.value.copy(
            serverIdentifier = normalized,
            status = UiRemoteStatus.NotAuthorized,
            hasCredentials = false,
            message = null,
            email = null
        )
        scope.launch(ioContext) {
            disconnectInternal(clearCredentials = false, reason = null)
            cachedConfig = null
            val current = _state.value
            configStore.clear()
            configStore.save(
                serverIdentifier = current.serverIdentifier,
                email = null,
                accessToken = null,
                accessTokenExpiresAt = null,
                wsToken = null,
                wsTokenExpiresAt = null
            )
        }
    }

    override fun beginAuthorization() {
        if (_state.value.status == UiRemoteStatus.Authorizing) return
        scope.launch(ioContext) {
            logger.info("[RemoteAuth] Starting Google OAuth flow for server='${_state.value.serverIdentifier}'")
            _state.value = _state.value.copy(status = UiRemoteStatus.Authorizing, message = "Opening browser...")
            val login = runCatching {
                logger.info("[RemoteAuth] oauth_authorization_requested audience=mcp redirect_uri=$REDIRECT_URI")
                httpClient.get("$BASE_URL/auth/mcp/login") {
                    url {
                        parameters.append("redirect_uri", REDIRECT_URI)
                    }
                }.body<LoginResponse>()
            }
                .onFailure { logger.error("[RemoteAuth] login failed: ${it.message}") }
                .getOrElse {
                    _state.value = _state.value.copy(status = UiRemoteStatus.Error, message = "Login failed: ${it.message}")
                    return@launch
                }
            logger.debug("[RemoteAuth] Login response received: state=${login.state}, redirect=${login.authorizationUrl}")
            val redirectInAuthUrl = runCatching {
                val uri = URI(login.authorizationUrl)
                parseQueryString(uri.rawQuery ?: "").getAll("redirect_uri")?.firstOrNull()
            }.getOrNull()
            if (redirectInAuthUrl == null) {
                val msg = "Authorization URL missing redirect_uri; backend may be outdated or redirect_uri not forwarded"
                logger.error("[RemoteAuth] $msg")
                _state.value = _state.value.copy(status = UiRemoteStatus.Error, message = msg)
                return@launch
            }
            if (redirectInAuthUrl != REDIRECT_URI) {
                val msg = "Authorization URL redirect_uri mismatch; expected $REDIRECT_URI but got $redirectInAuthUrl"
                logger.error("[RemoteAuth] $msg")
                _state.value = _state.value.copy(status = UiRemoteStatus.Error, message = msg)
                return@launch
            }
            logger.info("[RemoteAuth] Opening browser for OAuth consent")
            openBrowser(login.authorizationUrl)
            logger.debug("[RemoteAuth] Awaiting OAuth callback for state=${login.state}")
            val callbackResult = runCatching { callbackServer.awaitCallback(login.state) }
            val callback: OAuthCallback = callbackResult.getOrNull()
                ?: run {
                    val msg = callbackResult.exceptionOrNull()?.let { "Callback server failed: ${it.message}" }
                        ?: "Authorization timed out"
                    logger.error("[RemoteAuth] Authorization failed before callback: $msg", callbackResult.exceptionOrNull())
                    _state.value = _state.value.copy(status = UiRemoteStatus.Error, message = msg)
                    return@launch
                }
            logger.info("[RemoteAuth] OAuth callback received; state=${callback.state}, codeLen=${callback.code.length}")
            val tokenResp = runCatching {
                httpClient.post("$BASE_URL/auth/mcp/callback") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        CallbackRequest(
                            code = callback.code,
                            state = callback.state,
                            audience = "mcp",
                            redirectUri = REDIRECT_URI
                        )
                    )
                }.body<TokenResponse>()
            }.onFailure { logger.error("[RemoteAuth] callback exchange failed: ${it.message}") }
                .getOrElse {
                    _state.value = _state.value.copy(status = UiRemoteStatus.Error, message = "Exchange failed: ${it.message}")
                    return@launch
                }
            if (!tokenResp.tokenType.equals("bearer", ignoreCase = true)) {
                logger.error("[RemoteAuth] Invalid token_type '${tokenResp.tokenType}' received; expected bearer")
                _state.value = _state.value.copy(status = UiRemoteStatus.Error, message = "Invalid token type")
                return@launch
            }
            if (!tokenResp.scope.equals("mcp", ignoreCase = true)) {
                logger.error("[RemoteAuth] Invalid scope '${tokenResp.scope}' received; expected mcp")
                _state.value = _state.value.copy(status = UiRemoteStatus.Error, message = "Invalid token scope")
                return@launch
            }
            logger.info("[RemoteAuth] oauth_code_exchange audience=mcp status=success")
            logger.info("[RemoteAuth] Exchanged OAuth code for access token; expiresAt=${tokenResp.expiresAt}")
            val email = extractEmail(tokenResp.accessToken)
            if (email != null) {
                logger.info("[RemoteAuth] Extracted user email from token: $email")
            } else {
                logger.warn("[RemoteAuth] Failed to extract email from access token payload")
            }
            val accessExpiry = parseInstant(tokenResp.expiresAt)
            logTokenSnapshot("Access token received", tokenResp.accessToken, null)
            val register = registerServer(tokenResp.accessToken)
            if (register == null) {
                _state.value = _state.value.copy(status = UiRemoteStatus.Error, message = "Register failed", hasCredentials = false)
                return@launch
            }
            val wsExpiry = accessExpiry?.plusSeconds(24 * 3600) // backend default, best effort
            persistConfig(
                serverIdentifier = _state.value.serverIdentifier,
                email = email,
                accessToken = tokenResp.accessToken,
                accessTokenExpiresAt = accessExpiry,
                wsToken = register.jwtToken,
                wsTokenExpiresAt = wsExpiry
            )
            logger.info("[RemoteAuth] Remote config saved; accessExp=$accessExpiry, wsExp=$wsExpiry, email=$email")
            logTokenSnapshot("Persisted tokens", tokenResp.accessToken, register.jwtToken)
            _state.value = _state.value.copy(
                status = UiRemoteStatus.Registered,
                message = null,
                email = email,
                hasCredentials = true
            )
            proxyRunning = proxyLifecycle.isRunning()
            if (proxyRunning) {
                connectWebSocket(register.jwtToken)
            } else {
                logger.info("[RemoteAuth] Authorization complete; waiting for MCP proxy to start before connecting")
            }
        }
    }

    override fun connect() {
        scope.launch(ioContext) {
            connectWithCachedTokens(auto = false)
        }
    }

    override fun disconnect() {
        scope.launch(ioContext) {
            disconnectInternal(clearCredentials = false, reason = "Disconnected")
        }
    }

    override fun logout() {
        scope.launch(ioContext) {
            disconnectInternal(clearCredentials = true, reason = "Logged out")
        }
    }

    override fun onProxyRunningChanged(running: Boolean) {
        proxyRunning = running
        scope.launch(ioContext) {
            if (running) {
                connectWithCachedTokens(auto = true)
            } else {
                disconnectInternal(clearCredentials = false, reason = "MCP proxy stopped")
            }
        }
    }

    private suspend fun connectWithCachedTokens(auto: Boolean) {
        val config = loadCachedConfig()
        if (config == null || !hasCredentials(config)) {
            if (!auto) {
                _state.value = defaultRemoteState().copy(serverIdentifier = _state.value.serverIdentifier)
            }
            return
        }
        if (config.serverIdentifier != _state.value.serverIdentifier) {
            _state.value = _state.value.copy(serverIdentifier = config.serverIdentifier)
        }
        proxyRunning = proxyLifecycle.isRunning()
        if (!proxyRunning) {
            if (!auto) {
                _state.value = _state.value.copy(
                    status = UiRemoteStatus.Registered,
                    message = "Start MCP proxy to connect",
                    hasCredentials = true
                )
            }
            return
        }
        val wsToken = config.wsToken?.takeIf { hasValidWsToken(config) }
        if (wsToken != null) {
            _state.value = _state.value.copy(
                status = UiRemoteStatus.WsConnecting,
                message = null,
                hasCredentials = true
            )
            connectWebSocket(wsToken)
            return
        }
        if (!hasValidAccessToken(config)) {
            logger.warn("[RemoteAuth] Cached access token expired; requiring re-authorization")
            disconnectInternal(clearCredentials = true, reason = "Authorization expired")
            return
        }
        val register = registerServer(config.accessToken!!)
        if (register == null) {
            return
        }
        val wsExpiry = config.accessTokenExpiresAt?.plusSeconds(24 * 3600)
        persistConfig(
            serverIdentifier = config.serverIdentifier,
            email = config.email,
            accessToken = config.accessToken,
            accessTokenExpiresAt = config.accessTokenExpiresAt,
            wsToken = register.jwtToken,
            wsTokenExpiresAt = wsExpiry
        )
        _state.value = _state.value.copy(
            status = UiRemoteStatus.Registered,
            hasCredentials = true,
            email = config.email,
            message = null
        )
        connectWebSocket(register.jwtToken)
    }

    private suspend fun disconnectInternal(clearCredentials: Boolean, reason: String?) {
        runCatching { wsClient?.close() }
        wsClient = null
        val hasCreds = _state.value.hasCredentials
        if (clearCredentials || !hasCreds) {
            cachedConfig = null
            configStore.clear()
            _state.value = defaultRemoteState().copy(
                serverIdentifier = _state.value.serverIdentifier,
                message = reason
            )
            return
        }
        _state.value = _state.value.copy(
            status = UiRemoteStatus.WsOffline,
            message = reason,
            hasCredentials = true
        )
    }

    private suspend fun connectWebSocket(jwt: String) {
        if (!proxyLifecycle.isRunning()) {
            logger.warn("[RemoteAuth] Skipping WebSocket connect: MCP proxy is not running")
            _state.value = _state.value.copy(
                status = UiRemoteStatus.Registered,
                message = "Start MCP proxy to connect",
                hasCredentials = true
            )
            return
        }
        val proxyProvider = { proxyLifecycle.currentProxy() }
        val wsUrl = "$WS_BASE_URL$WS_PATH/${_state.value.serverIdentifier}"
        logger.info("[RemoteAuth] Initiating WebSocket connection to $wsUrl")
        wsClient?.close()
        val client = wsFactory.create(
            httpClient = httpClient,
            url = wsUrl,
            authToken = jwt,
            serverIdentifier = _state.value.serverIdentifier,
            proxyProvider = proxyProvider,
            logger = logger,
            scope = scope,
            onStatus = { status, message ->
                _state.value = _state.value.copy(status = status, message = message, hasCredentials = true)
            },
            onAuthFailure = { reason ->
                logger.error("[RemoteAuth] WebSocket authentication failed: $reason")
                _state.value = _state.value.copy(status = UiRemoteStatus.Error, message = reason, hasCredentials = true)
            }
        )
        wsClient = client
        val result = client.connect()
        if (result.isFailure) {
            logger.warn("[RemoteAuth] WebSocket connection failed: ${result.exceptionOrNull()?.message}")
            _state.value = _state.value.copy(
                status = UiRemoteStatus.WsOffline,
                message = result.exceptionOrNull()?.message,
                hasCredentials = true
            )
        } else {
            logger.info("[RemoteAuth] WebSocket connection established")
            logger.info("[RemoteAuth] websocket_online server_identifier=${_state.value.serverIdentifier}")
        }
    }

    private suspend fun registerServer(accessToken: String): RegisterResponse? = runCatching {
        _state.value = _state.value.copy(
            status = UiRemoteStatus.Registering,
            message = "Registering server",
            hasCredentials = true
        )
        logger.info("[RemoteAuth] Registering server '${_state.value.serverIdentifier}' with remote backend")
        httpClient.post("$BASE_URL/auth/mcp/register") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    serverIdentifier = _state.value.serverIdentifier,
                    name = _state.value.serverIdentifier,
                    capabilities = RegisterRequest.Capabilities(prompts = true, tools = true, resources = true)
                )
            )
        }.body<RegisterResponse>()
    }.onSuccess { response ->
        logger.info("[RemoteAuth] Register response: status=${response.status}, server=${response.serverIdentifier}")
        logger.info("[RemoteAuth] server_registered server_identifier=${response.serverIdentifier}")
        logTokenSnapshot("Received WebSocket JWT", null, response.jwtToken)
    }.onFailure {
        val msg = when (it) {
            is ClientRequestException -> {
                if (it.response.status.value == 403) "Server identifier is already in use"
                else "Register failed (${it.response.status.value})"
            }
            else -> it.message ?: "Register failed"
        }
        _state.value = _state.value.copy(status = UiRemoteStatus.Error, message = msg, hasCredentials = true)
        logger.error("[RemoteAuth] register failed: $msg")
    }.getOrNull()

    private fun loadCachedConfig(): LoadedRemoteConfig? {
        cachedConfig = cachedConfig ?: configStore.load()
        return cachedConfig
    }

    private fun hasCredentials(config: LoadedRemoteConfig?): Boolean =
        config != null && (!config.accessToken.isNullOrBlank() || !config.wsToken.isNullOrBlank())

    private fun hasValidAccessToken(config: LoadedRemoteConfig): Boolean =
        !config.accessToken.isNullOrBlank() && !isExpired(config.accessTokenExpiresAt)

    private fun hasValidWsToken(config: LoadedRemoteConfig): Boolean =
        !config.wsToken.isNullOrBlank() && !isExpired(config.wsTokenExpiresAt)

    private fun persistConfig(
        serverIdentifier: String,
        email: String?,
        accessToken: String?,
        accessTokenExpiresAt: Instant?,
        wsToken: String?,
        wsTokenExpiresAt: Instant?
    ) {
        cachedConfig = LoadedRemoteConfig(
            serverIdentifier = serverIdentifier,
            email = email,
            accessToken = accessToken,
            accessTokenExpiresAt = accessTokenExpiresAt,
            wsToken = wsToken,
            wsTokenExpiresAt = wsTokenExpiresAt
        )
        configStore.save(
            serverIdentifier = serverIdentifier,
            email = email,
            accessToken = accessToken,
            accessTokenExpiresAt = accessTokenExpiresAt,
            wsToken = wsToken,
            wsTokenExpiresAt = wsTokenExpiresAt
        )
    }

    private fun isExpired(expiry: Instant?): Boolean =
        expiry != null && now().isAfter(expiry.minusSeconds(60))

    private fun parseInstant(value: String?): Instant? = try {
        value?.let { Instant.parse(it) }
    } catch (_: DateTimeParseException) {
        null
    }

    private fun normalizeIdentifier(value: String): String {
        val normalized = value.lowercase(Locale.getDefault())
            .map { ch ->
                when {
                    ch.isLetterOrDigit() -> ch
                    ch == '-' || ch == '_' || ch == '.' -> '-'
                    else -> '-'
                }
            }
            .joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')
        return normalized.ifBlank { defaultRemoteServerIdentifier() }.take(64)
    }

    private fun extractEmail(token: String): String? = runCatching {
        val parts = token.split(".")
        if (parts.size < 2) return@runCatching null
        val payload = String(Base64.getUrlDecoder().decode(parts[1]))
        val json = jsonParser.parseToJsonElement(payload).jsonObject
        json["email"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private fun openBrowser(url: String) {
        runCatching {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI(url))
            }
        }.onFailure { logger.warn("[RemoteAuth] failed to open browser: ${it.message}") }
    }

    private fun logTokenSnapshot(context: String, accessToken: String?, wsToken: String?) {
        val access = redactToken(accessToken)
        val ws = redactToken(wsToken)
        logger.debug("[RemoteAuth] $context | access=$access, ws=$ws")
    }

    private fun redactToken(token: String?): String =
        token?.let {
            if (it.length <= 10) "***${it.length}chars***" else "${it.take(6)}...${it.takeLast(4)} (${it.length} chars)"
        } ?: "null"
}

private fun defaultHttpClient(): HttpClient = HttpClient {
    install(WebSockets) {
        pingIntervalMillis = 10000
    }
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}
