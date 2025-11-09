package io.qent.broxy.core.mcp.clients

import io.qent.broxy.core.mcp.PromptDescriptor
import io.qent.broxy.core.mcp.ResourceDescriptor
import io.qent.broxy.core.mcp.ToolDescriptor
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.CallToolResultBase
import io.modelcontextprotocol.kotlin.sdk.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.ReadResourceResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive

class FakeSdkClientFacade(
    private val tools: List<ToolDescriptor> = listOf(ToolDescriptor("echo", "Echo tool")),
    private val resources: List<ResourceDescriptor> = listOf(ResourceDescriptor("res1", "uri://res1", "R1")),
    private val prompts: List<PromptDescriptor> = listOf(PromptDescriptor("p1", "Prompt 1")),
) : SdkClientFacade {
    override suspend fun getTools(): List<ToolDescriptor> = tools
    override suspend fun getResources(): List<ResourceDescriptor> = resources
    override suspend fun getPrompts(): List<PromptDescriptor> = prompts
    override suspend fun callTool(name: String, arguments: JsonObject): CallToolResultBase? =
        CallToolResult(
            content = emptyList(),
            structuredContent = buildJsonObject {
                put("tool", name)
                put("ok", true)
            },
            isError = false,
            _meta = JsonObject(mapOf("source" to JsonPrimitive("fake")))
        )

    override suspend fun getPrompt(name: String, arguments: Map<String, String>?): GetPromptResult =
        GetPromptResult(description = "desc", messages = emptyList(), _meta = JsonObject(emptyMap()))

    override suspend fun readResource(uri: String): ReadResourceResult =
        ReadResourceResult(contents = emptyList(), _meta = JsonObject(emptyMap()))

    override suspend fun close() { /* no-op */ }
}
