package io.qent.broxy.core.mcp.clients

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.qent.broxy.core.mcp.PromptDescriptor
import io.qent.broxy.core.mcp.ResourceDescriptor
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.ToolDescriptor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KtorMcpClientStreamableHttpTest {
    @Test
    fun connect_and_capabilities_and_callTool_with_mockito() {
        runBlocking {
            val facade: SdkClientFacade = mock()
            whenever(facade.getTools()).thenReturn(listOf(ToolDescriptor("echo", "Echo tool")))
            whenever(facade.getResources()).thenReturn(listOf(ResourceDescriptor("res1", "uri://res1", "R1")))
            whenever(facade.getPrompts()).thenReturn(listOf(PromptDescriptor("p1", "Prompt 1")))
            whenever(facade.callTool(any(), any())).thenReturn(
                CallToolResult(
                    content = emptyList(),
                    structuredContent = buildJsonObject { put("ok", true) },
                    isError = false,
                    meta = JsonObject(emptyMap()),
                ),
            )

            val client =
                KtorMcpClient(
                    mode = KtorMcpClient.Mode.StreamableHttp,
                    url = "http://localhost",
                    headersMap = emptyMap(),
                    connector = SdkConnector { facade },
                )

            val conn = client.connect()
            assertTrue(conn.isSuccess)

            val caps = client.fetchCapabilities()
            assertTrue(caps.isSuccess)
            val c: ServerCapabilities = caps.getOrThrow()
            assertEquals(1, c.tools.size)
            assertEquals("echo", c.tools.first().name)

            val res = client.callTool("echo", JsonObject(emptyMap()))
            assertTrue(res.isSuccess)
            assertTrue(res.getOrThrow().toString().contains("\"ok\":true"))

            verify(facade).getTools()
            verify(facade).callTool(any(), any())
        }
    }
}
