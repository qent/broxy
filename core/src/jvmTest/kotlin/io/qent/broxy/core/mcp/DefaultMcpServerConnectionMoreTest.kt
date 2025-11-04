package io.qent.broxy.core.mcp

import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.mcp.ServerCapabilities
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertIs

class DefaultMcpServerConnectionMoreTest {
    private fun config(id: String = "s1") = McpServerConfig(
        id = id,
        name = "Test Server",
        transport = TransportConfig.HttpTransport(url = "http://localhost")
    )

    @Test
    fun getPrompt_and_readResource_connect_if_needed_and_succeed() {
        runBlocking {
            val client: McpClient = mock()
            whenever(client.connect()).thenReturn(Result.success(Unit))
            whenever(client.getPrompt("p1")).thenReturn(Result.success(buildJsonObject { put("description", "d"); put("messages", "[]") }))
            whenever(client.readResource("u1")).thenReturn(Result.success(buildJsonObject { put("contents", "[]"); put("_meta", "{}") }))

            val conn = DefaultMcpServerConnection(config(), client = client)

            val pr = conn.getPrompt("p1")
            assertTrue(pr.isSuccess)
            verify(client).connect()
            verify(client).getPrompt("p1")

            val rr = conn.readResource("u1")
            assertTrue(rr.isSuccess)
            verify(client).readResource("u1")
        }
    }

    @Test
    fun callTool_returns_failure_when_connect_fails() {
        runBlocking {
            val client: McpClient = mock()
            whenever(client.connect()).thenReturn(Result.failure(IllegalStateException("nope")))

            val conn = DefaultMcpServerConnection(config(), client = client)
            val res = conn.callTool("echo", JsonObject(emptyMap()))
            assertTrue(res.isFailure)
            verify(client, never()).callTool(any(), any())
        }
    }

    @Test
    fun callTool_times_out_when_client_is_slow() {
        runBlocking {
            val slowClient = object : McpClient {
                override suspend fun connect(): Result<Unit> = Result.success(Unit)
                override suspend fun disconnect() {}
                override suspend fun fetchCapabilities(): Result<ServerCapabilities> = Result.success(ServerCapabilities())
                override suspend fun callTool(name: String, arguments: JsonObject): Result<kotlinx.serialization.json.JsonElement> {
                    delay(50)
                    return Result.success(buildJsonObject { put("ok", true) })
                }

                override suspend fun getPrompt(name: String): Result<JsonObject> = Result.success(JsonObject(emptyMap()))
                override suspend fun readResource(uri: String): Result<JsonObject> = Result.success(JsonObject(emptyMap()))
            }

            val conn = DefaultMcpServerConnection(
                config(),
                client = slowClient,
                initialCallTimeoutMillis = 10
            )
            val res = conn.callTool("slow", JsonObject(emptyMap()))
            assertTrue(res.isFailure)
            assertIs<io.qent.broxy.core.mcp.errors.McpError.TimeoutError>(res.exceptionOrNull())
        }
    }
}
