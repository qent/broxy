package io.qent.broxy.core.mcp

import io.qent.broxy.core.mcp.errors.McpError
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultMcpServerConnectionTest {
    private val config = McpServerConfig(
        id = "s1",
        name = "Test Server",
        transport = TransportConfig.StdioTransport(command = "echo"),
        env = emptyMap(),
        enabled = true
    )

    private fun newConnection(
        client: FakeMcpClient,
        callTimeoutMillis: Long = 1_000L,
        capabilitiesTimeoutMillis: Long = 1_000L
    ): DefaultMcpServerConnection =
        DefaultMcpServerConnection(
            config = config,
            logger = NoopLogger,
            cacheTtlMs = Long.MAX_VALUE,
            maxRetries = 1,
            client = client,
            cache = CapabilitiesCache(ttlMillis = Long.MAX_VALUE),
            initialCallTimeoutMillis = callTimeoutMillis,
            initialCapabilitiesTimeoutMillis = capabilitiesTimeoutMillis,
            initialConnectTimeoutMillis = capabilitiesTimeoutMillis
        )

    @org.junit.Test
    fun connectMovesStatusToRunning() = runTest {
        val client = FakeMcpClient()
        val connection = newConnection(client)

        val result = connection.connect()

        assertTrue(result.isSuccess)
        assertEquals(ServerStatus.Running, connection.status)
        assertEquals(1, client.connectCalls)
    }

    @org.junit.Test
    fun getCapabilitiesUsesCacheWhenAvailable() = runTest {
        val caps1 = ServerCapabilities(
            tools = listOf(ToolDescriptor(name = "alpha"))
        )
        val caps2 = ServerCapabilities(
            tools = listOf(ToolDescriptor(name = "beta"))
        )
        val client = FakeMcpClient(
            capabilityResults = ArrayDeque(listOf(Result.success(caps1), Result.success(caps2)))
        )
        val connection = newConnection(client)
        connection.connect()

        val first = connection.getCapabilities()
        val second = connection.getCapabilities()

        assertTrue(first.isSuccess)
        assertTrue(second.isSuccess)
        assertSame(first.getOrThrow(), second.getOrThrow(), "Second call should reuse cached capabilities")
        assertEquals(1, client.capabilitiesCalls, "Client should be queried only once when cache is valid")
    }

    @org.junit.Test
    fun forceRefreshFailureFallsBackToCachedCapabilities() = runTest {
        val cachedCaps = ServerCapabilities(
            tools = listOf(ToolDescriptor(name = "alpha"))
        )
        val client = FakeMcpClient(
            capabilityResults = ArrayDeque(
                listOf(
                    Result.success(cachedCaps),
                    Result.failure(RuntimeException("boom"))
                )
            )
        )
        val connection = newConnection(client)
        connection.connect()

        val initial = connection.getCapabilities()
        val refreshed = connection.getCapabilities(forceRefresh = true)

        assertTrue(initial.isSuccess)
        assertTrue(refreshed.isSuccess, "Failure on refresh should return cached result")
        assertEquals(cachedCaps, refreshed.getOrThrow())
        assertEquals(2, client.capabilitiesCalls)
    }

    @org.junit.Test
    fun callToolRespectsTimeoutAndReturnsTimeoutError() = runTest {
        val client = FakeMcpClient().apply {
            callToolDelayMillis = 50
            callToolResult = Result.success(JsonPrimitive("ok"))
        }
        val connection = newConnection(client, callTimeoutMillis = 10)
        connection.connect()
        connection.updateCallTimeout(10)

        val result = connection.callTool("slow", JsonObject(emptyMap()))

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertIs<McpError.TimeoutError>(error)
        assertEquals(1, client.callToolCalls)
    }

    @org.junit.Test
    fun capabilitiesTimeoutFailsWithoutCache() = runTest {
        val caps = ServerCapabilities(tools = listOf(ToolDescriptor(name = "alpha")))
        val client = FakeMcpClient(
            capabilityResults = ArrayDeque(listOf(Result.success(caps)))
        ).apply {
            capabilityDelayMillis = 50
        }
        val connection = newConnection(client, capabilitiesTimeoutMillis = 10)
        connection.connect()

        val result = connection.getCapabilities(forceRefresh = true)

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertIs<McpError.TimeoutError>(error)
        assertEquals(1, client.capabilitiesCalls)
    }

    @org.junit.Test
    fun capabilitiesTimeoutFallsBackToCache() = runTest {
        val caps = ServerCapabilities(tools = listOf(ToolDescriptor(name = "alpha")))
        val client = FakeMcpClient(
            capabilityResults = ArrayDeque(listOf(Result.success(caps), Result.success(caps)))
        )
        val connection = newConnection(client, capabilitiesTimeoutMillis = 10)
        connection.connect()

        val initial = connection.getCapabilities(forceRefresh = true)
        assertTrue(initial.isSuccess)

        client.capabilityDelayMillis = 50
        val refreshed = connection.getCapabilities(forceRefresh = true)

        assertTrue(refreshed.isSuccess)
        assertEquals(caps, refreshed.getOrThrow())
        assertEquals(2, client.capabilitiesCalls)
    }

    private class FakeMcpClient(
        connectResults: ArrayDeque<Result<Unit>> = ArrayDeque(listOf(Result.success(Unit))),
        capabilityResults: ArrayDeque<Result<ServerCapabilities>> = ArrayDeque(listOf(Result.success(ServerCapabilities())))
    ) : McpClient {
        private val connectQueue = connectResults
        private val capsQueue = capabilityResults
        private var lastCaps: Result<ServerCapabilities> = capabilityResults.firstOrNull() ?: Result.success(ServerCapabilities())

        var callToolDelayMillis: Long = 0
        var capabilityDelayMillis: Long = 0
        var callToolResult: Result<JsonElement> = Result.success(JsonNull)

        var connectCalls: Int = 0
        var capabilitiesCalls: Int = 0
        var callToolCalls: Int = 0

        override suspend fun connect(): Result<Unit> {
            connectCalls += 1
            return if (connectQueue.isEmpty()) {
                Result.success(Unit)
            } else {
                connectQueue.removeFirst()
            }
        }

        override suspend fun disconnect() = Unit

        override suspend fun fetchCapabilities(): Result<ServerCapabilities> {
            capabilitiesCalls += 1
            if (capabilityDelayMillis > 0) {
                delay(capabilityDelayMillis)
            }
            val result = if (capsQueue.isEmpty()) lastCaps else capsQueue.removeFirst()
            lastCaps = result
            return result
        }

        override suspend fun callTool(name: String, arguments: JsonObject): Result<JsonElement> {
            callToolCalls += 1
            if (callToolDelayMillis > 0) {
                delay(callToolDelayMillis)
            }
            return callToolResult
        }

        override suspend fun getPrompt(name: String): Result<JsonObject> = Result.success(JsonObject(emptyMap()))

        override suspend fun readResource(uri: String): Result<JsonObject> = Result.success(JsonObject(emptyMap()))
    }

    private object NoopLogger : Logger {
        override fun debug(message: String) {}
        override fun info(message: String) {}
        override fun warn(message: String, throwable: Throwable?) {}
        override fun error(message: String, throwable: Throwable?) {}
    }
}
