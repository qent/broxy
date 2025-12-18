package io.qent.broxy.core.mcp.clients

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.qent.broxy.core.mcp.PromptDescriptor
import io.qent.broxy.core.mcp.ResourceDescriptor
import io.qent.broxy.core.mcp.ToolDescriptor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class FakeSdkClientFacade(
    private val tools: List<ToolDescriptor> = listOf(ToolDescriptor("echo", "Echo tool")),
    private val resources: List<ResourceDescriptor> = listOf(ResourceDescriptor("res1", "uri://res1", "R1")),
    private val prompts: List<PromptDescriptor> = listOf(PromptDescriptor("p1", "Prompt 1")),
) : SdkClientFacade {
    override suspend fun getTools(): List<ToolDescriptor> = tools
    override suspend fun getResources(): List<ResourceDescriptor> = resources
    override suspend fun getPrompts(): List<PromptDescriptor> = prompts
    override suspend fun callTool(name: String, arguments: JsonObject): CallToolResult? =
        CallToolResult(
            content = emptyList(),
            structuredContent = buildJsonObject {
                put("tool", name)
                put("ok", true)
            },
            isError = false,
            meta = JsonObject(mapOf("source" to JsonPrimitive("fake")))
        )

    override suspend fun getPrompt(name: String, arguments: Map<String, String>?): GetPromptResult =
        GetPromptResult(description = "desc", messages = emptyList(), meta = JsonObject(emptyMap()))

    override suspend fun readResource(uri: String): ReadResourceResult =
        ReadResourceResult(contents = emptyList(), meta = JsonObject(emptyMap()))

    override suspend fun close() { /* no-op */
    }
}
