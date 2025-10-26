package io.qent.bro.core.mcp

import io.qent.bro.core.models.McpServerConfig
import io.qent.bro.core.models.TransportConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultMcpServerConnectionTest {
    private fun config(id: String = "s1") = McpServerConfig(
        id = id,
        name = "Test Server",
        transport = TransportConfig.HttpTransport(url = "http://localhost")
    )

    @Test
    fun caching_forceRefresh_and_fallback_uses_cache_with_mockito() {
        runBlocking {
            val mockClient: McpClient = mock()
            val caps1 = ServerCapabilities(tools = listOf(ToolDescriptor("t1")))
            val caps2 = ServerCapabilities(tools = listOf(ToolDescriptor("t2")))

            whenever(mockClient.connect()).thenReturn(Result.success(Unit))
            whenever(mockClient.fetchCapabilities()).thenReturn(
                Result.success(caps1), // first fetch
                Result.success(caps2), // second fetch (force refresh)
                Result.failure(IllegalStateException("fetch fail")) // third fetch (force refresh fallback)
            )

            val conn = DefaultMcpServerConnection(config(), client = mockClient)

            assertTrue(conn.connect().isSuccess)

            val first = conn.getCapabilities()
            assertTrue(first.isSuccess)
            assertEquals("t1", first.getOrThrow().tools.first().name)
            verify(mockClient, times(1)).fetchCapabilities()

            // Cached path should not call client again
            val second = conn.getCapabilities()
            assertTrue(second.isSuccess)
            verify(mockClient, times(1)).fetchCapabilities()

            // Force refresh increments fetch count, returns new caps
            val third = conn.getCapabilities(forceRefresh = true)
            assertTrue(third.isSuccess)
            assertEquals("t2", third.getOrThrow().tools.first().name)
            verify(mockClient, times(2)).fetchCapabilities()

            // Now simulate failure, but fallback to cached on forceRefresh
            val fourth = conn.getCapabilities(forceRefresh = true)
            assertTrue(fourth.isSuccess)
            assertEquals("t2", fourth.getOrThrow().tools.first().name)
            verify(mockClient, times(3)).fetchCapabilities()
        }
    }

    @Test
    fun call_tool_delegates_to_client() {
        runBlocking {
            val mockClient: McpClient = mock()
            whenever(mockClient.connect()).thenReturn(Result.success(Unit))
            whenever(mockClient.callTool(any(), any())).thenReturn(Result.success(buildJsonObject { put("tool", "echo") }))

            val conn = DefaultMcpServerConnection(config(), client = mockClient)
            assertTrue(conn.connect().isSuccess)
            val result = conn.callTool("echo", JsonObject(emptyMap()))
            assertTrue(result.isSuccess)
            assertTrue(result.getOrThrow().toString().contains("\"tool\":\"echo\""))
            verify(mockClient).callTool(any(), any())
        }
    }
}
