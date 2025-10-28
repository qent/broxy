package io.qent.bro.core.mcp.clients

import io.modelcontextprotocol.kotlin.sdk.CallToolResultBase
import io.qent.bro.core.mcp.PromptDescriptor
import io.qent.bro.core.mcp.ResourceDescriptor
import io.qent.bro.core.mcp.ToolDescriptor
import kotlinx.serialization.json.JsonObject

interface SdkClientFacade {
    suspend fun getTools(): List<ToolDescriptor>
    suspend fun getResources(): List<ResourceDescriptor>
    suspend fun getPrompts(): List<PromptDescriptor>
    suspend fun callTool(name: String, arguments: JsonObject): CallToolResultBase?
    suspend fun getPrompt(name: String): io.modelcontextprotocol.kotlin.sdk.GetPromptResult
    suspend fun readResource(uri: String): io.modelcontextprotocol.kotlin.sdk.ReadResourceResult
    suspend fun close()
}
