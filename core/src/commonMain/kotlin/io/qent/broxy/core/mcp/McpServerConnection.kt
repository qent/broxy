package io.qent.broxy.core.mcp

import io.qent.broxy.core.models.McpServerConfig
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

interface McpServerConnection {
    val serverId: String
    val config: McpServerConfig
    val status: ServerStatus

    suspend fun connect(): Result<Unit>

    suspend fun disconnect()

    suspend fun getCapabilities(forceRefresh: Boolean = false): Result<ServerCapabilities>

    suspend fun callTool(toolName: String, arguments: JsonObject = JsonObject(emptyMap())): Result<JsonElement>

    suspend fun getPrompt(name: String, arguments: Map<String, String>? = null): Result<JsonObject>

    suspend fun readResource(uri: String): Result<JsonObject>
}
