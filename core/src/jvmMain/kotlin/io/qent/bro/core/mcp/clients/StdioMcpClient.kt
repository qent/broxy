package io.qent.bro.core.mcp.clients

import io.modelcontextprotocol.kotlin.sdk.LIB_VERSION
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.IMPLEMENTATION_NAME
import io.qent.bro.core.mcp.McpClient
import io.qent.bro.core.mcp.ServerCapabilities
import io.qent.bro.core.utils.ConsoleLogger
import io.qent.bro.core.utils.Logger
import kotlinx.io.buffered
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

class StdioMcpClient(
    private val command: String,
    private val args: List<String>,
    private val env: Map<String, String>,
    private val logger: Logger = ConsoleLogger
) : McpClient {
    private var process: Process? = null
    private var client: SdkClientFacade? = null

    override suspend fun connect(): Result<Unit> = runCatching {
        if (client != null || process?.isAlive == true) return@runCatching
        val pb = ProcessBuilder(listOf(command) + args)
        val envMap = pb.environment()
        env.forEach { (k, v) -> envMap[k] = v }
        val proc = pb.start()
        process = proc

        val source = proc.inputStream.asSource().buffered()
        val sink = proc.outputStream.asSink().buffered()
        val transport = StdioClientTransport(source, sink)
        val sdk = Client(io.modelcontextprotocol.kotlin.sdk.Implementation(IMPLEMENTATION_NAME, LIB_VERSION))
        sdk.connect(transport)
        client = RealSdkClientFacade(sdk)
        logger.info("Connected stdio MCP process: $command ${args.joinToString(" ")}")
    }

    override suspend fun disconnect() {
        runCatching { client?.close() }
        process?.destroy()
        client = null
        logger.info("Stopped stdio MCP process: $command")
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
        c.callTool(name, arguments) ?: JsonNull
    }

    internal fun setClientForTests(facade: SdkClientFacade) {
        client = facade
    }
}
