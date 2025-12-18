package io.qent.broxy.core.proxy

import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.ServerStatus
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.TransportConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertTrue
import io.qent.broxy.core.mcp.McpServerConnection as Conn

private class Srv(
    override val serverId: String,
    override val config: McpServerConfig,
    private val handler: (String) -> JsonObject
) : Conn {
    override var status: ServerStatus = ServerStatus.Running
        private set

    override suspend fun connect(): Result<Unit> = Result.success(Unit)
    override suspend fun disconnect() {}
    override suspend fun getCapabilities(forceRefresh: Boolean): Result<ServerCapabilities> =
        Result.success(ServerCapabilities())

    override suspend fun callTool(toolName: String, arguments: JsonObject): Result<JsonElement> =
        Result.success(buildJsonObject {
            put("content", buildJsonArray { })
            put("structuredContent", handler(toolName))
            put("isError", JsonPrimitive(false))
            put("_meta", JsonObject(emptyMap()))
        })

    override suspend fun getPrompt(name: String, arguments: Map<String, String>?): Result<JsonObject> =
        Result.failure(UnsupportedOperationException())

    override suspend fun readResource(uri: String): Result<JsonObject> = Result.failure(UnsupportedOperationException())
}

class RequestRouterTest {
    private fun cfg(id: String) = McpServerConfig(id, "srv-$id", TransportConfig.HttpTransport("http://$id"))

    @Test
    fun enforces_allowed_and_routes_by_prefix() = runBlocking {
        val s1 = Srv("s1", cfg("s1")) { tool -> buildJsonObject { put("server", "s1"); put("tool", tool) } }
        val s2 = Srv("s2", cfg("s2")) { tool -> buildJsonObject { put("server", "s2"); put("tool", tool) } }
        val router = DefaultRequestDispatcher(servers = listOf(s1, s2), allowedPrefixedTools = { setOf("s2:echo") })

        val denied = router.dispatchToolCall(ToolCallRequest("s1:echo"))
        assertTrue(denied.isFailure)

        val ok = router.dispatchToolCall(ToolCallRequest("s2:echo"))
        assertTrue(ok.isSuccess)
        assertTrue(ok.getOrThrow().toString().contains("\"server\":\"s2\""))
    }
}
