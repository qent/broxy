package io.qent.broxy.core.mcp.clients

import io.ktor.client.HttpClient
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
import io.qent.broxy.core.mcp.auth.OAuthManager
import io.qent.broxy.core.mcp.auth.OAuthState
import io.qent.broxy.core.mcp.auth.createDefaultHttpClient
import io.qent.broxy.core.mcp.auth.peekAuthorizationTimeoutMillis
import io.qent.broxy.core.models.AuthConfig
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Unified Ktor-based MCP client supporting SSE and WebSocket transports.
 */
@Suppress("LongParameterList")
class KtorMcpClient(
    private val mode: Mode,
    private val url: String,
    private val headersMap: Map<String, String> = emptyMap(),
    private val logger: Logger = ConsoleLogger,
    private val authConfig: AuthConfig? = null,
    private val authState: OAuthState? = null,
    private val connector: SdkConnector? = null,
    private val ignoreHttpsCertificateErrors: Boolean = false,
    private val oauthAuthorizerFactory: (AuthConfig.OAuth, OAuthState, String, Logger) -> OAuthAuthorizer =
        { cfg, state, resourceUrl, log ->
            OAuthManager(
                cfg,
                state,
                resourceUrl,
                log,
                httpClientFactory = { createDefaultHttpClient(ignoreHttpsCertificateErrors) },
            )
        },
    private val preauthorizeWithConnector: Boolean = false,
    private val authorizationStatusListener: AuthorizationStatusListener? = null,
) : McpClient,
    TimeoutConfigurableMcpClient,
    AuthInteractiveMcpClient {
    enum class Mode { Sse, StreamableHttp, WebSocket }

    private var ktor: HttpClient? = null
    private var client: SdkClientFacade? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val oauthState: OAuthState = authState ?: OAuthState()
    private val authCoordinator =
        AuthCoordinator(
            headersMap = headersMap,
            authConfig = authConfig,
            authState = oauthState,
            oauthAuthorizerFactory = oauthAuthorizerFactory,
            authorizationStatusListener = authorizationStatusListener,
            logger = logger,
            url = url,
        )
    private val transportConnector =
        TransportConnector(
            mode = mode,
            url = url,
            headersMap = headersMap,
            logger = logger,
            authCoordinator = authCoordinator,
            ignoreHttpsCertificateErrors = ignoreHttpsCertificateErrors,
        )
    private val capabilityFetcher = CapabilityFetcher(logger)
    private val connectionManager = ConnectionManager()

    @Volatile
    private var connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS

    @Volatile
    private var capabilitiesTimeoutMillis: Long = DEFAULT_CAPABILITIES_TIMEOUT_MILLIS

    override fun updateTimeouts(
        connectTimeoutMillis: Long,
        capabilitiesTimeoutMillis: Long,
    ) {
        val connectTimeout = connectTimeoutMillis.coerceAtLeast(MIN_TIMEOUT_MILLIS)
        this.connectTimeoutMillis = connectTimeout
        this.capabilitiesTimeoutMillis = capabilitiesTimeoutMillis.coerceAtLeast(MIN_TIMEOUT_MILLIS)
        oauthState.peekAuthorizationTimeoutMillis()?.let { this.authorizationTimeoutMillis = it }
    }

    companion object {
        private const val MIN_TIMEOUT_MILLIS = 1L
        private const val NANOS_PER_MILLI = 1_000_000
        private const val AUTH_CONNECT_ATTEMPTS = 2
        private const val AUTH_OPERATION_ATTEMPTS = 2
        private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000L
        private const val DEFAULT_CAPABILITIES_TIMEOUT_MILLIS = 10_000L
        private const val DEFAULT_AUTHORIZATION_TIMEOUT_MILLIS = 180_000L
    }

    @Volatile
    override var authorizationTimeoutMillis: Long =
        oauthState.peekAuthorizationTimeoutMillis() ?: DEFAULT_AUTHORIZATION_TIMEOUT_MILLIS
        private set

    override suspend fun connect(): Result<Unit> = connectionManager.connect(allowAuthRetry = true)

    override suspend fun disconnect() {
        runCatching { client?.close() }
        runCatching { ktor?.close() }
        runCatching { authCoordinator.close() }
        client = null
        ktor = null
        logger.info("Closed Ktor MCP client ($mode) for $url")
    }

    override suspend fun fetchCapabilities(): Result<ServerCapabilities> =
        runCatching {
            logger.debug("KtorMcpClient fetchCapabilities start ($mode) url=$url")
            withAuthRetry("fetchCapabilities") {
                val c = checkNotNull(client) { "Not connected" }
                val timeoutMillis = capabilitiesTimeoutMillis.coerceAtLeast(MIN_TIMEOUT_MILLIS)
                val (tools, resources, prompts) = capabilityFetcher.fetch(c, timeoutMillis)
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
                val c = checkNotNull(client) { "Not connected" }
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
                val c = checkNotNull(client) { "Not connected" }
                val r = c.getPrompt(name, arguments)
                val el = Json.encodeToJsonElement(GetPromptResult.serializer(), r)
                el as JsonObject
            }
        }

    override suspend fun readResource(uri: String): Result<JsonObject> =
        runCatching {
            withAuthRetry("readResource") {
                val c = checkNotNull(client) { "Not connected" }
                val r = c.readResource(uri)
                val el = Json.encodeToJsonElement(ReadResourceResult.serializer(), r)
                el as JsonObject
            }
        }

    private inner class ConnectionManager {
        suspend fun connect(allowAuthRetry: Boolean): Result<Unit> =
            runCatching {
                logger.debug(
                    "KtorMcpClient connect start ($mode) url=$url allowAuthRetry=$allowAuthRetry " +
                        "preauthorizeWithConnector=$preauthorizeWithConnector",
                )
                val timer = ConnectTimer(mode, url, logger)
                if (client != null) return@runCatching
                preauthorizeIfNeeded()
                if (connectViaConnector(timer)) return@runCatching
                val maxAttempts = resolveConnectAttempts(allowAuthRetry)
                connectWithRetries(maxAttempts, allowAuthRetry, timer)
            }.onFailure { ex ->
                logger.error("Failed to connect Ktor MCP client ($mode) to $url", ex)
            }

        private suspend fun preauthorizeIfNeeded() {
            val allowPreauth = connector == null || preauthorizeWithConnector
            val authManager = authCoordinator.resolvePreauthManager(allowPreauth)
            if (authManager != null) {
                logger.debug("KtorMcpClient preauthorizing OAuth ($mode) url=$url")
                authCoordinator.ensureAuthorized(authManager)
                logger.debug("KtorMcpClient preauthorization complete ($mode) url=$url")
            }
        }

        private suspend fun connectViaConnector(timer: ConnectTimer): Boolean {
            val connector = connector ?: return false
            client = connector.connect()
            logger.info("Connected via test connector for Ktor client ($mode)")
            timer.logTotal()
            return true
        }

        private fun resolveConnectAttempts(allowAuthRetry: Boolean): Int =
            if (allowAuthRetry && authCoordinator.shouldRetryAuth()) {
                AUTH_CONNECT_ATTEMPTS
            } else {
                1
            }

        private suspend fun connectWithRetries(
            maxAttempts: Int,
            allowAuthRetry: Boolean,
            timer: ConnectTimer,
        ) {
            var lastError: Throwable? = null
            repeat(maxAttempts) { attempt ->
                authCoordinator.resetChallenge()
                val attemptStartNanos = System.nanoTime()
                logger.debug("KtorMcpClient connect attempt ${attempt + 1}/$maxAttempts ($mode) url=$url")
                val attemptResult = runCatching { connectOnce() }
                if (attemptResult.isSuccess) {
                    logger.info("Connected Ktor MCP client ($mode) to $url")
                    timer.logAttemptSuccess(attemptStartNanos, attempt + 1, maxAttempts)
                    timer.logTotal()
                    return
                }
                val error = attemptResult.exceptionOrNull()
                timer.logAttemptFailure(attemptStartNanos, attempt + 1, maxAttempts)
                lastError = error
                val challenge = authCoordinator.consumeChallenge()
                val shouldRetry =
                    allowAuthRetry &&
                        attempt < maxAttempts - 1 &&
                        authCoordinator.isAuthFailure(error, challenge)
                if (shouldRetry) {
                    val manager = authCoordinator.getOrCreateOAuthManager()
                    if (manager != null) {
                        logger.debug("KtorMcpClient auth failure detected; reauthorizing ($mode) url=$url")
                        authCoordinator.ensureAuthorized(manager, challenge)
                        disconnect()
                        return@repeat
                    }
                }
                if (error != null) {
                    throw error
                }
                error("Failed to connect Ktor MCP client ($mode)")
            }
            lastError?.let { throw it }
            error("Failed to connect Ktor MCP client ($mode)")
        }

        private suspend fun connectOnce() {
            val connectTimeout = connectTimeoutMillis.coerceAtLeast(MIN_TIMEOUT_MILLIS)
            logger.debug("KtorMcpClient connectOnce ($mode) url=$url timeout=${connectTimeout}ms")
            val connection = transportConnector.connect(connectTimeout)
            ktor = connection.httpClient
            client = connection.sdkClient
        }
    }

    private suspend fun <T> withAuthRetry(
        operation: String,
        block: suspend () -> T,
    ): T {
        val maxAttempts =
            if (authCoordinator.shouldRetryAuth()) {
                AUTH_OPERATION_ATTEMPTS
            } else {
                1
            }
        var lastError: Throwable? = null
        var attempt = 0
        var shouldRetry = true
        while (attempt < maxAttempts && shouldRetry) {
            authCoordinator.resetChallenge()
            val result = runCatching { block() }
            val challenge = authCoordinator.consumeChallenge()
            val authFailure = authCoordinator.isAuthFailure(result.exceptionOrNull(), challenge)
            if (authFailure) {
                logger.debug("KtorMcpClient auth failure during $operation ($mode) url=$url")
                val authError =
                    result.exceptionOrNull() ?: error("Unauthorized response during $operation")
                lastError = authError
                val canRetry = attempt < maxAttempts - 1
                val manager = if (canRetry) authCoordinator.getOrCreateOAuthManager() else null
                if (manager != null) {
                    reauthorizeAndReconnect(challenge)
                    shouldRetry = true
                } else {
                    shouldRetry = false
                }
            } else if (result.isSuccess) {
                return result.getOrThrow()
            } else {
                lastError = result.exceptionOrNull()
                shouldRetry = false
            }
            attempt += 1
        }
        lastError?.let { throw it }
        error("Failed $operation")
    }

    private suspend fun reauthorizeAndReconnect(challenge: OAuthChallenge?) {
        logger.debug("KtorMcpClient reauthorizeAndReconnect start ($mode) url=$url")
        val manager = authCoordinator.getOrCreateOAuthManager() ?: return
        authCoordinator.ensureAuthorized(manager, challenge)
        disconnect()
        connectionManager.connect(allowAuthRetry = false).getOrThrow()
    }

    private class ConnectTimer(
        private val mode: Mode,
        private val url: String,
        private val logger: Logger,
    ) {
        private val startNanos = System.nanoTime()

        fun logAttemptSuccess(
            attemptStartNanos: Long,
            attempt: Int,
            maxAttempts: Int,
        ) {
            val attemptMs = (System.nanoTime() - attemptStartNanos) / NANOS_PER_MILLI
            logger.debug(
                "KtorMcpClient connect attempt $attempt/$maxAttempts ($mode) " +
                    "url=$url elapsed=${attemptMs}ms",
            )
        }

        fun logAttemptFailure(
            attemptStartNanos: Long,
            attempt: Int,
            maxAttempts: Int,
        ) {
            val attemptMs = (System.nanoTime() - attemptStartNanos) / NANOS_PER_MILLI
            logger.debug(
                "KtorMcpClient connect attempt $attempt/$maxAttempts ($mode) " +
                    "url=$url failed after ${attemptMs}ms",
            )
        }

        fun logTotal() {
            val elapsedMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
            logger.debug("KtorMcpClient connect total ($mode) url=$url elapsed=${elapsedMs}ms")
        }
    }
}
