package io.qent.broxy.core.mcp.clients

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import io.modelcontextprotocol.kotlin.sdk.CallToolResultBase
import io.modelcontextprotocol.kotlin.sdk.client.mcpSse
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.client.mcpWebSocket
import io.qent.broxy.core.mcp.McpClient
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.TimeoutConfigurableMcpClient
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
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
) : McpClient, TimeoutConfigurableMcpClient {
    enum class Mode { Sse, StreamableHttp, WebSocket }

    private var ktor: HttpClient? = null
    private var client: SdkClientFacade? = null
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile
    private var connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS
    @Volatile
    private var capabilitiesTimeoutMillis: Long = DEFAULT_CAPABILITIES_TIMEOUT_MILLIS

    override fun updateTimeouts(connectTimeoutMillis: Long, capabilitiesTimeoutMillis: Long) {
        this.connectTimeoutMillis = connectTimeoutMillis.coerceAtLeast(1)
        this.capabilitiesTimeoutMillis = capabilitiesTimeoutMillis.coerceAtLeast(1)
    }

    companion object {
        private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000L
        private const val DEFAULT_CAPABILITIES_TIMEOUT_MILLIS = 10_000L
    }

    override suspend fun connect(): Result<Unit> = runCatching {
        if (client != null) return@runCatching
        // Tests can inject a fake connector
        connector?.let {
            client = it.connect()
            logger.info("Connected via test connector for Ktor client ($mode)")
            return@runCatching
        }

        val connectTimeout = connectTimeoutMillis.coerceAtLeast(1)
        ktor = HttpClient(CIO) {
            if (mode == Mode.Sse || mode == Mode.StreamableHttp) install(SSE)
            if (mode == Mode.WebSocket) install(WebSockets)
            install(HttpTimeout) {
                requestTimeoutMillis = connectTimeout
                socketTimeoutMillis = connectTimeout
                this.connectTimeoutMillis = connectTimeout
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
        client = RealSdkClientFacade(sdk, logger)
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
        val timeoutMillis = capabilitiesTimeoutMillis.coerceAtLeast(1)
        val tools = listWithTimeout("listTools", timeoutMillis, emptyList()) { c.getTools() }
        val resources = listWithTimeout("listResources", timeoutMillis, emptyList()) { c.getResources() }
        val prompts = listWithTimeout("listPrompts", timeoutMillis, emptyList()) { c.getPrompts() }
        ServerCapabilities(tools = tools, resources = resources, prompts = prompts)
    }

    override suspend fun callTool(name: String, arguments: JsonObject): Result<JsonElement> = runCatching {
        val c = client ?: throw IllegalStateException("Not connected")
        val result = c.callTool(name, arguments) ?: io.modelcontextprotocol.kotlin.sdk.CallToolResult(
            content = emptyList(),
            structuredContent = JsonObject(emptyMap()),
            isError = false,
            _meta = JsonObject(emptyMap())
        )
        json.encodeToJsonElement(CallToolResultBase.serializer(), result) as JsonObject
    }

    override suspend fun getPrompt(name: String, arguments: Map<String, String>?): Result<JsonObject> = runCatching {
        val c = client ?: throw IllegalStateException("Not connected")
        val r = c.getPrompt(name, arguments)
        val el = kotlinx.serialization.json.Json.encodeToJsonElement(io.modelcontextprotocol.kotlin.sdk.GetPromptResult.serializer(), r)
        el as JsonObject
    }

    override suspend fun readResource(uri: String): Result<JsonObject> = runCatching {
        val c = client ?: throw IllegalStateException("Not connected")
        val r = c.readResource(uri)
        val el = kotlinx.serialization.json.Json.encodeToJsonElement(io.modelcontextprotocol.kotlin.sdk.ReadResourceResult.serializer(), r)
        el as JsonObject
    }

    private suspend fun <T> listWithTimeout(
        operation: String,
        timeoutMillis: Long,
        defaultValue: T,
        fetch: suspend () -> T
    ): T {
        return runCatching {
            withTimeout(timeoutMillis) { fetch() }
        }.onFailure { ex ->
            val kind = if (ex is TimeoutCancellationException) "timed out after ${timeoutMillis}ms" else ex.message ?: ex::class.simpleName
            logger.warn("$operation $kind; treating as empty.", ex)
        }.getOrDefault(defaultValue)
    }
}
