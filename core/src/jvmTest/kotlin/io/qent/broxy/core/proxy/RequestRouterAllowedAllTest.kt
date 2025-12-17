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

private class SrvAllowed(
    override val serverId: String,
    override val config: McpServerConfig,
    private val handler: (String) -> JsonObject
) : Conn {
    override var status: ServerStatus = ServerStatus.Running
        private set
    override suspend fun connect(): Result<Unit> = Result.success(Unit)
    override suspend fun disconnect() {}
    override suspend fun getCapabilities(forceRefresh: Boolean): Result<ServerCapabilities> = Result.success(ServerCapabilities())
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

class RequestRouterAllowedAllTest {
    private fun cfg(id: String) = McpServerConfig(id, "srv-$id", TransportConfig.HttpTransport("http://$id"))

    @Test
    fun empty_allowed_set_means_all_tools_are_allowed() = runBlocking {
        val s1 = SrvAllowed("s1", cfg("s1")) { tool -> buildJsonObject { put("server", "s1"); put("tool", tool) } }
        val router = DefaultRequestDispatcher(servers = listOf(s1), allowedPrefixedTools = { emptySet() })

        val res = router.dispatchToolCall(ToolCallRequest("s1:echo"))
        assertTrue(res.isSuccess)
        assertTrue(res.getOrThrow().toString().contains("\"server\":\"s1\""))
    }

    @Test
    fun when_allow_all_is_disabled_empty_allowed_set_denies_all_tools() = runBlocking {
        val s1 = SrvAllowed("s1", cfg("s1")) { tool -> buildJsonObject { put("server", "s1"); put("tool", tool) } }
        val router = DefaultRequestDispatcher(
            servers = listOf(s1),
            allowedPrefixedTools = { emptySet() },
            allowAllWhenNoAllowedTools = false
        )

        val res = router.dispatchToolCall(ToolCallRequest("s1:echo"))
        assertTrue(res.isFailure)
    }
}
