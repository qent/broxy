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

private const val BASE_URL = "https://mcp.broxy.run"
private const val WS_PATH = "/ws"
private const val WS_BASE_URL = "wss://mcp.broxy.run"
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

    private var wsClient: RemoteWsClient? = null

    override fun start() {
        scope.launch(ioContext) {
            val loaded = configStore.load()
            if (loaded != null) {
                logger.info("[RemoteAuth] Loaded cached remote config: server=${loaded.serverIdentifier}, email=${loaded.email}, accessExp=${loaded.accessTokenExpiresAt}, wsExp=${loaded.wsTokenExpiresAt}")
                logTokenSnapshot("Loaded cached tokens", loaded.accessToken, loaded.wsToken)
                _state.value = loaded.toUi().copy(status = UiRemoteStatus.Registered)
                if (!loaded.wsToken.isNullOrBlank() && !isExpired(loaded.wsTokenExpiresAt)) {
                    logger.info("[RemoteAuth] Reusing cached WebSocket token; attempting reconnect")
                    connectWebSocket(loaded.wsToken)
                } else {
                    logger.warn("[RemoteAuth] Cached WebSocket token missing or expired; waiting for authorization")
                }
            } else {
                logger.debug("[RemoteAuth] No cached remote config found; awaiting manual authorization")
            }
        }
    }

    override fun updateServerIdentifier(value: String) {
        val normalized = normalizeIdentifier(value)
        logger.info("[RemoteAuth] Updating server identifier to '$normalized'")
        _state.value = _state.value.copy(serverIdentifier = normalized)
        scope.launch(ioContext) {
            val current = _state.value
            configStore.clear()
            configStore.save(
                serverIdentifier = current.serverIdentifier,
                email = current.email,
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
            logger.info("[RemoteAuth] Opening browser for OAuth consent")
            openBrowser(login.authorizationUrl)
            logger.debug("[RemoteAuth] Awaiting OAuth callback for state=${login.state}")
            val callback: OAuthCallback = callbackServer.awaitCallback(login.state)
                ?: run {
                    logger.warn("[RemoteAuth] Authorization timed out before callback")
                    _state.value = _state.value.copy(status = UiRemoteStatus.Error, message = "Authorization timed out")
                    return@launch
                }
            logger.info("[RemoteAuth] OAuth callback received; state=${callback.state}, codeLen=${callback.code.length}")
            val tokenResp = runCatching {
                httpClient.post("$BASE_URL/auth/mcp/callback") {
                    contentType(ContentType.Application.Json)
                    setBody(CallbackRequest(code = callback.code, state = callback.state))
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
                _state.value = _state.value.copy(status = UiRemoteStatus.Error, message = "Register failed")
                return@launch
            }
            val wsExpiry = accessExpiry?.plusSeconds(24 * 3600) // backend default, best effort
            configStore.save(
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
                email = email
            )
            connectWebSocket(register.jwtToken)
        }
    }

    override fun disconnect() {
        scope.launch(ioContext) {
            runCatching { wsClient?.close() }
            wsClient = null
            configStore.clear()
            _state.value = defaultRemoteState().copy(serverIdentifier = _state.value.serverIdentifier)
        }
    }

    private suspend fun connectWebSocket(jwt: String) {
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
                _state.value = _state.value.copy(status = status, message = message)
            },
            onAuthFailure = { reason ->
                logger.error("[RemoteAuth] WebSocket authentication failed: $reason")
                _state.value = _state.value.copy(status = UiRemoteStatus.Error, message = reason)
            }
        )
        wsClient = client
        val result = client.connect()
        if (result.isFailure) {
            logger.warn("[RemoteAuth] WebSocket connection failed: ${result.exceptionOrNull()?.message}")
            _state.value = _state.value.copy(
                status = UiRemoteStatus.WsOffline,
                message = result.exceptionOrNull()?.message
            )
        } else {
            logger.info("[RemoteAuth] WebSocket connection established")
            logger.info("[RemoteAuth] websocket_online server_identifier=${_state.value.serverIdentifier}")
        }
    }

    private suspend fun registerServer(accessToken: String): RegisterResponse? = runCatching {
        _state.value = _state.value.copy(status = UiRemoteStatus.Registering, message = "Registering server")
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
        _state.value = _state.value.copy(status = UiRemoteStatus.Error, message = msg)
        logger.error("[RemoteAuth] register failed: $msg")
    }.getOrNull()

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
    install(WebSockets)
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}
