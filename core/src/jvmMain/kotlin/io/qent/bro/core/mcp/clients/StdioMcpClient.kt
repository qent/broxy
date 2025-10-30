package io.qent.bro.core.mcp.clients

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.CallToolResultBase
import io.modelcontextprotocol.kotlin.sdk.LIB_VERSION
import io.modelcontextprotocol.kotlin.sdk.shared.IMPLEMENTATION_NAME
import io.qent.bro.core.mcp.McpClient
import io.qent.bro.core.mcp.ServerCapabilities
import io.qent.bro.core.utils.ConsoleLogger
import io.qent.bro.core.utils.Logger
import io.qent.bro.core.config.EnvironmentVariableResolver
import io.qent.bro.core.utils.ConfigurationException
import kotlinx.io.buffered
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.concurrent.thread

class StdioMcpClient(
    private val command: String,
    private val args: List<String>,
    private val env: Map<String, String>,
    private val logger: Logger = ConsoleLogger,
    private val connector: SdkConnector? = null
) : McpClient {
    private var process: Process? = null
    private var client: SdkClientFacade? = null
    private var stderrThread: Thread? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val envResolver = EnvironmentVariableResolver(logger = logger)

    override suspend fun connect(): Result<Unit> = runCatching {
        if (client != null || process?.isAlive == true) return@runCatching
        if (connector != null) {
            client = connector.connect()
            logger.info("Connected via test connector for stdio client")
            return@runCatching
        }
        val resolvedEnv = try {
            envResolver.resolveMap(env)
        } catch (ex: ConfigurationException) {
            logger.error("Failed to resolve environment for stdio client '$command'", ex)
            throw ex
        }
        val pb = ProcessBuilder(listOf(command) + args)
        val envMap = pb.environment()
        resolvedEnv.forEach { (k, v) -> envMap[k] = v }
        envResolver.logResolvedEnv("Launching stdio MCP process '$command'", resolvedEnv)
        val proc = runCatching { pb.start() }
            .onFailure { ex -> logger.error("Failed to launch stdio MCP process '$command'", ex) }
            .getOrThrow()
        process = proc
        stderrThread?.takeIf { it.isAlive }?.interrupt()
        stderrThread = thread(name = "StdioMcpClient-stderr-$command") {
            runCatching {
                proc.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank()) {
                            logger.warn("[STDERR][$command] $line")
                        }
                    }
                }
            }.onFailure { ex ->
                if (!Thread.currentThread().isInterrupted) {
                    logger.warn("Error reading stderr from '$command'", ex)
                }
            }
        }

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
        stderrThread?.let {
            runCatching { it.join(500) }
        }
        stderrThread = null
        client = null
        logger.info("Stopped stdio MCP process: $command")
    }

    override suspend fun fetchCapabilities(): Result<ServerCapabilities> = runCatching {
        val c = client ?: throw IllegalStateException("Not connected")
        val tools = kotlin.runCatching { c.getTools() }
            .onFailure { logger.warn("listTools not available; treating as empty.", it) }
            .getOrDefault(emptyList())
        val resources = kotlin.runCatching { c.getResources() }
            .onFailure { logger.warn("listResources not available; treating as empty.", it) }
            .getOrDefault(emptyList())
        val prompts = kotlin.runCatching { c.getPrompts() }
            .onFailure { logger.warn("listPrompts not available; treating as empty.", it) }
            .getOrDefault(emptyList())
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

    // Uses SdkConnector for test-time injection
}
