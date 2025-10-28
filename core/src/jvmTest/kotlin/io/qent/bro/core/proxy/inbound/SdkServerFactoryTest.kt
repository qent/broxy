package io.qent.bro.core.proxy.inbound

import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.qent.bro.core.mcp.McpServerConnection
import io.qent.bro.core.mcp.ServerCapabilities
import io.qent.bro.core.mcp.ServerStatus
import io.qent.bro.core.mcp.ToolDescriptor
import io.qent.bro.core.mcp.PromptDescriptor
import io.qent.bro.core.mcp.ResourceDescriptor
import io.modelcontextprotocol.kotlin.sdk.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.qent.bro.core.models.McpServerConfig
import io.qent.bro.core.models.Preset
import io.qent.bro.core.models.ToolReference
import io.qent.bro.core.models.TransportConfig
import io.qent.bro.core.proxy.ProxyMcpServer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class Srv(
    override val serverId: String,
    override val config: McpServerConfig,
    private val caps: ServerCapabilities
) : McpServerConnection {
    override var status: ServerStatus = ServerStatus.Running
        private set
    override suspend fun connect(): Result<Unit> = Result.success(Unit)
    override suspend fun disconnect() {}
    override suspend fun getCapabilities(forceRefresh: Boolean): Result<ServerCapabilities> = Result.success(caps)
    override suspend fun callTool(toolName: String, arguments: JsonObject): Result<kotlinx.serialization.json.JsonElement> = Result.success(JsonNull)
    override suspend fun getPrompt(name: String): Result<JsonObject> = Result.success(buildJsonObject { put("description", "d"); put("messages", "[]") })
    override suspend fun readResource(uri: String): Result<JsonObject> = Result.success(buildJsonObject { put("contents", "[]"); put("_meta", "{}") })
}

class SdkServerFactoryTest {
    private fun cfg(id: String) = McpServerConfig(id, "srv-$id", TransportConfig.HttpTransport("http://$id"))

    @Test
    fun registers_tools_and_handlers_from_caps() {
        val schema = Tool.Input(
            properties = buildJsonObject {
                put("arg", buildJsonObject { put("type", JsonPrimitive("string")) })
            },
            required = listOf("arg")
        )
        val promptArgs = listOf(PromptArgument(name = "topic", description = "Topic to explore", required = true))
        val s1 = Srv(
            "s1",
            cfg("s1"),
            ServerCapabilities(
                tools = listOf(ToolDescriptor("t1", description = "desc", inputSchema = schema)),
                prompts = listOf(PromptDescriptor("p1", description = "prompt", arguments = promptArgs)),
                resources = listOf(
                    ResourceDescriptor(
                        name = "Resource",
                        uri = "uri://r1",
                        description = "resource",
                        mimeType = "application/json"
                    )
                )
            )
        )
        val proxy = ProxyMcpServer(listOf(s1))
        // Prepare filtered capabilities via preset
        val preset = Preset("p", "n", "d", tools = listOf(ToolReference("s1", "t1", true)))
        proxy.start(preset, TransportConfig.HttpTransport("http://0.0.0.0:0/mcp"))

        val server = buildSdkServer(proxy)
        // Tool registered (prefixed)
        assertTrue(server.tools.containsKey("s1:t1"))

        val registered = server.tools["s1:t1"]!!
        assertEquals(schema, registered.tool.inputSchema)
        assertEquals("desc", registered.tool.description)

        val registeredPrompt = server.prompts["p1"]!!
        assertEquals(promptArgs, registeredPrompt.prompt.arguments)

        val registeredResource = server.resources["uri://r1"]!!
        assertEquals("application/json", registeredResource.resource.mimeType)
        assertEquals("resource", registeredResource.resource.description)

        // Call handler directly
        val handler = server.tools["s1:t1"]!!.handler
        val res = kotlinx.coroutines.runBlocking { handler(CallToolRequest("s1:t1", JsonObject(emptyMap()), JsonObject(emptyMap()))) }
        // Structured content should be a JsonObject according to handler conversion
        assertTrue(res.structuredContent is JsonObject)
    }
}
