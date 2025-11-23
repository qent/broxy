package io.qent.broxy.core.mcp.clients

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.qent.broxy.core.mcp.PromptDescriptor
import io.qent.broxy.core.mcp.ResourceDescriptor
import io.qent.broxy.core.mcp.ToolDescriptor
import kotlinx.serialization.json.JsonObject

interface SdkClientFacade {
    suspend fun getTools(): List<ToolDescriptor>
    suspend fun getResources(): List<ResourceDescriptor>
    suspend fun getPrompts(): List<PromptDescriptor>
    suspend fun callTool(name: String, arguments: JsonObject): CallToolResult?
    suspend fun getPrompt(name: String, arguments: Map<String, String>? = null): GetPromptResult
    suspend fun readResource(uri: String): ReadResourceResult
    suspend fun close()
}
