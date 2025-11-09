package io.qent.broxy.core.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

interface McpClient {
    suspend fun connect(): Result<Unit>
    suspend fun disconnect()
    suspend fun fetchCapabilities(): Result<ServerCapabilities>
    suspend fun callTool(name: String, arguments: JsonObject = JsonObject(emptyMap())): Result<JsonElement>
    suspend fun getPrompt(name: String, arguments: Map<String, String>? = null): Result<JsonObject>
    suspend fun readResource(uri: String): Result<JsonObject>
}
