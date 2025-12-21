package io.qent.broxy.core.mcp

import io.qent.broxy.core.models.McpServerConfig
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.Closeable
import java.util.concurrent.Executors

/**
 * Runs all operations for a single MCP server on its own single-thread dispatcher.
 * This isolates per-server lifecycle work from other servers.
 */
class IsolatedMcpServerConnection(
    private val delegate: McpServerConnection,
    threadName: String = "broxy-mcp-${delegate.serverId}",
) : McpServerConnection, Closeable {
    private val dispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, threadName).apply { isDaemon = true }
        }.asCoroutineDispatcher()

    private val mutex = Mutex()

    override val serverId: String
        get() = delegate.serverId

    override val config: McpServerConfig
        get() = delegate.config

    override val status: ServerStatus
        get() = delegate.status

    override suspend fun connect(): Result<Unit> = runIsolated { delegate.connect() }

    override suspend fun disconnect() {
        runIsolated { delegate.disconnect() }
    }

    override suspend fun getCapabilities(forceRefresh: Boolean): Result<ServerCapabilities> =
        runIsolated {
            delegate.getCapabilities(forceRefresh)
        }

    override suspend fun callTool(
        toolName: String,
        arguments: JsonObject,
    ): Result<JsonElement> = runIsolated { delegate.callTool(toolName, arguments) }

    override suspend fun getPrompt(
        name: String,
        arguments: Map<String, String>?,
    ): Result<JsonObject> = runIsolated { delegate.getPrompt(name, arguments) }

    override suspend fun readResource(uri: String): Result<JsonObject> = runIsolated { delegate.readResource(uri) }

    override fun close() {
        dispatcher.close()
    }

    private suspend fun <T> runIsolated(block: suspend () -> T): T = withContext(dispatcher) { mutex.withLock { block() } }
}
