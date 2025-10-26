package io.qent.bro.core.mcp.clients

import io.qent.bro.core.mcp.ServerCapabilities
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpMcpClientTest {
    @Test
    fun connect_and_capabilities_and_callTool_with_fake() = runBlocking {
        val fake = FakeSdkClientFacade()
        val client = HttpMcpClient(url = "http://localhost", defaultHeaders = emptyMap())
        client.setClientForTests(fake)

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
    }
}
