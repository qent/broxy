package io.qent.broxy.core.mcp.clients

import io.modelcontextprotocol.kotlin.sdk.CallToolResultBase
import io.modelcontextprotocol.kotlin.sdk.LIB_VERSION
import io.modelcontextprotocol.kotlin.sdk.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.JSONRPCNotification
import io.modelcontextprotocol.kotlin.sdk.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.Method
import io.modelcontextprotocol.kotlin.sdk.shared.IMPLEMENTATION_NAME
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.shared.serializeMessage
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.qent.broxy.core.mcp.McpClient
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.TimeoutConfigurableMcpClient
import io.qent.broxy.core.mcp.errors.McpError
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger
import io.qent.broxy.core.config.EnvironmentVariableResolver
import io.qent.broxy.core.utils.ConfigurationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.io.buffered
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.concurrent.thread
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class StdioMcpClient(
    private val command: String,
    private val args: List<String>,
    private val env: Map<String, String>,
    private val logger: Logger = ConsoleLogger,
    private val connector: SdkConnector? = null
) : McpClient, TimeoutConfigurableMcpClient {
    private var process: Process? = null
    private var client: SdkClientFacade? = null
    private var stderrThread: Thread? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val envResolver = EnvironmentVariableResolver(logger = logger)
    @Volatile
    private var connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS
    @Volatile
    private var capabilitiesTimeoutMillis: Long = DEFAULT_CAPABILITIES_TIMEOUT_MILLIS

    override fun updateTimeouts(connectTimeoutMillis: Long, capabilitiesTimeoutMillis: Long) {
        this.connectTimeoutMillis = connectTimeoutMillis.coerceAtLeast(1)
        this.capabilitiesTimeoutMillis = capabilitiesTimeoutMillis.coerceAtLeast(1)
    }

    override suspend fun connect(): Result<Unit> = coroutineScope {
        runCatching {
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

            val handshake = async(Dispatchers.IO) {
                val source = proc.inputStream.asSource().buffered()
                val sink = proc.outputStream.asSink().buffered()
                val transport = LoggingTransport(StdioClientTransport(source, sink), logger)
                val sdk = Client(io.modelcontextprotocol.kotlin.sdk.Implementation(IMPLEMENTATION_NAME, LIB_VERSION))
                sdk.connect(transport)
                RealSdkClientFacade(sdk)
            }

            try {
                val timeoutMillis = resolveConnectTimeout()
                val facade = withTimeout(timeoutMillis) { handshake.await() }
                client = facade
                logger.info("Connected stdio MCP process: $command ${args.joinToString(" ")}")
            } catch (t: TimeoutCancellationException) {
                handshake.cancel(CancellationException("Handshake timed out", t))
                handleConnectFailure(proc)
                throw McpError.TimeoutError("STDIO connect timed out after ${resolveConnectTimeout()}ms", t)
            } catch (t: Throwable) {
                handshake.cancel(CancellationException("Handshake failed", t))
                handleConnectFailure(proc)
                throw t
            }
        }
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
        val timeoutMillis = resolveCapabilitiesTimeout()
        val tools = listWithTimeout(
            operation = "listTools",
            timeoutMillis = timeoutMillis,
            defaultValue = emptyList()
        ) { c.getTools() }
        val resources = listWithTimeout(
            operation = "listResources",
            timeoutMillis = timeoutMillis,
            defaultValue = emptyList()
        ) { c.getResources() }
        val prompts = listWithTimeout(
            operation = "listPrompts",
            timeoutMillis = timeoutMillis,
            defaultValue = emptyList()
        ) { c.getPrompts() }
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

    private fun resolveConnectTimeout(): Long = connectTimeoutMillis.coerceAtLeast(1)

    private fun resolveCapabilitiesTimeout(): Long = capabilitiesTimeoutMillis.coerceAtLeast(1)

    private fun handleConnectFailure(proc: Process) {
        runCatching { proc.destroyForcibly() }
        stderrThread?.let { thread ->
            thread.interrupt()
            runCatching { thread.join(500) }
        }
        stderrThread = null
        process = null
        client = null
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

    companion object {
        private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 30_000L
        private const val DEFAULT_CAPABILITIES_TIMEOUT_MILLIS = 30_000L
    }
}

private class LoggingTransport(
    private val delegate: Transport,
    private val logger: Logger
) : Transport {
    private val resourceListRequests = Collections.newSetFromMap(ConcurrentHashMap<io.modelcontextprotocol.kotlin.sdk.RequestId, Boolean>())

    override suspend fun start() {
        delegate.start()
    }

    override suspend fun send(message: JSONRPCMessage) {
        if (message is JSONRPCRequest && message.method == Method.Defined.ResourcesList.value) {
            resourceListRequests.add(message.id)
            logger.info("STDIO resources/list request id=${message.id}")
        }
        delegate.send(message)
    }

    override suspend fun close() {
        resourceListRequests.clear()
        delegate.close()
    }

    override fun onClose(block: () -> Unit) {
        delegate.onClose(block)
    }

    override fun onError(block: (Throwable) -> Unit) {
        delegate.onError(block)
    }

    override fun onMessage(block: suspend (JSONRPCMessage) -> Unit) {
        delegate.onMessage { message ->
            when (message) {
                is JSONRPCResponse -> {
                    if (resourceListRequests.remove(message.id)) {
                        logRaw("resources/list response", message)
                    }
                }
                is JSONRPCNotification -> {
                    if (message.method == Method.Defined.NotificationsResourcesListChanged.value) {
                        logRaw("resources/list_changed notification", message)
                    }
                }
                else -> Unit
            }
            block(message)
        }
    }

    private fun logRaw(label: String, message: JSONRPCMessage) {
        val raw = runCatching { serializeMessage(message).trimEnd() }
            .getOrElse { "unable to serialize: ${it.message}" }
        logger.warn("STDIO raw $label: $raw")
    }
}
