package io.qent.bro.core.mcp.clients

import io.qent.bro.core.mcp.PromptDescriptor
import io.qent.bro.core.mcp.ResourceDescriptor
import io.qent.bro.core.mcp.ToolDescriptor
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
    override suspend fun callTool(name: String, arguments: JsonObject): JsonElement? = buildJsonObject {
        put("tool", name)
        put("ok", true)
    }

    override suspend fun close() { /* no-op */ }
}

