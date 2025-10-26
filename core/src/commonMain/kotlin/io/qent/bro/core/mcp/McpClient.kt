package io.qent.bro.core.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

interface McpClient {
    suspend fun connect(): Result<Unit>
    suspend fun disconnect()
    suspend fun fetchCapabilities(): Result<ServerCapabilities>
    suspend fun callTool(name: String, arguments: JsonObject = JsonObject(emptyMap())): Result<JsonElement>
}

