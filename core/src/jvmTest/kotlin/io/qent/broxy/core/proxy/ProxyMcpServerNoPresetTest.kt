package io.qent.broxy.core.proxy

import io.qent.broxy.core.mcp.*
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.ToolReference
import io.qent.broxy.core.models.TransportConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertTrue

private class StaticServer(
    override val serverId: String,
    override val config: McpServerConfig,
    private val caps: ServerCapabilities
) : McpServerConnection {
    override var status: ServerStatus = ServerStatus.Running
        private set

    override suspend fun connect(): Result<Unit> = Result.success(Unit)
    override suspend fun disconnect() {}
    override suspend fun getCapabilities(forceRefresh: Boolean): Result<ServerCapabilities> = Result.success(caps)

    override suspend fun callTool(toolName: String, arguments: JsonObject): Result<JsonElement> =
        Result.success(
            buildJsonObject {
                put("content", buildJsonArray { })
                put("structuredContent", buildJsonObject { put("tool", JsonPrimitive(toolName)) })
                put("isError", JsonPrimitive(false))
                put("_meta", JsonObject(emptyMap()))
            }
        )

    override suspend fun getPrompt(name: String, arguments: Map<String, String>?): Result<JsonObject> =
        Result.success(buildJsonObject { put("description", JsonPrimitive("desc-$name")); put("messages", JsonPrimitive("[]")) })

    override suspend fun readResource(uri: String): Result<JsonObject> =
        Result.success(buildJsonObject { put("contents", JsonPrimitive("[]")); put("_meta", JsonPrimitive("{}")) })
}

class ProxyMcpServerNoPresetTest {
    private fun cfg(id: String) = McpServerConfig(id, "srv-$id", TransportConfig.HttpTransport("http://$id"))

    @Test
    fun no_active_preset_exposes_empty_caps_and_denies_calls() = runBlocking {
        val server = StaticServer(
            serverId = "s1",
            config = cfg("s1"),
            caps = ServerCapabilities(
                tools = listOf(ToolDescriptor("echo")),
                prompts = listOf(PromptDescriptor("p1")),
                resources = listOf(ResourceDescriptor("r1", uri = "u1"))
            )
        )
        val proxy = ProxyMcpServer(downstreams = listOf(server))
        proxy.start(Preset.empty(), TransportConfig.StreamableHttpTransport("http://localhost:3335/mcp"))

        assertTrue(proxy.getCapabilities().tools.isEmpty())
        assertTrue(proxy.getCapabilities().prompts.isEmpty())
        assertTrue(proxy.getCapabilities().resources.isEmpty())

        assertTrue(proxy.callTool("s1:echo").isFailure)
        assertTrue(proxy.getPrompt("p1").isFailure)
        assertTrue(proxy.readResource("u1").isFailure)
    }

    @Test
    fun preset_selection_allows_tools_and_includes_prompt_resource_when_unrestricted() = runBlocking {
        val server = StaticServer(
            serverId = "s1",
            config = cfg("s1"),
            caps = ServerCapabilities(
                tools = listOf(ToolDescriptor("echo")),
                prompts = listOf(PromptDescriptor("p1")),
                resources = listOf(ResourceDescriptor("r1", uri = "u1"))
            )
        )
        val proxy = ProxyMcpServer(downstreams = listOf(server))
        proxy.start(Preset.empty(), TransportConfig.StreamableHttpTransport("http://localhost:3335/mcp"))

        val preset = Preset(
            id = "main",
            name = "Main",
            description = "",
            tools = listOf(ToolReference(serverId = "s1", toolName = "echo", enabled = true)),
            prompts = null,
            resources = null
        )
        proxy.applyPreset(preset)

        assertTrue(proxy.getCapabilities().tools.any { it.name == "s1:echo" })
        assertTrue(proxy.getCapabilities().prompts.any { it.name == "p1" })
        assertTrue(proxy.getCapabilities().resources.any { (it.uri ?: it.name) == "u1" })

        assertTrue(proxy.callTool("s1:echo").isSuccess)
        assertTrue(proxy.getPrompt("p1").isSuccess)
        assertTrue(proxy.readResource("u1").isSuccess)
    }
}
