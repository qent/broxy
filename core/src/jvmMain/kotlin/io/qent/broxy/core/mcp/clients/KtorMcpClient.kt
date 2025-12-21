package io.qent.broxy.core.mcp.clients

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import io.ktor.http.HttpHeaders
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
) : McpClient, TimeoutConfigurableMcpClient, AuthInteractiveMcpClient {
    enum class Mode { Sse, StreamableHttp, WebSocket }

    private var ktor: HttpClient? = null
    private var client: SdkClientFacade? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val authChallengeRecorder = OAuthChallengeRecorder()
    private val oauthState: OAuthState = authState ?: OAuthState()
    private val autoOauthEnabled = authConfig == null
    private var oauthManager: OAuthAuthorizer? =
        (authConfig as? AuthConfig.OAuth)?.let { cfg ->
            oauthAuthorizerFactory(cfg, oauthState, resolveOAuthResourceUrl(url), logger)
        }

    @Volatile
    private var connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS

    @Volatile
    private var capabilitiesTimeoutMillis: Long = DEFAULT_CAPABILITIES_TIMEOUT_MILLIS

    override fun updateTimeouts(
        connectTimeoutMillis: Long,
        capabilitiesTimeoutMillis: Long,
    ) {
        this.connectTimeoutMillis = connectTimeoutMillis.coerceAtLeast(1)
        this.capabilitiesTimeoutMillis = capabilitiesTimeoutMillis.coerceAtLeast(1)
    }

    companion object {
        private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000L
        private const val DEFAULT_CAPABILITIES_TIMEOUT_MILLIS = 10_000L
        private const val DEFAULT_AUTHORIZATION_TIMEOUT_MILLIS = 180_000L
    }

    override val authorizationTimeoutMillis: Long
        get() = DEFAULT_AUTHORIZATION_TIMEOUT_MILLIS

    override suspend fun connect(): Result<Unit> = connectInternal(allowAuthRetry = true)

    private suspend fun connectInternal(allowAuthRetry: Boolean): Result<Unit> =
        runCatching {
            if (client != null) return@runCatching
            val allowPreauth = connector == null || preauthorizeWithConnector
            val authManager =
                if (allowPreauth) {
                    resolveAuthManager()
                } else {
                    null
                }
            authManager?.ensureAuthorized()?.getOrThrow()
            // Tests can inject a fake connector
            connector?.let {
                client = it.connect()
                logger.info("Connected via test connector for Ktor client ($mode)")
                return@runCatching
            }

            val maxAttempts =
                if (allowAuthRetry && (oauthManager != null || autoOauthEnabled)) {
                    2
                } else {
                    1
                }
            var lastError: Throwable? = null
            repeat(maxAttempts) { attempt ->
                authChallengeRecorder.reset()
                try {
                    connectOnce()
                    logger.info("Connected Ktor MCP client ($mode) to $url")
                    return@runCatching
                } catch (ex: Throwable) {
                    lastError = ex
                    val challenge = authChallengeRecorder.consume()
                    if (allowAuthRetry && attempt < maxAttempts - 1 && isAuthFailure(ex, challenge)) {
                        val manager = oauthManager ?: getOrCreateOAuthManager()
                        if (manager != null) {
                            manager.ensureAuthorized(challenge).getOrThrow()
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
            withAuthRetry("fetchCapabilities") {
                val c = client ?: throw IllegalStateException("Not connected")
                val timeoutMillis = capabilitiesTimeoutMillis.coerceAtLeast(1)
                val tools = listWithTimeout("listTools", timeoutMillis, emptyList()) { c.getTools() }
                val resources = listWithTimeout("listResources", timeoutMillis, emptyList()) { c.getResources() }
                val prompts = listWithTimeout("listPrompts", timeoutMillis, emptyList()) { c.getPrompts() }
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

    private fun createHttpClient(connectTimeout: Long): HttpClient =
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

    private fun buildRequestBuilder(): HttpRequestBuilder.() -> Unit =
        {
            val token = oauthManager?.currentAccessToken() ?: oauthState.token?.accessToken
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
            if (oauthManager != null || autoOauthEnabled) {
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
        val manager = oauthManager ?: getOrCreateOAuthManager() ?: return
        manager.ensureAuthorized(challenge).getOrThrow()
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
        if (!autoOauthEnabled) return null
        return getOrCreateOAuthManager()
    }

    private fun getOrCreateOAuthManager(): OAuthAuthorizer? {
        oauthManager?.let { return it }
        if (!autoOauthEnabled) return null
        val manager = oauthAuthorizerFactory(AuthConfig.OAuth(), oauthState, resolveOAuthResourceUrl(url), logger)
        oauthManager = manager
        return manager
    }

    private suspend fun <T> listWithTimeout(
        operation: String,
        timeoutMillis: Long,
        defaultValue: T,
        fetch: suspend () -> T,
    ): T {
        return runCatching {
            withTimeout(timeoutMillis) { fetch() }
        }.onFailure { ex ->
            val kind =
                if (ex is TimeoutCancellationException) {
                    "timed out after ${timeoutMillis}ms"
                } else {
                    ex.message
                        ?: ex::class.simpleName
                }
            logger.warn("$operation $kind; treating as empty.", ex)
        }.getOrDefault(defaultValue)
    }
}
