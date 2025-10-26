package io.qent.bro.core.mcp

import io.qent.bro.core.mcp.errors.McpError
import io.qent.bro.core.models.McpServerConfig
import io.qent.bro.core.utils.ConsoleLogger
import io.qent.bro.core.utils.ExponentialBackoff
import io.qent.bro.core.utils.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class DefaultMcpServerConnection(
    override val config: McpServerConfig,
    private val logger: Logger = ConsoleLogger,
    private val cacheTtlMs: Long = 5 * 60 * 1000,
    private val maxRetries: Int = 5
) : McpServerConnection {
    override val serverId: String = config.id
    @Volatile
    override var status: ServerStatus = ServerStatus.Stopped
        private set

    private val cache = CapabilitiesCache(ttlMillis = cacheTtlMs)
    private val client: McpClient by lazy { McpClientFactory.create(config.transport, config.env) }
    private val connectMutex = Mutex()

    override suspend fun connect(): Result<Unit> = connectMutex.withLock {
        if (status == ServerStatus.Running) return Result.success(Unit)
        status = ServerStatus.Starting
        val backoff = ExponentialBackoff()
        var lastError: Throwable? = null
        for (attempt in 1..maxRetries) {
            val result = client.connect()
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
        val result = client.fetchCapabilities()
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
        return client.callTool(toolName, arguments)
    }
}

