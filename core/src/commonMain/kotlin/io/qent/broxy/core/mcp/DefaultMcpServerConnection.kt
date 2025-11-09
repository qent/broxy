package io.qent.broxy.core.mcp

import io.qent.broxy.core.mcp.errors.McpError
import io.qent.broxy.core.mcp.TimeoutConfigurableMcpClient
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.ExponentialBackoff
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class DefaultMcpServerConnection(
    override val config: McpServerConfig,
    private val logger: Logger = ConsoleLogger,
    private val cacheTtlMs: Long = 5 * 60 * 1000,
    private val maxRetries: Int = 5,
    private val client: McpClient = McpClientFactory(defaultMcpClientProvider()).create(
        config.transport,
        config.env,
        logger
    ),
    private val cache: CapabilitiesCache = CapabilitiesCache(ttlMillis = cacheTtlMs),
    initialCallTimeoutMillis: Long = 60_000,
    initialCapabilitiesTimeoutMillis: Long = 30_000,
    initialConnectTimeoutMillis: Long = initialCapabilitiesTimeoutMillis
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

    init {
        applyClientTimeouts()
    }

    fun updateCallTimeout(millis: Long) {
        callTimeoutMillis = millis.coerceAtLeast(1)
        logger.info("Updated call timeout for '${config.name}' to ${callTimeoutMillis}ms")
    }

    fun updateCapabilitiesTimeout(millis: Long) {
        capabilitiesTimeoutMillis = millis.coerceAtLeast(1)
        connectTimeoutMillis = capabilitiesTimeoutMillis
        applyClientTimeouts()
        logger.info("Updated capabilities timeout for '${config.name}' to ${capabilitiesTimeoutMillis}ms")
    }

    private fun applyClientTimeouts() {
        (client as? TimeoutConfigurableMcpClient)?.updateTimeouts(connectTimeoutMillis, capabilitiesTimeoutMillis)
    }

    private val connectMutex = Mutex()

    override suspend fun connect(): Result<Unit> = connectMutex.withLock {
        if (status == ServerStatus.Running) return Result.success(Unit)
        status = ServerStatus.Starting
        val backoff = ExponentialBackoff()
        var lastError: Throwable? = null
        for (attempt in 1..maxRetries) {
            val result = try {
                withTimeout(connectTimeoutMillis) { client.connect() }
            } catch (t: TimeoutCancellationException) {
                val err = McpError.TimeoutError("Connect timed out after ${connectTimeoutMillis}ms", t)
                Result.failure(err)
            }
            if (result.isSuccess) {
                status = ServerStatus.Running
                logger.info("Connected to MCP server '${config.name}' (${config.id})")
                return Result.success(Unit)
            } else {
                lastError = result.exceptionOrNull()
                logger.warn("Failed to connect to '${config.name}' (attempt $attempt/$maxRetries)", lastError)
                status = ServerStatus.Error(lastError?.message)
                delay(backoff.delayForAttempt(attempt))
            }
        }
        status = ServerStatus.Error(lastError?.message)
        return Result.failure(McpError.ConnectionError("Failed to connect after $maxRetries attempts", lastError))
    }

    override suspend fun disconnect() {
        if (status == ServerStatus.Stopped || status == ServerStatus.Stopping) return
        status = ServerStatus.Stopping
        runCatching { client.disconnect() }
            .onFailure { logger.warn("Error while disconnecting from '${config.name}'", it) }
        status = ServerStatus.Stopped
    }

    override suspend fun getCapabilities(forceRefresh: Boolean): Result<ServerCapabilities> {
        if (!forceRefresh) {
            cache.get()?.let { return Result.success(it) }
        }
        if (status != ServerStatus.Running) {
            val conn = connect()
            if (conn.isFailure) {
                val cached = cache.get()
                if (cached != null) return Result.success(cached)
                return Result.failure(conn.exceptionOrNull()!!)
            }
        }
        val result = try {
            withTimeout(capabilitiesTimeoutMillis) { client.fetchCapabilities() }
        } catch (t: TimeoutCancellationException) {
            Result.failure(McpError.TimeoutError("Capabilities fetch timed out after ${capabilitiesTimeoutMillis}ms", t))
        }
        if (result.isSuccess) {
            val caps = result.getOrThrow()
            cache.put(caps)
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

    override suspend fun callTool(toolName: String, arguments: JsonObject): Result<JsonElement> {
        if (status != ServerStatus.Running) {
            val r = connect()
            if (r.isFailure) return Result.failure(r.exceptionOrNull()!!)
        }
        return try {
            withTimeout(callTimeoutMillis) {
                client.callTool(toolName, arguments)
            }
        } catch (t: TimeoutCancellationException) {
            val err = McpError.TimeoutError("Tool '$toolName' timed out after ${callTimeoutMillis}ms", t)
            logger.warn("Timed out calling tool '$toolName' on '${config.name}'", t)
            Result.failure(err)
        }
    }

    override suspend fun getPrompt(name: String, arguments: Map<String, String>?): Result<JsonObject> {
        if (status != ServerStatus.Running) {
            val r = connect()
            if (r.isFailure) return Result.failure(r.exceptionOrNull()!!)
        }
        return try {
            withTimeout(callTimeoutMillis) {
                client.getPrompt(name, arguments)
            }
        } catch (t: TimeoutCancellationException) {
            val err = McpError.TimeoutError("Prompt '$name' timed out after ${callTimeoutMillis}ms", t)
            logger.warn("Timed out fetching prompt '$name' from '${config.name}'", t)
            Result.failure(err)
        }
    }

    override suspend fun readResource(uri: String): Result<JsonObject> {
        if (status != ServerStatus.Running) {
            val r = connect()
            if (r.isFailure) return Result.failure(r.exceptionOrNull()!!)
        }
        return try {
            withTimeout(callTimeoutMillis) {
                client.readResource(uri)
            }
        } catch (t: TimeoutCancellationException) {
            val err = McpError.TimeoutError("Resource '$uri' timed out after ${callTimeoutMillis}ms", t)
            logger.warn("Timed out reading resource '$uri' from '${config.name}'", t)
            Result.failure(err)
        }
    }
}
