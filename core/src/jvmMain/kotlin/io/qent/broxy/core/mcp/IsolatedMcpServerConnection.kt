package io.qent.broxy.core.mcp

import io.qent.broxy.core.models.McpServerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
) : McpServerConnection,
    Closeable {
    private val dispatcher: ExecutorCoroutineDispatcher =
        Executors
            .newSingleThreadExecutor { runnable ->
                Thread(runnable, threadName).apply { isDaemon = true }
            }.asCoroutineDispatcher()

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val queue = OperationQueue()
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val actorJob =
        scope
            .launch {
                try {
                    while (signals.receiveCatching().isSuccess) {
                        queue.drain()
                    }
                } finally {
                    queue.cancelPending(CancellationException("Server operation cancelled"))
                }
            }.also { job ->
                job.invokeOnCompletion { dispatcher.close() }
            }

    private interface ServerOperation {
        val isCancelled: Boolean

        fun cancel(cause: CancellationException)

        suspend fun execute()
    }

    private class ServerOperationImpl<T>(
        private val block: suspend () -> T,
    ) : ServerOperation {
        private val result = CompletableDeferred<T>()

        override val isCancelled: Boolean
            get() = result.isCancelled

        override fun cancel(cause: CancellationException) {
            result.cancel(cause)
        }

        suspend fun await(): T = result.await()

        override suspend fun execute() {
            val outcome = runCatching { block() }
            outcome.onSuccess { value ->
                result.complete(value)
            }
            outcome.onFailure { t ->
                if (t is CancellationException) {
                    if (!currentCoroutineContext().isActive) {
                        result.cancel(t)
                        throw t
                    }
                    result.completeExceptionally(t)
                } else {
                    result.completeExceptionally(t)
                }
            }
        }
    }

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
        signals.close()
        scope.cancel()
        actorJob.cancel()
    }

    private suspend fun <T> runIsolated(block: suspend () -> T): T {
        val op = ServerOperationImpl(block)
        queue.add(op)
        if (signals.trySend(Unit).isFailure) {
            queue.remove(op)
            val error = CancellationException("Server connection is closed")
            op.cancel(error)
            throw error
        }
        return try {
            op.await()
        } catch (t: CancellationException) {
            op.cancel(t)
            queue.remove(op)
            throw t
        }
    }

    private inner class OperationQueue {
        private val operations = ArrayDeque<ServerOperation>()
        private val mutex = Mutex()

        suspend fun add(op: ServerOperation) {
            mutex.withLock {
                operations.addLast(op)
            }
        }

        suspend fun remove(op: ServerOperation) {
            mutex.withLock {
                operations.remove(op)
            }
        }

        suspend fun drain() {
            while (true) {
                val op =
                    mutex.withLock {
                        if (operations.isEmpty()) {
                            null
                        } else {
                            operations.removeFirst()
                        }
                    } ?: return
                if (op.isCancelled) continue
                op.execute()
            }
        }

        suspend fun cancelPending(cause: CancellationException) {
            val pending =
                mutex.withLock {
                    val copy = operations.toList()
                    operations.clear()
                    copy
                }
            pending.forEach { op -> op.cancel(cause) }
        }
    }
}
