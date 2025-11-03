package io.qent.broxy.core.mcp

import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.TransportConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultiServerClientMockitoTest {
    private fun cfg(id: String) = McpServerConfig(id, "srv-$id", TransportConfig.HttpTransport("http://$id"))

    @Test
    fun fetch_all_caps_and_route_calls_with_mockito() = runBlocking {
        val s1: McpServerConnection = mock()
        whenever(s1.serverId).thenReturn("s1")
        whenever(s1.config).thenReturn(cfg("s1"))
        whenever(s1.getCapabilities(false)).thenReturn(Result.success(ServerCapabilities(tools = listOf(ToolDescriptor("t1")))))
        whenever(s1.callTool(any(), any())).thenReturn(Result.success(buildJsonObject {
            put("content", buildJsonArray { })
            put("structuredContent", buildJsonObject { put("server", JsonPrimitive("s1")) })
            put("isError", JsonPrimitive(false))
            put("_meta", JsonObject(emptyMap()))
        }))

        val s2: McpServerConnection = mock()
        whenever(s2.serverId).thenReturn("s2")
        whenever(s2.config).thenReturn(cfg("s2"))
        whenever(s2.getCapabilities(false)).thenReturn(Result.failure(IllegalStateException("boom")))

        val multi = MultiServerClient(listOf(s1, s2))
        val all = multi.fetchAllCapabilities()
        assertEquals(setOf("s1"), all.keys)

        val tools = multi.listPrefixedTools(all)
        assertEquals(1, tools.size)
        assertEquals("s1:t1", tools.first().name)

        val routed = multi.callPrefixedTool("s1:t1")
        assertTrue(routed.isSuccess)

        val unknown = multi.callPrefixedTool("unknown:echo")
        assertTrue(unknown.isFailure)
    }
}
