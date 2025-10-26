package io.qent.bro.core.mcp

import io.qent.bro.core.mcp.errors.McpError
import io.qent.bro.core.models.McpServerConfig
import io.qent.bro.core.models.TransportConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeMcpClient(
    var caps: ServerCapabilities,
    var failFetch: Boolean = false
) : McpClient {
    var connectCount = 0
    var fetchCount = 0
    var callCount = 0

    override suspend fun connect(): Result<Unit> {
        connectCount++
        return Result.success(Unit)
    }

    override suspend fun disconnect() { /* no-op */ }

    override suspend fun fetchCapabilities(): Result<ServerCapabilities> {
        fetchCount++
        return if (failFetch) Result.failure(IllegalStateException("fetch fail")) else Result.success(caps)
    }

    override suspend fun callTool(name: String, arguments: JsonObject): Result<kotlinx.serialization.json.JsonElement> {
        callCount++
        return Result.success(buildJsonObject { put("tool", name) })
    }
}

class DefaultMcpServerConnectionTest {
    private lateinit var fake: FakeMcpClient

    @BeforeTest
    fun setup() {
        // Inject fake into factory hooks
        McpClientFactoryHooks.provider = { _, _ -> fake }
    }

    @AfterTest
    fun tearDown() {
        McpClientFactoryHooks.provider = null
    }

    private fun config(id: String = "s1") = McpServerConfig(
        id = id,
        name = "Test Server",
        transport = TransportConfig.HttpTransport(url = "http://localhost")
    )

    @Test
    fun caching_and_force_refresh_and_fallback() = runBlocking {
        fake = FakeMcpClient(
            caps = ServerCapabilities(
                tools = listOf(ToolDescriptor("t1")),
                resources = emptyList(),
                prompts = emptyList()
            )
        )
        val conn = DefaultMcpServerConnection(config())

        assertTrue(conn.connect().isSuccess)
        val first = conn.getCapabilities()
        assertTrue(first.isSuccess)
        assertEquals(1, fake.fetchCount)

        // Cached path should not increment fetch count
        val second = conn.getCapabilities()
        assertTrue(second.isSuccess)
        assertEquals(1, fake.fetchCount)

        // Force refresh increments fetch count
        val third = conn.getCapabilities(forceRefresh = true)
        assertTrue(third.isSuccess)
        assertEquals(2, fake.fetchCount)

        // Now simulate failure, but fallback to cached on forceRefresh
        fake.failFetch = true
        val fourth = conn.getCapabilities(forceRefresh = true)
        assertTrue(fourth.isSuccess)
        assertEquals(3, fake.fetchCount)
    }

    @Test
    fun call_tool_delegates() = runBlocking {
        fake = FakeMcpClient(
            caps = ServerCapabilities()
        )
        val conn = DefaultMcpServerConnection(config())
        assertTrue(conn.connect().isSuccess)
        val result = conn.callTool("echo", JsonObject(emptyMap()))
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().toString().contains("\"tool\":\"echo\""))
        assertEquals(1, fake.callCount)
    }
}

