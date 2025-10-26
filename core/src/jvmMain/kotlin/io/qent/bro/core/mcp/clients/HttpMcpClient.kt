package io.qent.bro.core.mcp.clients

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import io.modelcontextprotocol.kotlin.sdk.client.mcpSse
import io.qent.bro.core.mcp.McpClient
import io.qent.bro.core.mcp.ServerCapabilities
import io.qent.bro.core.utils.ConsoleLogger
import io.qent.bro.core.utils.Logger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration.Companion.seconds

class HttpMcpClient(
    private val url: String,
    private val defaultHeaders: Map<String, String>,
    private val logger: Logger = ConsoleLogger
) : McpClient {
    private var ktor: HttpClient? = null
    private var client: SdkClientFacade? = null

    override suspend fun connect(): Result<Unit> = runCatching {
        if (client != null) return@runCatching
        ktor = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                socketTimeoutMillis = 60_000
                connectTimeoutMillis = 30_000
            }
        }
        val reqBuilder: HttpRequestBuilder.() -> Unit = {
            if (defaultHeaders.isNotEmpty()) {
                headers {
                    defaultHeaders.forEach { (k, v) -> append(k, v) }
                }
            }
        }
        val sdk = requireNotNull(ktor).mcpSse(urlString = url, reconnectionTime = 3.seconds, requestBuilder = reqBuilder)
        client = RealSdkClientFacade(sdk)
        logger.info("Connected HTTP(SSE) MCP client to $url")
    }

    override suspend fun disconnect() {
        runCatching { client?.close() }
        runCatching { ktor?.close() }
        client = null
        ktor = null
        logger.info("Closed HTTP MCP client for $url")
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

    internal fun setClientForTests(facade: SdkClientFacade) {
        client = facade
    }
}
