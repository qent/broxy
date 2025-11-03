package io.qent.broxy.core.mcp

import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.TransportConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

private class MCServer(
    override val serverId: String,
    override val config: McpServerConfig,
    var caps: Result<ServerCapabilities>,
    private val toolHandler: (String, JsonObject) -> Result<JsonElement>
) : McpServerConnection {
    override var status: ServerStatus = ServerStatus.Running
        private set

    override suspend fun connect(): Result<Unit> = Result.success(Unit)
    override suspend fun disconnect() {}
    override suspend fun getCapabilities(forceRefresh: Boolean): Result<ServerCapabilities> = caps
    override suspend fun callTool(toolName: String, arguments: JsonObject): Result<JsonElement> = toolHandler(toolName, arguments)
    override suspend fun getPrompt(name: String): Result<JsonObject> = Result.failure(UnsupportedOperationException())
    override suspend fun readResource(uri: String): Result<JsonObject> = Result.failure(UnsupportedOperationException())
}

class MultiServerClientTest {
    private fun cfg(id: String) = McpServerConfig(id, "srv-$id", TransportConfig.HttpTransport("http://$id"))

    @Test
    fun fetch_all_caps_skips_failures_and_prefix_tools() {
        runBlocking {
        val s1 = MCServer(
            serverId = "s1",
            config = cfg("s1"),
            caps = Result.success(ServerCapabilities(tools = listOf(ToolDescriptor("t1"))))
        ) { _, _ -> Result.success(buildJsonObject {
            put("content", buildJsonArray { })
            put("structuredContent", buildJsonObject { put("ok", JsonPrimitive(true)) })
            put("isError", JsonPrimitive(false))
            put("_meta", JsonObject(emptyMap()))
        }) }

        val s2 = MCServer(
            serverId = "s2",
            config = cfg("s2"),
            caps = Result.failure(IllegalStateException("boom"))
        ) { _, _ -> Result.success(buildJsonObject {
            put("content", buildJsonArray { })
            put("structuredContent", buildJsonObject { put("ok", JsonPrimitive(true)) })
            put("isError", JsonPrimitive(false))
            put("_meta", JsonObject(emptyMap()))
        }) }

        val multi = MultiServerClient(listOf(s1, s2))
        val all = multi.fetchAllCapabilities()
        assertEquals(setOf("s1"), all.keys)

        val tools = multi.listPrefixedTools(all)
        assertEquals(1, tools.size)
        assertEquals("s1:t1", tools.first().name)
        }
    }

    @Test
    fun routes_tool_calls_by_prefix_and_validates_names() {
        runBlocking {
        val s1 = MCServer(
            serverId = "a",
            config = cfg("a"),
            caps = Result.success(ServerCapabilities()),
        ) { tool, _ -> Result.success(buildJsonObject {
            put("content", buildJsonArray { })
            put("structuredContent", buildJsonObject {
                put("server", JsonPrimitive("a"))
                put("tool", JsonPrimitive(tool))
            })
            put("isError", JsonPrimitive(false))
            put("_meta", JsonObject(emptyMap()))
        }) }

        val s2 = MCServer(
            serverId = "b",
            config = cfg("b"),
            caps = Result.success(ServerCapabilities()),
        ) { tool, _ -> Result.success(buildJsonObject {
            put("content", buildJsonArray { })
            put("structuredContent", buildJsonObject {
                put("server", JsonPrimitive("b"))
                put("tool", JsonPrimitive(tool))
            })
            put("isError", JsonPrimitive(false))
            put("_meta", JsonObject(emptyMap()))
        }) }

        val multi = MultiServerClient(listOf(s1, s2))
        val r = multi.callPrefixedTool("b:echo")
        assertTrue(r.isSuccess)
        assertTrue(r.getOrThrow().toString().contains("\"server\":\"b\""))

        assertFailsWith<IllegalArgumentException> { multi.parsePrefixedName("invalid") }
        }
    }
}
