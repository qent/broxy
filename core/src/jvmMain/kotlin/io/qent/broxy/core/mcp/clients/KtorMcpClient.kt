package io.qent.broxy.core.mcp.clients

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import io.modelcontextprotocol.kotlin.sdk.client.mcpSse
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.client.mcpWebSocket
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.qent.broxy.core.mcp.AuthInteractiveMcpClient
import io.qent.broxy.core.mcp.McpClient
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.TimeoutConfigurableMcpClient
import io.qent.broxy.core.mcp.auth.AuthorizationStatusListener
import io.qent.broxy.core.mcp.auth.OAuthAuthorizer
import io.qent.broxy.core.mcp.auth.OAuthChallenge
import io.qent.broxy.core.mcp.auth.OAuthChallengeRecorder
import io.qent.broxy.core.mcp.auth.OAuthManager
import io.qent.broxy.core.mcp.auth.OAuthState
import io.qent.broxy.core.mcp.auth.resolveOAuthResourceUrl
import io.qent.broxy.core.models.AuthConfig
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration.Companion.seconds

/**
 * Unified Ktor-based MCP client supporting SSE and WebSocket transports.
 */
class KtorMcpClient(
    private val mode: Mode,
    private val url: String,
    private val headersMap: Map<String, String> = emptyMap(),
    private val logger: Logger = ConsoleLogger,
    private val authConfig: AuthConfig? = null,
    private val authState: OAuthState? = null,
    private val connector: SdkConnector? = null,
    private val oauthAuthorizerFactory: (AuthConfig.OAuth, OAuthState, String, Logger) -> OAuthAuthorizer =
        { cfg, state, resourceUrl, log -> OAuthManager(cfg, state, resourceUrl, log) },
    private val preauthorizeWithConnector: Boolean = false,
    private val authorizationStatusListener: AuthorizationStatusListener? = null,
) : McpClient, TimeoutConfigurableMcpClient, AuthInteractiveMcpClient {
    enum class Mode { Sse, StreamableHttp, WebSocket }

    private var ktor: HttpClient? = null
    private var client: SdkClientFacade? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val authChallengeRecorder = OAuthChallengeRecorder()
    private val oauthState: OAuthState = authState ?: OAuthState()
    private val hasExplicitAuthorizationHeader =
        headersMap.keys.any { it.equals(HttpHeaders.Authorization, ignoreCase = true) }
    private val oauthAllowed = !hasExplicitAuthorizationHeader
    private val autoOauthEnabled = oauthAllowed && authConfig == null
    private var oauthManager: OAuthAuthorizer? =
        if (oauthAllowed) {
            (authConfig as? AuthConfig.OAuth)?.let { cfg ->
                oauthAuthorizerFactory(cfg, oauthState, resolveOAuthResourceUrl(url), logger)
            }
        } else {
            null
        }

    @Volatile
    private var connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS

    @Volatile
    private var capabilitiesTimeoutMillis: Long = DEFAULT_CAPABILITIES_TIMEOUT_MILLIS

    override fun updateTimeouts(
        connectTimeoutMillis: Long,
        capabilitiesTimeoutMillis: Long,
    ) {
        val connectTimeout = connectTimeoutMillis.coerceAtLeast(1)
        this.connectTimeoutMillis = connectTimeout
        this.capabilitiesTimeoutMillis = capabilitiesTimeoutMillis.coerceAtLeast(1)
        oauthState.authorizationTimeoutMillis?.let { this.authorizationTimeoutMillis = it }
    }

    companion object {
        private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000L
        private const val DEFAULT_CAPABILITIES_TIMEOUT_MILLIS = 10_000L
        private const val DEFAULT_AUTHORIZATION_TIMEOUT_MILLIS = 180_000L
    }

    @Volatile
    override var authorizationTimeoutMillis: Long =
        oauthState.authorizationTimeoutMillis ?: DEFAULT_AUTHORIZATION_TIMEOUT_MILLIS
        private set

    override suspend fun connect(): Result<Unit> = connectInternal(allowAuthRetry = true)

    private suspend fun connectInternal(allowAuthRetry: Boolean): Result<Unit> =
        runCatching {
            logger.debug(
                "KtorMcpClient connect start ($mode) url=$url allowAuthRetry=$allowAuthRetry " +
                    "preauthorizeWithConnector=$preauthorizeWithConnector",
            )
            val connectStartNanos = System.nanoTime()

            fun logConnectTotal() {
                val elapsedMs = (System.nanoTime() - connectStartNanos) / 1_000_000
                logger.debug("KtorMcpClient connect total ($mode) url=$url elapsed=${elapsedMs}ms")
            }
            if (client != null) return@runCatching
            val allowPreauth = connector == null || preauthorizeWithConnector
            val authManager =
                if (allowPreauth) {
                    resolveAuthManager()
                } else {
                    null
                }
            if (authManager != null) {
                logger.debug("KtorMcpClient preauthorizing OAuth ($mode) url=$url")
            }
            authManager?.let { manager -> ensureAuthorized(manager) }
            if (authManager != null) {
                logger.debug("KtorMcpClient preauthorization complete ($mode) url=$url")
            }
            // Tests can inject a fake connector
            connector?.let {
                client = it.connect()
                logger.info("Connected via test connector for Ktor client ($mode)")
                logConnectTotal()
                return@runCatching
            }

            val maxAttempts =
                if (allowAuthRetry && oauthAllowed && (oauthManager != null || autoOauthEnabled)) {
                    2
                } else {
                    1
                }
            var lastError: Throwable? = null
            repeat(maxAttempts) { attempt ->
                authChallengeRecorder.reset()
                val attemptStartNanos = System.nanoTime()
                try {
                    logger.debug("KtorMcpClient connect attempt ${attempt + 1}/$maxAttempts ($mode) url=$url")
                    connectOnce()
                    logger.info("Connected Ktor MCP client ($mode) to $url")
                    val attemptMs = (System.nanoTime() - attemptStartNanos) / 1_000_000
                    logger.debug(
                        "KtorMcpClient connect attempt ${attempt + 1}/$maxAttempts ($mode) " +
                            "url=$url elapsed=${attemptMs}ms",
                    )
                    logConnectTotal()
                    return@runCatching
                } catch (ex: Throwable) {
                    val attemptMs = (System.nanoTime() - attemptStartNanos) / 1_000_000
                    logger.debug(
                        "KtorMcpClient connect attempt ${attempt + 1}/$maxAttempts ($mode) " +
                            "url=$url failed after ${attemptMs}ms",
                    )
                    lastError = ex
                    val challenge = authChallengeRecorder.consume()
                    if (allowAuthRetry && attempt < maxAttempts - 1 && isAuthFailure(ex, challenge)) {
                        val manager = oauthManager ?: getOrCreateOAuthManager()
                        if (manager != null) {
                            logger.debug("KtorMcpClient auth failure detected; reauthorizing ($mode) url=$url")
                            ensureAuthorized(manager, challenge)
                            disconnect()
                            return@repeat
                        }
                    }
                    throw ex
                }
            }
            throw lastError ?: IllegalStateException("Failed to connect Ktor MCP client ($mode)")
        }.onFailure { ex ->
            logger.error("Failed to connect Ktor MCP client ($mode) to $url", ex)
        }

    override suspend fun disconnect() {
        runCatching { client?.close() }
        runCatching { ktor?.close() }
        runCatching { oauthManager?.close() }
        client = null
        ktor = null
        logger.info("Closed Ktor MCP client ($mode) for $url")
    }

    override suspend fun fetchCapabilities(): Result<ServerCapabilities> =
        runCatching {
            logger.debug("KtorMcpClient fetchCapabilities start ($mode) url=$url")
            withAuthRetry("fetchCapabilities") {
                val c = client ?: throw IllegalStateException("Not connected")
                val timeoutMillis = capabilitiesTimeoutMillis.coerceAtLeast(1)
                val (tools, resources, prompts) =
                    coroutineScope {
                        val toolsDeferred =
                            async { listWithTimeout("listTools", timeoutMillis, emptyList()) { c.getTools() } }
                        val resourcesDeferred =
                            async { listWithTimeout("listResources", timeoutMillis, emptyList()) { c.getResources() } }
                        val promptsDeferred =
                            async { listWithTimeout("listPrompts", timeoutMillis, emptyList()) { c.getPrompts() } }
                        Triple(toolsDeferred.await(), resourcesDeferred.await(), promptsDeferred.await())
                    }
                logger.debug(
                    "KtorMcpClient fetchCapabilities done ($mode) url=$url " +
                        "tools=${tools.size} resources=${resources.size} prompts=${prompts.size}",
                )
                ServerCapabilities(tools = tools, resources = resources, prompts = prompts)
            }
        }

    override suspend fun callTool(
        name: String,
        arguments: JsonObject,
    ): Result<JsonElement> =
        runCatching {
            withAuthRetry("callTool:$name") {
                val c = client ?: throw IllegalStateException("Not connected")
                val result =
                    c.callTool(name, arguments) ?: CallToolResult(
                        content = emptyList(),
                        isError = false,
                        structuredContent = JsonObject(emptyMap()),
                        meta = JsonObject(emptyMap()),
                    )
                json.encodeToJsonElement(CallToolResult.serializer(), result) as JsonObject
            }
        }

    override suspend fun getPrompt(
        name: String,
        arguments: Map<String, String>?,
    ): Result<JsonObject> =
        runCatching {
            withAuthRetry("getPrompt:$name") {
                val c = client ?: throw IllegalStateException("Not connected")
                val r = c.getPrompt(name, arguments)
                val el = kotlinx.serialization.json.Json.encodeToJsonElement(GetPromptResult.serializer(), r)
                el as JsonObject
            }
        }

    override suspend fun readResource(uri: String): Result<JsonObject> =
        runCatching {
            withAuthRetry("readResource") {
                val c = client ?: throw IllegalStateException("Not connected")
                val r = c.readResource(uri)
                val el = kotlinx.serialization.json.Json.encodeToJsonElement(ReadResourceResult.serializer(), r)
                el as JsonObject
            }
        }

    private suspend fun connectOnce() {
        val connectTimeout = connectTimeoutMillis.coerceAtLeast(1)
        logger.debug("KtorMcpClient connectOnce ($mode) url=$url timeout=${connectTimeout}ms")
        ktor = createHttpClient(connectTimeout)
        val reqBuilder = buildRequestBuilder()
        val sdk =
            when (mode) {
                Mode.Sse ->
                    requireNotNull(ktor).mcpSse(
                        urlString = url,
                        reconnectionTime = 3.seconds,
                        requestBuilder = reqBuilder,
                    )

                Mode.StreamableHttp -> requireNotNull(ktor).mcpStreamableHttp(url = url, requestBuilder = reqBuilder)
                Mode.WebSocket -> requireNotNull(ktor).mcpWebSocket(urlString = url, requestBuilder = reqBuilder)
            }
        client = RealSdkClientFacade(sdk, logger)
    }

    private fun createHttpClient(connectTimeout: Long): HttpClient {
        val client =
            HttpClient(CIO) {
                if (mode == Mode.Sse || mode == Mode.StreamableHttp) install(SSE)
                if (mode == Mode.WebSocket) install(WebSockets)
                install(HttpTimeout) {
                    requestTimeoutMillis = connectTimeout
                    socketTimeoutMillis = connectTimeout
                    this.connectTimeoutMillis = connectTimeout
                }
                HttpResponseValidator {
                    validateResponse { response ->
                        authChallengeRecorder.record(response)
                    }
                }
            }
        client.plugin(HttpSend).intercept { request ->
            val startNanos = System.nanoTime()
            val urlLabel = request.url.build().toLogString()
            try {
                val call = execute(request)
                val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
                val status = call.response.status
                logger.debug(
                    "HTTP ${request.method.value} $urlLabel -> " +
                        "${status.value} ${status.description} in ${elapsedMs}ms",
                )
                call
            } catch (ex: Throwable) {
                val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
                logger.warn(
                    "HTTP ${request.method.value} $urlLabel failed after ${elapsedMs}ms",
                    ex,
                )
                throw ex
            }
        }
        return client
    }

    private fun buildRequestBuilder(): HttpRequestBuilder.() -> Unit =
        {
            val token =
                if (oauthAllowed) {
                    oauthManager?.currentAccessToken() ?: oauthState.token?.accessToken
                } else {
                    null
                }
            if (headersMap.isNotEmpty() || token != null) {
                headers {
                    headersMap.forEach { (k, v) -> append(k, v) }
                    if (token != null) {
                        remove(HttpHeaders.Authorization)
                        append(HttpHeaders.Authorization, "Bearer $token")
                    }
                }
            }
        }

    private suspend fun <T> withAuthRetry(
        operation: String,
        block: suspend () -> T,
    ): T {
        val maxAttempts =
            if (oauthAllowed && (oauthManager != null || autoOauthEnabled)) {
                2
            } else {
                1
            }
        var lastError: Throwable? = null
        repeat(maxAttempts) { attempt ->
            authChallengeRecorder.reset()
            val result = runCatching { block() }
            val challenge = authChallengeRecorder.consume()
            val authFailure = isAuthFailure(result.exceptionOrNull(), challenge)
            if (authFailure) {
                logger.debug("KtorMcpClient auth failure during $operation ($mode) url=$url")
                lastError =
                    result.exceptionOrNull()
                        ?: IllegalStateException("Unauthorized response during $operation")
                if (attempt < maxAttempts - 1) {
                    val manager = oauthManager ?: getOrCreateOAuthManager()
                    if (manager != null) {
                        reauthorizeAndReconnect(challenge)
                        return@repeat
                    }
                }
                throw lastError!!
            }
            if (result.isFailure) {
                throw result.exceptionOrNull()!!
            }
            return result.getOrThrow()
        }
        throw lastError ?: IllegalStateException("Failed $operation")
    }

    private suspend fun reauthorizeAndReconnect(challenge: OAuthChallenge?) {
        logger.debug("KtorMcpClient reauthorizeAndReconnect start ($mode) url=$url")
        val manager = oauthManager ?: getOrCreateOAuthManager() ?: return
        ensureAuthorized(manager, challenge)
        disconnect()
        connectInternal(allowAuthRetry = false).getOrThrow()
    }

    private fun isAuthFailure(
        error: Throwable?,
        challenge: OAuthChallenge?,
    ): Boolean {
        if (challenge != null && (challenge.statusCode == 401 || challenge.statusCode == 403)) return true
        return when (error) {
            is StreamableHttpError -> error.code == 401 || error.code == 403
            is SSEClientException -> error.response?.status?.value == 401 || error.response?.status?.value == 403
            is ResponseException -> error.response.status.value == 401 || error.response.status.value == 403
            else -> false
        }
    }

    private fun resolveAuthManager(): OAuthAuthorizer? {
        oauthManager?.let { return it }
        if (!oauthAllowed || !autoOauthEnabled) return null
        return getOrCreateOAuthManager()
    }

    private fun getOrCreateOAuthManager(): OAuthAuthorizer? {
        oauthManager?.let { return it }
        if (!oauthAllowed || !autoOauthEnabled) return null
        val manager = oauthAuthorizerFactory(AuthConfig.OAuth(), oauthState, resolveOAuthResourceUrl(url), logger)
        oauthManager = manager
        return manager
    }

    private suspend fun ensureAuthorized(
        manager: OAuthAuthorizer,
        challenge: OAuthChallenge? = null,
    ) {
        val listener = authorizationStatusListener
        if (listener == null) {
            manager.ensureAuthorized(challenge).getOrThrow()
            return
        }
        listener.onAuthorizationStart()
        try {
            manager.ensureAuthorized(challenge).getOrThrow()
        } finally {
            listener.onAuthorizationComplete()
        }
    }

    private suspend fun <T> listWithTimeout(
        operation: String,
        timeoutMillis: Long,
        defaultValue: T,
        fetch: suspend () -> T,
    ): T {
        val startNanos = System.nanoTime()
        return runCatching {
            withTimeout(timeoutMillis) { fetch() }
        }.onSuccess {
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
            logger.debug("$operation completed in ${elapsedMs}ms")
        }.onFailure { ex ->
            val kind =
                if (ex is TimeoutCancellationException) {
                    "timed out after ${timeoutMillis}ms"
                } else {
                    ex.message
                        ?: ex::class.simpleName
                }
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
            logger.warn("$operation $kind after ${elapsedMs}ms; treating as empty.", ex)
        }.getOrDefault(defaultValue)
    }

    private fun Url.toLogString(): String {
        val portPart = if (port != protocol.defaultPort && port > 0) ":$port" else ""
        val path = encodedPath.takeIf { it.isNotBlank() } ?: "/"
        return "${protocol.name}://$host$portPart$path"
    }
}
