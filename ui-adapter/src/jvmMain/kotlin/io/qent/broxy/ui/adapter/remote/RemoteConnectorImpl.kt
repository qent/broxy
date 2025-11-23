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
    private val callbackServer: LoopbackCallbackServer = LoopbackCallbackServer(),
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
                _state.value = loaded.toUi().copy(status = UiRemoteStatus.Registered)
                if (!loaded.wsToken.isNullOrBlank() && !isExpired(loaded.wsTokenExpiresAt)) {
                    connectWebSocket(loaded.wsToken)
                }
            }
        }
    }

    override fun updateServerIdentifier(value: String) {
        val normalized = normalizeIdentifier(value)
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
            _state.value = _state.value.copy(status = UiRemoteStatus.Authorizing, message = "Opening browser...")
            val login = runCatching { httpClient.get("$BASE_URL/auth/mcp/login").body<LoginResponse>() }
                .onFailure { logger.error("[RemoteAuth] login failed: ${it.message}") }
                .getOrElse {
                    _state.value = _state.value.copy(status = UiRemoteStatus.Error, message = "Login failed: ${it.message}")
                    return@launch
                }
            openBrowser(login.authorizationUrl)
            val callback: OAuthCallback = callbackServer.awaitCallback(login.state)
                ?: run {
                    _state.value = _state.value.copy(status = UiRemoteStatus.Error, message = "Authorization timed out")
                    return@launch
                }
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
            val email = extractEmail(tokenResp.accessToken)
            val accessExpiry = parseInstant(tokenResp.expiresAt)
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
                _state.value = _state.value.copy(status = UiRemoteStatus.Error, message = reason)
            }
        )
        wsClient = client
        val result = client.connect()
        if (result.isFailure) {
            _state.value = _state.value.copy(
                status = UiRemoteStatus.WsOffline,
                message = result.exceptionOrNull()?.message
            )
        }
    }

    private suspend fun registerServer(accessToken: String): RegisterResponse? = runCatching {
        _state.value = _state.value.copy(status = UiRemoteStatus.Registering, message = "Registering server")
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
}

private fun defaultHttpClient(): HttpClient = HttpClient {
    install(WebSockets)
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}
