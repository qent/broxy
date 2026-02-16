package io.qent.broxy.core.mcp

import io.qent.broxy.core.mcp.auth.AuthorizationStatusListener
import io.qent.broxy.core.mcp.auth.OAuthState
import io.qent.broxy.core.mcp.auth.withLock
import io.qent.broxy.core.mcp.errors.McpError
import io.qent.broxy.core.models.AuthConfig
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.ExponentialBackoff
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Suppress("LongParameterList", "TooManyFunctions")
class DefaultMcpServerConnection(
    override val config: McpServerConfig,
    private val logger: Logger = ConsoleLogger,
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MILLIS,
    private var maxRetries: Int = DEFAULT_MAX_RETRIES,
    private var ignoreHttpsCertificateErrors: Boolean = false,
    private val authState: OAuthState? =
        when {
            config.auth is AuthConfig.OAuth -> OAuthState()
            config.auth == null && config.transport is TransportConfig.HttpTransport -> OAuthState()
            config.auth == null && config.transport is TransportConfig.StreamableHttpTransport -> OAuthState()
            config.auth == null && config.transport is TransportConfig.WebSocketTransport -> OAuthState()
            else -> null
        },
    private val authorizationStatusListener: AuthorizationStatusListener? = null,
    private val authStateObserver: ((OAuthState) -> Unit)? = null,
    private val clientFactory: () -> McpClient = {
        McpClientFactory(defaultMcpClientProvider()).create(
            config.transport,
            config.env,
            ignoreHttpsCertificateErrors,
            logger,
            config.auth,
            authState,
            authorizationStatusListener,
        )
    },
    private val cache: CapabilitiesCache = CapabilitiesCache(ttlMillis = cacheTtlMs),
    initialCallTimeoutMillis: Long = DEFAULT_CALL_TIMEOUT_MILLIS,
    initialCapabilitiesTimeoutMillis: Long = DEFAULT_CAPABILITIES_TIMEOUT_MILLIS,
    initialConnectTimeoutMillis: Long = initialCapabilitiesTimeoutMillis,
    initialAuthorizationTimeoutMillis: Long = DEFAULT_AUTHORIZATION_TIMEOUT_MILLIS,
) : McpServerConnection {
    private companion object {
        private const val DEFAULT_CACHE_TTL_MILLIS = 5 * 60 * 1_000L
        private const val DEFAULT_CALL_TIMEOUT_MILLIS = 60_000L
        private const val DEFAULT_CAPABILITIES_TIMEOUT_MILLIS = 10_000L
        private const val DEFAULT_MAX_RETRIES = 5
        private const val MIN_TIMEOUT_MILLIS = 1L
        private const val MIN_RETRY_COUNT = 1
        private const val DEFAULT_AUTHORIZATION_TIMEOUT_MILLIS = 120_000L
    }

    override val serverId: String = config.id

    @Volatile
    override var status: ServerStatus = ServerStatus.Stopped
        private set

    @Volatile
    private var callTimeoutMillis: Long = initialCallTimeoutMillis.coerceAtLeast(MIN_TIMEOUT_MILLIS)

    @Volatile
    private var capabilitiesTimeoutMillis: Long = initialCapabilitiesTimeoutMillis.coerceAtLeast(MIN_TIMEOUT_MILLIS)

    @Volatile
    private var connectTimeoutMillis: Long = initialConnectTimeoutMillis.coerceAtLeast(MIN_TIMEOUT_MILLIS)

    @Volatile
    private var authorizationTimeoutMillis: Long = initialAuthorizationTimeoutMillis.coerceAtLeast(MIN_TIMEOUT_MILLIS)

    init {
        maxRetries = maxRetries.coerceAtLeast(MIN_RETRY_COUNT)
        if (ignoreHttpsCertificateErrors && config.transport.isTlsCapableTransport()) {
            logger.warn(
                "HTTPS certificate validation is disabled for '${config.name}'. " +
                    "Use this only for trusted corporate/self-signed environments.",
            )
        }
        authState?.let { state ->
            runBlocking {
                state.withLock {
                    authorizationTimeoutMillis = this@DefaultMcpServerConnection.authorizationTimeoutMillis
                }
            }
        }
    }

    private val sessionRunner = SessionRunner()

    fun updateCallTimeout(millis: Long) {
        callTimeoutMillis = millis.coerceAtLeast(MIN_TIMEOUT_MILLIS)
        logger.info("Updated call timeout for '${config.name}' to ${callTimeoutMillis}ms")
    }

    fun updateCapabilitiesTimeout(millis: Long) {
        capabilitiesTimeoutMillis = millis.coerceAtLeast(MIN_TIMEOUT_MILLIS)
        connectTimeoutMillis = capabilitiesTimeoutMillis
        logger.info("Updated capabilities timeout for '${config.name}' to ${capabilitiesTimeoutMillis}ms")
    }

    fun updateConnectionRetryCount(count: Int) {
        maxRetries = count.coerceAtLeast(MIN_RETRY_COUNT)
        logger.info("Updated connection retries for '${config.name}' to $maxRetries")
    }

    fun updateIgnoreHttpsCertificateErrors(enabled: Boolean) {
        ignoreHttpsCertificateErrors = enabled
        if (enabled && config.transport.isTlsCapableTransport()) {
            logger.warn(
                "HTTPS certificate validation is disabled for '${config.name}'. " +
                    "Use this only for trusted corporate/self-signed environments.",
            )
        }
    }

    fun updateAuthorizationTimeout(millis: Long) {
        authorizationTimeoutMillis = millis.coerceAtLeast(MIN_TIMEOUT_MILLIS)
        authState?.let { state ->
            runBlocking {
                state.withLock {
                    authorizationTimeoutMillis = this@DefaultMcpServerConnection.authorizationTimeoutMillis
                }
            }
        }
        logger.info("Updated authorization timeout for '${config.name}' to ${authorizationTimeoutMillis}ms")
    }

    suspend fun warmCapabilitiesCache(
        capabilities: ServerCapabilities,
        ageMillis: Long,
    ) {
        cache.warm(capabilities, ageMillis)
    }

    override suspend fun connect(): Result<Unit> = sessionRunner.runSession { Result.success(Unit) }

    override suspend fun disconnect() {
        status = ServerStatus.Stopped
    }

    override suspend fun getCapabilities(forceRefresh: Boolean): Result<ServerCapabilities> {
        val cached = if (forceRefresh) null else cache.get()
        val result =
            if (cached != null) {
                Result.success(cached)
            } else {
                logger.debug("Fetching capabilities for '${config.name}' (forceRefresh=$forceRefresh)")
                val fetchResult =
                    sessionRunner.runSession { client ->
                        // Don't wrap with timeout here - fetchCapabilities() already has per-operation timeouts
                        // This prevents the outer timeout from killing the entire operation when individual
                        // operations (tools, prompts, resources) take time but succeed individually
                        client.fetchCapabilities()
                    }
                if (fetchResult.isSuccess) {
                    val caps = fetchResult.getOrThrow()
                    cache.put(caps)
                    logger.info(
                        "Successfully fetched capabilities for '${config.name}': " +
                            "${caps.tools.size} tools, ${caps.resources.size} resources, " +
                            "${caps.prompts.size} prompts",
                    )
                    Result.success(caps)
                } else {
                    val lastError = fetchResult.exceptionOrNull()
                    logger.error("Failed to fetch capabilities for '${config.name}'", lastError)
                    val fallback = cache.get()
                    if (fallback != null) {
                        logger.warn("Using cached capabilities for '${config.name}'", lastError)
                        Result.success(fallback)
                    } else {
                        Result.failure(lastError ?: McpError.TransportError("Unknown error fetching capabilities"))
                    }
                }
            }
        return result
    }

    override suspend fun callTool(
        toolName: String,
        arguments: JsonObject,
    ): Result<JsonElement> =
        sessionRunner.runTimedSession(
            timeoutMillis = callTimeoutMillis,
            timeoutMessage = "Tool '$toolName' timed out after ${callTimeoutMillis}ms",
            timeoutLogMessage = "Timed out calling tool '$toolName' on '${config.name}'",
        ) { client -> client.callTool(toolName, arguments) }

    override suspend fun getPrompt(
        name: String,
        arguments: Map<String, String>?,
    ): Result<JsonObject> =
        sessionRunner.runTimedSession(
            timeoutMillis = callTimeoutMillis,
            timeoutMessage = "Prompt '$name' timed out after ${callTimeoutMillis}ms",
            timeoutLogMessage = "Timed out fetching prompt '$name' from '${config.name}'",
        ) { client -> client.getPrompt(name, arguments) }

    override suspend fun readResource(uri: String): Result<JsonObject> =
        sessionRunner.runTimedSession(
            timeoutMillis = callTimeoutMillis,
            timeoutMessage = "Resource '$uri' timed out after ${callTimeoutMillis}ms",
            timeoutLogMessage = "Timed out reading resource '$uri' from '${config.name}'",
        ) { client -> client.readResource(uri) }

    private inner class SessionRunner {
        private fun newClient(): McpClient = clientFactory().also { configureClientTimeouts(it) }

        private fun configureClientTimeouts(client: McpClient) {
            (client as? TimeoutConfigurableMcpClient)?.updateTimeouts(connectTimeoutMillis, capabilitiesTimeoutMillis)
        }

        suspend fun <T> runSession(block: suspend (McpClient) -> Result<T>): Result<T> {
            val client = newClient()
            logger.debug("Opening MCP session for '${config.name}'")
            status = ServerStatus.Starting
            val connectResult = connectClient(client)
            if (connectResult.isFailure) {
                val error = connectResult.exceptionOrNull() ?: McpError.ConnectionError("Failed to connect", null)
                status = ServerStatus.Error(error.message)
                return finalizeSession(client, Result.failure(error))
            }
            status = ServerStatus.Running
            val blockResult = runCatching { block(client) }
            val result =
                blockResult.exceptionOrNull()?.let { ex ->
                    if (ex is CancellationException) throw ex
                    Result.failure(ex)
                } ?: blockResult.getOrThrow()
            if (result.isFailure) {
                status = ServerStatus.Error(result.exceptionOrNull()?.message)
            }
            return finalizeSession(client, result)
        }

        suspend fun <T> runTimedSession(
            timeoutMillis: Long,
            timeoutMessage: String,
            timeoutLogMessage: String,
            block: suspend (McpClient) -> Result<T>,
        ): Result<T> {
            val resolvedTimeout = timeoutMillis.coerceAtLeast(MIN_TIMEOUT_MILLIS)
            return runSession { client ->
                try {
                    withTimeout(resolvedTimeout) { block(client) }
                } catch (t: TimeoutCancellationException) {
                    val err = McpError.TimeoutError(timeoutMessage, t)
                    logger.error(timeoutLogMessage, t)
                    Result.failure(err)
                }
            }
        }

        private suspend fun connectClient(client: McpClient): Result<Unit> {
            val backoff = ExponentialBackoff()
            var lastError: Throwable? = null
            val isAuthInteractive = client is AuthInteractiveMcpClient
            var finalResult: Result<Unit>? = null
            var attempt = 1
            while (attempt <= maxRetries && finalResult == null) {
                val timeoutMillis = connectTimeoutMillis
                logger.debug(
                    "Connecting to '${config.name}' (attempt $attempt/$maxRetries, timeout=${timeoutMillis}ms)",
                )
                val result = attemptConnect(client, timeoutMillis, isAuthInteractive)
                if (result.isSuccess) {
                    finalResult = Result.success(Unit)
                } else {
                    lastError = result.exceptionOrNull()
                    finalResult = handleConnectFailure(lastError, attempt, backoff)
                }
                attempt += 1
            }
            val resolved =
                finalResult ?: run {
                    logger.error("Failed to connect to '${config.name}' after $maxRetries attempts", lastError)
                    Result.failure(resolveConnectFailure(lastError))
                }
            return resolved
        }

        private suspend fun handleConnectFailure(
            error: Throwable?,
            attempt: Int,
            backoff: ExponentialBackoff,
        ): Result<Unit>? {
            if (error is CancellationException) {
                logger.info("Connect cancelled for '${config.name}'; stopping retries")
                return Result.failure(error)
            }
            logger.warn(
                "Failed to connect to '${config.name}' (attempt $attempt/$maxRetries): " +
                    "${error?.message}",
                error,
            )
            if (attempt < maxRetries) {
                delay(backoff.delayForAttempt(attempt))
            }
            return null
        }

        private suspend fun attemptConnect(
            client: McpClient,
            timeoutMillis: Long,
            isAuthInteractive: Boolean,
        ): Result<Unit> {
            val attempt =
                runCatching {
                    if (isAuthInteractive) {
                        client.connect()
                    } else {
                        withTimeout(timeoutMillis) { client.connect() }
                    }
                }
            val failure = attempt.exceptionOrNull()
            if (failure is CancellationException) throw failure
            val result =
                when {
                    failure == null -> attempt.getOrThrow()
                    failure is TimeoutCancellationException -> {
                        Result.failure(
                            McpError.TimeoutError(
                                "Connect timed out after ${timeoutMillis}ms",
                                failure,
                            ),
                        )
                    }
                    else -> Result.failure(failure)
                }
            return result
        }

        private fun resolveConnectFailure(lastError: Throwable?): Throwable {
            val base =
                if (maxRetries > 1) {
                    "Failed to connect after $maxRetries attempts"
                } else {
                    "Failed to connect"
                }
            val resolved =
                when {
                    lastError == null -> McpError.ConnectionError(base, null)
                    lastError is McpError -> lastError
                    else -> {
                        val detail = lastError.message?.takeIf { it.isNotBlank() }
                        val message = if (detail == null) base else "$base: $detail"
                        McpError.ConnectionError(message, lastError)
                    }
                }
            return resolved
        }

        private fun persistAuthState() {
            val state = authState ?: return
            authStateObserver?.invoke(state)
        }

        private suspend fun <T> finalizeSession(
            client: McpClient,
            result: Result<T>,
        ): Result<T> {
            runCatching { client.disconnect() }
                .onFailure { logger.warn("Error while disconnecting from '${config.name}'", it) }
            persistAuthState()
            if (status !is ServerStatus.Error) {
                status = ServerStatus.Stopped
            }
            logger.debug("Closed MCP session for '${config.name}'")
            return result
        }
    }
}

private fun TransportConfig.isTlsCapableTransport(): Boolean =
    this is TransportConfig.HttpTransport ||
        this is TransportConfig.StreamableHttpTransport ||
        this is TransportConfig.WebSocketTransport
