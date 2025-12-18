package io.qent.broxy.core.proxy

import io.qent.broxy.core.mcp.*
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.TransportConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.qent.broxy.core.mcp.McpServerConnection as Conn

private class DServer(
    override val serverId: String,
    override val config: McpServerConfig,
    private val caps: ServerCapabilities = ServerCapabilities()
) : Conn {
    override var status: ServerStatus = ServerStatus.Running
        private set

    override suspend fun connect(): Result<Unit> = Result.success(Unit)
    override suspend fun disconnect() {}
    override suspend fun getCapabilities(forceRefresh: Boolean): Result<ServerCapabilities> = Result.success(caps)
    override suspend fun callTool(toolName: String, arguments: JsonObject): Result<JsonElement> =
        Result.success(buildJsonObject {
            put("content", buildJsonArray { })
            put("structuredContent", buildJsonObject {
                put("server", JsonPrimitive(serverId))
                put("tool", JsonPrimitive(toolName))
            })
            put("isError", JsonPrimitive(false))
            put("_meta", JsonObject(emptyMap()))
        })

    override suspend fun getPrompt(name: String, arguments: Map<String, String>?): Result<JsonObject> =
        Result.success(buildJsonObject { put("description", "desc-$name"); put("messages", "[]") })

    override suspend fun readResource(uri: String): Result<JsonObject> =
        Result.success(buildJsonObject { put("contents", "[]"); put("_meta", "{}") })
}

class RequestDispatcherBatchTest {
    private fun cfg(id: String) = McpServerConfig(id, "srv-$id", TransportConfig.HttpTransport("http://$id"))

    @Test
    fun batch_dispatch_routes_to_correct_servers() = runBlocking {
        val s1 = DServer(
            "s1",
            cfg("s1"),
            caps = ServerCapabilities(tools = listOf(ToolDescriptor("echo")))
        )
        val s2 = DServer(
            "s2",
            cfg("s2"),
            caps = ServerCapabilities(tools = listOf(ToolDescriptor("ping")))
        )
        val allowed = setOf("s1:echo", "s2:ping")
        val dispatcher = DefaultRequestDispatcher(
            servers = listOf(s1, s2),
            allowedPrefixedTools = { allowed }
        )

        val results = dispatcher.dispatchBatch(
            listOf(
                ToolCallRequest("s1:echo"),
                ToolCallRequest("s2:ping")
            )
        )

        assertEquals(2, results.size)
        assertTrue(results[0].isSuccess)
        assertTrue(results[1].isSuccess)
        assertTrue(results[0].getOrThrow().toString().contains("\"server\":\"s1\""))
        assertTrue(results[1].getOrThrow().toString().contains("\"server\":\"s2\""))
    }

    @Test
    fun enforces_allowed_set_in_batch() = runBlocking {
        val s1 = DServer("s1", cfg("s1"))
        val s2 = DServer("s2", cfg("s2"))
        val dispatcher = DefaultRequestDispatcher(
            servers = listOf(s1, s2),
            allowedPrefixedTools = { setOf("s2:ping") }
        )

        val results = dispatcher.dispatchBatch(
            listOf(
                ToolCallRequest("s1:echo"),
                ToolCallRequest("s2:ping")
            )
        )

        assertTrue(results[0].isFailure)
        assertTrue(results[1].isSuccess)
    }

    @Test
    fun prompt_and_resource_resolution_fallback() = runBlocking {
        val s1 = DServer(
            "s1",
            cfg("s1"),
            caps = ServerCapabilities(
                prompts = listOf(PromptDescriptor("p1")),
                resources = listOf(ResourceDescriptor("r1", uri = "u1"))
            )
        )
        val s2 = DServer(
            "s2",
            cfg("s2"),
            caps = ServerCapabilities(
                prompts = listOf(PromptDescriptor("p2")),
                resources = listOf(ResourceDescriptor("r2", uri = "u2"))
            )
        )
        val dispatcher = DefaultRequestDispatcher(servers = listOf(s1, s2))

        val pr1 = dispatcher.dispatchPrompt("p2")
        assertTrue(pr1.isSuccess)
        val rr1 = dispatcher.dispatchResource("u1")
        assertTrue(rr1.isSuccess)
        val prUnknown = dispatcher.dispatchPrompt("unknown")
        assertTrue(prUnknown.isFailure)
    }
}
