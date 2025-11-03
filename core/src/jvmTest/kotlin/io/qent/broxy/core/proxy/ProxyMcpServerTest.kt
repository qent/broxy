package io.qent.broxy.core.proxy

import io.qent.broxy.core.mcp.McpServerConnection
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.ServerStatus
import io.qent.broxy.core.mcp.ToolDescriptor
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.ToolReference
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

private class PSrv(
    override val serverId: String,
    override val config: McpServerConfig,
    private val tools: List<String>,
    private val promptNames: List<String> = emptyList(),
    private val resourceUris: List<String> = emptyList()
) : McpServerConnection {
    override var status: ServerStatus = ServerStatus.Running
        private set
    override suspend fun connect(): Result<Unit> = Result.success(Unit)
    override suspend fun disconnect() {}
    override suspend fun getCapabilities(forceRefresh: Boolean): Result<ServerCapabilities> = Result.success(
        ServerCapabilities(
            tools = tools.map { ToolDescriptor(it) },
            prompts = promptNames.map { io.qent.broxy.core.mcp.PromptDescriptor(it) },
            resources = resourceUris.map { io.qent.broxy.core.mcp.ResourceDescriptor(it, it) }
        )
    )
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
    override suspend fun getPrompt(name: String): Result<JsonObject> =
        Result.success(buildJsonObject { put("description", "desc-$name"); put("messages", "[]") })
    override suspend fun readResource(uri: String): Result<JsonObject> =
        Result.success(buildJsonObject { put("contents", "[]"); put("_meta", "{}") })
}

class ProxyMcpServerTest {
    private fun cfg(id: String) = McpServerConfig(id, "srv-$id", TransportConfig.HttpTransport("http://$id"))

    @Test
    fun filters_caps_and_routes_calls() = runBlocking {
        val s1 = PSrv("s1", cfg("s1"), tools = listOf("t1"), promptNames = listOf("p1"), resourceUris = listOf("u1"))
        val s2 = PSrv("s2", cfg("s2"), tools = listOf("t2"))
        val preset = Preset("p", "name", "d", tools = listOf(
            ToolReference("s1", "t1", true),
            ToolReference("s2", "t2", true)
        ))
        val proxy = ProxyMcpServer(listOf(s1, s2))
        proxy.start(preset, TransportConfig.HttpTransport("http://0.0.0.0:0/mcp"))

        val caps = proxy.getCapabilities()
        val names = caps.tools.map { it.name }.toSet()
        assertEquals(setOf("s1:t1", "s2:t2"), names)

        val r = proxy.callTool("s1:t1")
        assertTrue(r.isSuccess)

        val pr = proxy.getPrompt("p1")
        assertTrue(pr.isSuccess)
        val rr = proxy.readResource("u1")
        assertTrue(rr.isSuccess)
    }
}
