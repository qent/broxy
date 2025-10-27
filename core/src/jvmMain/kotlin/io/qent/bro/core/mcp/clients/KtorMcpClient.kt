package io.qent.bro.core.mcp.clients

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import io.modelcontextprotocol.kotlin.sdk.client.mcpSse
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.client.mcpWebSocket
import io.qent.bro.core.mcp.McpClient
import io.qent.bro.core.mcp.ServerCapabilities
import io.qent.bro.core.utils.ConsoleLogger
import io.qent.bro.core.utils.Logger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration.Companion.seconds

/**
 * Unified Ktor-based MCP client supporting SSE and WebSocket transports.
 */
class KtorMcpClient(
    private val mode: Mode,
    private val url: String,
    private val headersMap: Map<String, String> = emptyMap(),
    private val logger: Logger = ConsoleLogger,
    private val connector: SdkConnector? = null
) : McpClient {
    enum class Mode { Sse, StreamableHttp, WebSocket }

    private var ktor: HttpClient? = null
    private var client: SdkClientFacade? = null

    override suspend fun connect(): Result<Unit> = runCatching {
        if (client != null) return@runCatching
        // Tests can inject a fake connector
        connector?.let {
            client = it.connect()
            logger.info("Connected via test connector for Ktor client ($mode)")
            return@runCatching
        }

        ktor = HttpClient(CIO) {
            if (mode == Mode.Sse || mode == Mode.StreamableHttp) install(SSE)
            if (mode == Mode.WebSocket) install(WebSockets)
            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                socketTimeoutMillis = 60_000
                connectTimeoutMillis = 30_000
            }
        }

        val reqBuilder: HttpRequestBuilder.() -> Unit = {
            if (headersMap.isNotEmpty()) {
                headers { headersMap.forEach { (k, v) -> append(k, v) } }
            }
        }

        val sdk = when (mode) {
            Mode.Sse -> requireNotNull(ktor).mcpSse(urlString = url, reconnectionTime = 3.seconds, requestBuilder = reqBuilder)
            Mode.StreamableHttp -> requireNotNull(ktor).mcpStreamableHttp(url = url, requestBuilder = reqBuilder)
            Mode.WebSocket -> requireNotNull(ktor).mcpWebSocket(urlString = url, requestBuilder = reqBuilder)
        }
        client = RealSdkClientFacade(sdk)
        logger.info("Connected Ktor MCP client ($mode) to $url")
    }

    override suspend fun disconnect() {
        runCatching { client?.close() }
        runCatching { ktor?.close() }
        client = null
        ktor = null
        logger.info("Closed Ktor MCP client ($mode) for $url")
    }

    override suspend fun fetchCapabilities(): Result<ServerCapabilities> = runCatching {
        val c = client ?: throw IllegalStateException("Not connected")
        ServerCapabilities(
            tools = c.getTools(),
            resources = c.getResources(),
            prompts = c.getPrompts()
        )
    }

    override suspend fun callTool(name: String, arguments: JsonObject): Result<JsonElement> = runCatching {
        val c = client ?: throw IllegalStateException("Not connected")
        c.callTool(name, arguments) ?: kotlinx.serialization.json.JsonNull
    }

    override suspend fun getPrompt(name: String): Result<JsonObject> = runCatching {
        val c = client ?: throw IllegalStateException("Not connected")
        val r = c.getPrompt(name)
        val el = kotlinx.serialization.json.Json.encodeToJsonElement(io.modelcontextprotocol.kotlin.sdk.GetPromptResult.serializer(), r)
        el as JsonObject
    }

    override suspend fun readResource(uri: String): Result<JsonObject> = runCatching {
        val c = client ?: throw IllegalStateException("Not connected")
        val r = c.readResource(uri)
        val el = kotlinx.serialization.json.Json.encodeToJsonElement(io.modelcontextprotocol.kotlin.sdk.ReadResourceResult.serializer(), r)
        el as JsonObject
    }
}
