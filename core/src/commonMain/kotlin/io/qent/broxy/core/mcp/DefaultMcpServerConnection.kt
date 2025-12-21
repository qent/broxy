package io.qent.broxy.core.mcp

import io.qent.broxy.core.mcp.auth.OAuthState
import io.qent.broxy.core.mcp.errors.McpError
import io.qent.broxy.core.models.AuthConfig
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.ExponentialBackoff
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class DefaultMcpServerConnection(
    override val config: McpServerConfig,
    private val logger: Logger = ConsoleLogger,
    private val cacheTtlMs: Long = 5 * 60 * 1000,
    private val maxRetries: Int = 5,
    private val authState: OAuthState? =
        when {
            config.auth is AuthConfig.OAuth -> OAuthState()
            config.auth == null && config.transport is TransportConfig.HttpTransport -> OAuthState()
            config.auth == null && config.transport is TransportConfig.StreamableHttpTransport -> OAuthState()
            config.auth == null && config.transport is TransportConfig.WebSocketTransport -> OAuthState()
            else -> null
        },
    private val clientFactory: () -> McpClient = {
        McpClientFactory(defaultMcpClientProvider()).create(
            config.transport,
            config.env,
            logger,
            config.auth,
            authState,
        )
    },
    private val cache: CapabilitiesCache = CapabilitiesCache(ttlMillis = cacheTtlMs),
    initialCallTimeoutMillis: Long = 60_000,
    initialCapabilitiesTimeoutMillis: Long = 10_000,
    initialConnectTimeoutMillis: Long = initialCapabilitiesTimeoutMillis,
) : McpServerConnection {
    override val serverId: String = config.id

    @Volatile
    override var status: ServerStatus = ServerStatus.Stopped
        private set

    @Volatile
    private var callTimeoutMillis: Long = initialCallTimeoutMillis.coerceAtLeast(1)

    @Volatile
    private var capabilitiesTimeoutMillis: Long = initialCapabilitiesTimeoutMillis.coerceAtLeast(1)

    @Volatile
    private var connectTimeoutMillis: Long = initialConnectTimeoutMillis.coerceAtLeast(1)

    fun updateCallTimeout(millis: Long) {
        callTimeoutMillis = millis.coerceAtLeast(1)
        logger.info("Updated call timeout for '${config.name}' to ${callTimeoutMillis}ms")
    }

    fun updateCapabilitiesTimeout(millis: Long) {
        capabilitiesTimeoutMillis = millis.coerceAtLeast(1)
        connectTimeoutMillis = capabilitiesTimeoutMillis
        logger.info("Updated capabilities timeout for '${config.name}' to ${capabilitiesTimeoutMillis}ms")
    }

    override suspend fun connect(): Result<Unit> = withSession { Result.success(Unit) }

    override suspend fun disconnect() {
        status = ServerStatus.Stopped
    }

    override suspend fun getCapabilities(forceRefresh: Boolean): Result<ServerCapabilities> {
        if (!forceRefresh) {
            cache.get()?.let { return Result.success(it) }
        }
        val result =
            withSession { client ->
                // Don't wrap with timeout here - fetchCapabilities() already has per-operation timeouts
                // This prevents the outer timeout from killing the entire operation when individual
                // operations (tools, prompts, resources) take time but succeed individually
                client.fetchCapabilities()
            }
        if (result.isSuccess) {
            val caps = result.getOrThrow()
            cache.put(caps)
            logger.info(
                "Successfully fetched capabilities for '${config.name}': " +
                    "${caps.tools.size} tools, ${caps.resources.size} resources, " +
                    "${caps.prompts.size} prompts",
            )
            return Result.success(caps)
        }
        val error = result.exceptionOrNull()
        logger.error("Failed to fetch capabilities for '${config.name}'", error)
        val cached = cache.get()
        if (cached != null) {
            logger.warn("Using cached capabilities for '${config.name}'", error)
            return Result.success(cached)
        }
        return Result.failure(error ?: McpError.TransportError("Unknown error fetching capabilities"))
    }

    override suspend fun callTool(
        toolName: String,
        arguments: JsonObject,
    ): Result<JsonElement> =
        withSession { client ->
            try {
                withTimeout(callTimeoutMillis) { client.callTool(toolName, arguments) }
            } catch (t: TimeoutCancellationException) {
                val err = McpError.TimeoutError("Tool '$toolName' timed out after ${callTimeoutMillis}ms", t)
                logger.warn("Timed out calling tool '$toolName' on '${config.name}'", t)
                Result.failure(err)
            }
        }

    override suspend fun getPrompt(
        name: String,
        arguments: Map<String, String>?,
    ): Result<JsonObject> =
        withSession { client ->
            try {
                withTimeout(callTimeoutMillis) { client.getPrompt(name, arguments) }
            } catch (t: TimeoutCancellationException) {
                val err = McpError.TimeoutError("Prompt '$name' timed out after ${callTimeoutMillis}ms", t)
                logger.warn("Timed out fetching prompt '$name' from '${config.name}'", t)
                Result.failure(err)
            }
        }

    override suspend fun readResource(uri: String): Result<JsonObject> =
        withSession { client ->
            try {
                withTimeout(callTimeoutMillis) { client.readResource(uri) }
            } catch (t: TimeoutCancellationException) {
                val err = McpError.TimeoutError("Resource '$uri' timed out after ${callTimeoutMillis}ms", t)
                logger.warn("Timed out reading resource '$uri' from '${config.name}'", t)
                Result.failure(err)
            }
        }

    private fun newClient(): McpClient = clientFactory().also { configureClientTimeouts(it) }

    private fun configureClientTimeouts(client: McpClient) {
        val effectiveConnectTimeout =
            (client as? AuthInteractiveMcpClient)?.authorizationTimeoutMillis
                ?.let { maxOf(connectTimeoutMillis, it) }
                ?: connectTimeoutMillis
        (client as? TimeoutConfigurableMcpClient)?.updateTimeouts(effectiveConnectTimeout, capabilitiesTimeoutMillis)
    }

    private suspend fun <T> withSession(block: suspend (McpClient) -> Result<T>): Result<T> {
        val client = newClient()
        status = ServerStatus.Starting
        val connectResult = connectClient(client)
        if (connectResult.isFailure) {
            val error = connectResult.exceptionOrNull()
            status = ServerStatus.Error(error?.message)
            runCatching { client.disconnect() }
                .onFailure { logger.warn("Error while disconnecting from '${config.name}'", it) }
            return Result.failure(error ?: McpError.ConnectionError("Failed to connect", null))
        }
        status = ServerStatus.Running
        val result =
            try {
                block(client)
            } catch (t: Throwable) {
                Result.failure(t)
            }
        if (result.isFailure) {
            status = ServerStatus.Error(result.exceptionOrNull()?.message)
        }
        runCatching { client.disconnect() }
            .onFailure { logger.warn("Error while disconnecting from '${config.name}'", it) }
        if (status !is ServerStatus.Error) {
            status = ServerStatus.Stopped
        }
        return result
    }

    private suspend fun connectClient(client: McpClient): Result<Unit> {
        val backoff = ExponentialBackoff()
        var lastError: Throwable? = null
        for (attempt in 1..maxRetries) {
            val timeoutMillis =
                (client as? AuthInteractiveMcpClient)?.authorizationTimeoutMillis
                    ?.let { maxOf(connectTimeoutMillis, it) }
                    ?: connectTimeoutMillis
            val result =
                try {
                    withTimeout(timeoutMillis) { client.connect() }
                } catch (t: TimeoutCancellationException) {
                    Result.failure(McpError.TimeoutError("Connect timed out after ${timeoutMillis}ms", t))
                }
            if (result.isSuccess) {
                return Result.success(Unit)
            } else {
                lastError = result.exceptionOrNull()
                logger.warn("Failed to connect to '${config.name}' (attempt $attempt/$maxRetries)", lastError)
                if (attempt < maxRetries) {
                    delay(backoff.delayForAttempt(attempt))
                }
            }
        }
        return Result.failure(McpError.ConnectionError("Failed to connect after $maxRetries attempts", lastError))
    }
}
