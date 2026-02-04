package io.qent.broxy.core.proxy.runtime

import io.qent.broxy.core.mcp.DefaultMcpServerConnection
import io.qent.broxy.core.mcp.IsolatedMcpServerConnection
import io.qent.broxy.core.mcp.McpServerConnection
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.ServerStatus
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.proxy.ProxyMcpServer
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RefreshSchedulerTest {
    @Test
    fun start_periodic_skips_when_interval_disabled() =
        runBlocking {
            val downstream = FakeConnection("s1")
            val proxy = ProxyMcpServer(listOf(downstream), logger = NoopLogger)
            proxy.start(Preset.empty(), TransportConfig.StdioTransport(command = "noop"))
            val updates = MutableSharedFlow<ServerConnectionUpdate>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val scheduler = RefreshScheduler(scope, CollectingLogger(NoopLogger), updates) { emptyMap() }

            scheduler.updateIntervalSeconds(0)
            scheduler.startPeriodic(proxy, listOf(downstream))
            delay(50)
            scheduler.stop()

            assertEquals(0, downstream.calls)
            scope.cancel()
        }

    @Test
    fun refresh_servers_emits_error_status_updates() =
        runBlocking {
            val downstream = FakeConnection("s1")
            val proxy = ProxyMcpServer(listOf(downstream), logger = NoopLogger)
            proxy.start(Preset.empty(), TransportConfig.StdioTransport(command = "noop"))
            val updates = MutableSharedFlow<ServerConnectionUpdate>(replay = 1)
            val managed = createManagedDownstream("s1", ServerStatus.Error("boom"))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val scheduler = RefreshScheduler(scope, CollectingLogger(NoopLogger), updates) { mapOf("s1" to managed) }

            scheduler.refreshServers(proxy, listOf("s1"), "Test")

            val update = withTimeout(1_000) { updates.first() }
            assertEquals("s1", update.serverId)
            assertEquals(ServerConnectionStatus.Error, update.status)

            managed.shutdown()
            scope.cancel()
        }

    @Test
    fun refresh_servers_respects_parallelism_limit() =
        runBlocking {
            val tracker = ConcurrencyTracker()
            val started = Channel<String>(capacity = 2)
            val gates =
                mapOf(
                    "s1" to CompletableDeferred<Unit>(),
                    "s2" to CompletableDeferred<Unit>(),
                )
            val downstreams =
                listOf(
                    BlockingConnection("s1", started, gates.getValue("s1"), tracker),
                    BlockingConnection("s2", started, gates.getValue("s2"), tracker),
                )
            val proxy = ProxyMcpServer(downstreams, logger = NoopLogger)
            proxy.start(Preset.empty(), TransportConfig.StdioTransport(command = "noop"))
            val updates = MutableSharedFlow<ServerConnectionUpdate>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val scheduler =
                RefreshScheduler(
                    scope,
                    CollectingLogger(NoopLogger),
                    updates,
                    concurrencyConfig =
                        RefreshConcurrencyConfig(
                            minParallelism = 1,
                            maxParallelism = 1,
                            defaultParallelism = 1,
                            limitByCpu = false,
                        ),
                ) { emptyMap() }
            scheduler.resetLimiter(downstreams.size)

            val refreshJob = launch { scheduler.refreshServers(proxy, downstreams.map { it.serverId }, "Test") }
            val first = started.receive()
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(200) { started.receive() }
            }
            gates.getValue(first).complete(Unit)
            val second = withTimeout(1_000) { started.receive() }
            gates.getValue(second).complete(Unit)
            refreshJob.join()

            assertEquals(1, tracker.maxConcurrent)
            scope.cancel()
        }

    @Test
    fun refresh_servers_cancels_in_flight_refresh() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val downstream = CancellableConnection("s1", started, cancelled)
            val proxy = ProxyMcpServer(listOf(downstream), logger = NoopLogger)
            proxy.start(Preset.empty(), TransportConfig.StdioTransport(command = "noop"))
            val updates = MutableSharedFlow<ServerConnectionUpdate>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val scheduler = RefreshScheduler(scope, CollectingLogger(NoopLogger), updates) { emptyMap() }
            scheduler.resetLimiter(1)

            val refreshJob = launch { scheduler.refreshServers(proxy, listOf("s1"), "Test") }
            withTimeout(1_000) { started.await() }
            refreshJob.cancel()
            refreshJob.join()

            withTimeout(1_000) { cancelled.await() }
            scope.cancel()
        }

    private fun createManagedDownstream(
        id: String,
        status: ServerStatus,
    ): ManagedDownstream {
        val config =
            McpServerConfig(
                id = id,
                name = id,
                transport = TransportConfig.StdioTransport(command = "noop"),
                env = emptyMap(),
                enabled = true,
                auth = null,
            )
        val connection =
            DefaultMcpServerConnection(
                config = config,
                logger = NoopLogger,
                clientFactory = { error("unused") },
            )
        val field = DefaultMcpServerConnection::class.java.getDeclaredField("status")
        field.isAccessible = true
        field.set(connection, status)
        val isolated = IsolatedMcpServerConnection(connection)
        return ManagedDownstream(connection, isolated)
    }

    private class FakeConnection(
        override val serverId: String,
    ) : McpServerConnection {
        var calls = 0

        override val config: McpServerConfig =
            McpServerConfig(
                id = serverId,
                name = serverId,
                transport = TransportConfig.StdioTransport(command = "noop"),
                env = emptyMap(),
                enabled = true,
                auth = null,
            )
        override val status: ServerStatus = ServerStatus.Running

        override suspend fun connect(): Result<Unit> = Result.success(Unit)

        override suspend fun disconnect() = Unit

        override suspend fun getCapabilities(forceRefresh: Boolean): Result<ServerCapabilities> {
            calls += 1
            return Result.success(ServerCapabilities())
        }

        override suspend fun callTool(
            toolName: String,
            arguments: JsonObject,
        ): Result<JsonElement> = Result.failure(IllegalStateException("unused"))

        override suspend fun getPrompt(
            name: String,
            arguments: Map<String, String>?,
        ): Result<JsonObject> = Result.failure(IllegalStateException("unused"))

        override suspend fun readResource(uri: String) = Result.failure<JsonObject>(IllegalStateException("unused"))
    }

    private object NoopLogger : Logger {
        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(
            message: String,
            throwable: Throwable?,
        ) = Unit

        override fun error(
            message: String,
            throwable: Throwable?,
        ) = Unit
    }

    private class BlockingConnection(
        override val serverId: String,
        private val started: Channel<String>,
        private val gate: CompletableDeferred<Unit>,
        private val tracker: ConcurrencyTracker,
    ) : McpServerConnection {
        override val config: McpServerConfig =
            McpServerConfig(
                id = serverId,
                name = serverId,
                transport = TransportConfig.StdioTransport(command = "noop"),
                env = emptyMap(),
                enabled = true,
                auth = null,
            )
        override val status: ServerStatus = ServerStatus.Running

        override suspend fun connect(): Result<Unit> = Result.success(Unit)

        override suspend fun disconnect() = Unit

        override suspend fun getCapabilities(forceRefresh: Boolean): Result<ServerCapabilities> {
            tracker.onEnter()
            started.send(serverId)
            try {
                gate.await()
            } finally {
                tracker.onExit()
            }
            return Result.success(ServerCapabilities())
        }

        override suspend fun callTool(
            toolName: String,
            arguments: JsonObject,
        ): Result<JsonElement> = Result.failure(IllegalStateException("unused"))

        override suspend fun getPrompt(
            name: String,
            arguments: Map<String, String>?,
        ): Result<JsonObject> = Result.failure(IllegalStateException("unused"))

        override suspend fun readResource(uri: String) = Result.failure<JsonObject>(IllegalStateException("unused"))
    }

    private class CancellableConnection(
        override val serverId: String,
        private val started: CompletableDeferred<Unit>,
        private val cancelled: CompletableDeferred<Unit>,
    ) : McpServerConnection {
        override val config: McpServerConfig =
            McpServerConfig(
                id = serverId,
                name = serverId,
                transport = TransportConfig.StdioTransport(command = "noop"),
                env = emptyMap(),
                enabled = true,
                auth = null,
            )
        override val status: ServerStatus = ServerStatus.Running

        override suspend fun connect(): Result<Unit> = Result.success(Unit)

        override suspend fun disconnect() = Unit

        override suspend fun getCapabilities(forceRefresh: Boolean): Result<ServerCapabilities> {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }

        override suspend fun callTool(
            toolName: String,
            arguments: JsonObject,
        ): Result<JsonElement> = Result.failure(IllegalStateException("unused"))

        override suspend fun getPrompt(
            name: String,
            arguments: Map<String, String>?,
        ): Result<JsonObject> = Result.failure(IllegalStateException("unused"))

        override suspend fun readResource(uri: String) = Result.failure<JsonObject>(IllegalStateException("unused"))
    }

    private class ConcurrencyTracker {
        private val active = AtomicInteger(0)
        private val max = AtomicInteger(0)

        val maxConcurrent: Int
            get() = max.get()

        fun onEnter() {
            val current = active.incrementAndGet()
            max.updateAndGet { existing -> maxOf(existing, current) }
        }

        fun onExit() {
            active.decrementAndGet()
        }
    }
}
