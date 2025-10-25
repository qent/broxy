package io.qent.broxy.core.mcp.clients

import io.modelcontextprotocol.kotlin.sdk.LIB_VERSION
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.IMPLEMENTATION_NAME
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.shared.serializeMessage
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCNotification
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.qent.broxy.core.config.EnvironmentVariableResolver
import io.qent.broxy.core.mcp.McpClient
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.TimeoutConfigurableMcpClient
import io.qent.broxy.core.mcp.errors.McpError
import io.qent.broxy.core.utils.CommandLocator
import io.qent.broxy.core.utils.ConfigurationException
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger
import io.qent.broxy.core.utils.UserPathResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class StdioMcpClient(
    private val command: String,
    private val args: List<String>,
    private val env: Map<String, String>,
    private val logger: Logger = ConsoleLogger,
    private val connector: SdkConnector? = null,
) : McpClient,
    TimeoutConfigurableMcpClient {
    private var process: Process? = null
    private var client: SdkClientFacade? = null
    private var stderrThread: Thread? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val envResolver = EnvironmentVariableResolver(logger = logger)
    private val capabilityFetcher = CapabilityFetcher(logger)

    @Volatile
    private var connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS

    @Volatile
    private var capabilitiesTimeoutMillis: Long = DEFAULT_CAPABILITIES_TIMEOUT_MILLIS

    override fun updateTimeouts(
        connectTimeoutMillis: Long,
        capabilitiesTimeoutMillis: Long,
    ) {
        this.connectTimeoutMillis = connectTimeoutMillis.coerceAtLeast(MIN_TIMEOUT_MILLIS)
        this.capabilitiesTimeoutMillis = capabilitiesTimeoutMillis.coerceAtLeast(MIN_TIMEOUT_MILLIS)
    }

    override suspend fun connect(): Result<Unit> =
        coroutineScope {
            runCatching {
                if (client != null || process?.isAlive == true) return@runCatching
                connectWithConnector(connector, logger)?.let { facade ->
                    client = facade
                    return@runCatching
                }
                val resolvedEnv = resolveEnvironment(envResolver, logger, env, command)
                val resolution = resolveCommandInfo(command, resolvedEnv, logger)
                val pb = buildProcessBuilder(resolution, args, resolvedEnv)
                envResolver.logResolvedEnv("Launching stdio MCP process '$command'", resolvedEnv)
                val proc = startProcess(pb, command, logger)
                process = proc
                stderrThread?.takeIf { it.isAlive }?.interrupt()
                stderrThread = startStderrLogger(proc, command, logger)
                val handshake = startHandshake(proc, logger)
                val facade =
                    awaitHandshake(
                        proc = proc,
                        handshake = handshake,
                        command = command,
                        timeoutMillis = resolveConnectTimeout(),
                        onFailure = ::handleConnectFailure,
                    )
                client = facade
                logger.info("Connected stdio MCP process: ${resolution.resolvedCommand} ${args.joinToString(" ")}")
            }
        }

    override suspend fun disconnect() {
        // Close client with timeout to avoid hanging
        runCatching {
            withTimeout(CLOSE_TIMEOUT_MILLIS) {
                client?.close()
            }
        }.onFailure { ex ->
            logger.warn("Error closing client: ${ex.message}")
        }
        process?.destroyForcibly()
        stderrThread?.let {
            runCatching { it.join(THREAD_JOIN_MILLIS) }
        }
        stderrThread = null
        client = null
        logger.info("Stopped stdio MCP process: $command")
    }

    override suspend fun fetchCapabilities(): Result<ServerCapabilities> =
        runCatching {
            val c = checkNotNull(client) { "Not connected" }
            val timeoutMillis = resolveCapabilitiesTimeout()
            val (tools, resources, prompts) = capabilityFetcher.fetch(c, timeoutMillis)
            ServerCapabilities(tools = tools, resources = resources, prompts = prompts)
        }

    override suspend fun callTool(
        name: String,
        arguments: JsonObject,
    ): Result<JsonElement> =
        runCatching {
            val c = checkNotNull(client) { "Not connected" }
            val result =
                c.callTool(name, arguments) ?: CallToolResult(
                    content = emptyList(),
                    isError = false,
                    structuredContent = JsonObject(emptyMap()),
                    meta = JsonObject(emptyMap()),
                )
            json.encodeToJsonElement(CallToolResult.serializer(), result) as JsonObject
        }

    override suspend fun getPrompt(
        name: String,
        arguments: Map<String, String>?,
    ): Result<JsonObject> =
        runCatching {
            val c = checkNotNull(client) { "Not connected" }
            val r = c.getPrompt(name, arguments)
            val el =
                kotlinx.serialization.json.Json
                    .encodeToJsonElement(GetPromptResult.serializer(), r)
            el as JsonObject
        }

    override suspend fun readResource(uri: String): Result<JsonObject> =
        runCatching {
            val c = checkNotNull(client) { "Not connected" }
            val r = c.readResource(uri)
            val el =
                kotlinx.serialization.json.Json
                    .encodeToJsonElement(ReadResourceResult.serializer(), r)
            el as JsonObject
        }

    // Uses SdkConnector for test-time injection

    private fun resolveConnectTimeout(): Long = connectTimeoutMillis.coerceAtLeast(MIN_TIMEOUT_MILLIS)

    private fun resolveCapabilitiesTimeout(): Long = capabilitiesTimeoutMillis.coerceAtLeast(MIN_TIMEOUT_MILLIS)

    private fun handleConnectFailure(proc: Process) {
        runCatching { proc.destroyForcibly() }
        stderrThread?.let { thread ->
            thread.interrupt()
            runCatching { thread.join(THREAD_JOIN_MILLIS) }
        }
        stderrThread = null
        process = null
        client = null
    }

    companion object {
        private const val MIN_TIMEOUT_MILLIS = 1L
        private const val CLOSE_TIMEOUT_MILLIS = 2000L
        private const val THREAD_JOIN_MILLIS = 500L
        private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000L
        private const val DEFAULT_CAPABILITIES_TIMEOUT_MILLIS = 10_000L
    }
}

private data class CommandResolution(
    val resolvedCommand: String,
    val pathOverride: String?,
    val resolvedPath: String?,
)

private suspend fun connectWithConnector(
    connector: SdkConnector?,
    logger: Logger,
): SdkClientFacade? =
    connector?.let {
        val facade = it.connect()
        logger.info("Connected via test connector for stdio client")
        facade
    }

private fun resolveEnvironment(
    envResolver: EnvironmentVariableResolver,
    logger: Logger,
    env: Map<String, String>,
    command: String,
): Map<String, String> =
    if (env.isEmpty() || env.values.none { envResolver.hasPlaceholders(it) }) {
        env
    } else {
        runCatching { envResolver.resolveMap(env) }
            .onFailure { ex -> logger.error("Failed to resolve environment for stdio client '$command'", ex) }
            .getOrThrow()
    }

private fun resolveCommandInfo(
    command: String,
    resolvedEnv: Map<String, String>,
    logger: Logger,
): CommandResolution {
    val pathKey = UserPathResolver.resolvePathKey(resolvedEnv)
    val pathOverride = resolvedEnv[pathKey]?.takeIf { it.isNotBlank() }
    val resolvedPath = pathOverride ?: UserPathResolver.resolve(logger)
    val resolvedCommand =
        CommandLocator.resolveCommand(command, pathOverride = resolvedPath, logger = logger)
            ?: throw ConfigurationException(
                "STDIO command '$command' was not found in PATH. " +
                    "Provide a full path or set PATH in the server environment.",
            )
    return CommandResolution(
        resolvedCommand = resolvedCommand,
        pathOverride = pathOverride,
        resolvedPath = resolvedPath,
    )
}

private fun buildProcessBuilder(
    resolution: CommandResolution,
    args: List<String>,
    resolvedEnv: Map<String, String>,
): ProcessBuilder {
    val pb = ProcessBuilder(listOf(resolution.resolvedCommand) + args)
    val envMap = pb.environment()
    resolvedEnv.forEach { (k, v) -> envMap[k] = v }
    if (resolution.pathOverride == null) {
        resolution.resolvedPath?.let { path ->
            envMap[UserPathResolver.resolvePathKey(envMap)] = path
        }
    }
    return pb
}

private fun startProcess(
    pb: ProcessBuilder,
    command: String,
    logger: Logger,
): Process =
    runCatching { pb.start() }
        .onFailure { ex -> logger.error("Failed to launch stdio MCP process '$command'", ex) }
        .getOrThrow()

private fun startStderrLogger(
    proc: Process,
    command: String,
    logger: Logger,
): Thread =
    thread(name = "StdioMcpClient-stderr-$command") {
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

private fun CoroutineScope.startHandshake(
    proc: Process,
    logger: Logger,
): Deferred<SdkClientFacade> =
    async(Dispatchers.IO) {
        val source = proc.inputStream.asSource().buffered()
        val sink = proc.outputStream.asSink().buffered()
        val transport = LoggingTransport(StdioClientTransport(source, sink), logger)
        val sdk = Client(Implementation(IMPLEMENTATION_NAME, LIB_VERSION))
        sdk.connect(transport)
        RealSdkClientFacade(sdk, logger)
    }

private suspend fun awaitHandshake(
    proc: Process,
    handshake: Deferred<SdkClientFacade>,
    command: String,
    timeoutMillis: Long,
    onFailure: (Process) -> Unit,
): SdkClientFacade {
    val result = runCatching { withTimeout(timeoutMillis) { handshake.await() } }
    val failure = result.exceptionOrNull()
    if (failure != null) {
        rethrowIfCancelled(failure)
        val message = if (failure is TimeoutCancellationException) "Handshake timed out" else "Handshake failed"
        handshake.cancel(CancellationException(message, failure))
        onFailure(proc)
        val exception =
            if (failure is TimeoutCancellationException) {
                McpError.TimeoutError("STDIO connect timed out after ${timeoutMillis}ms", failure)
            } else {
                failure
            }
        throw exception
    }
    val facade = result.getOrThrow()
    if (!proc.isAlive) {
        val exitCode = runCatching { proc.exitValue() }.getOrDefault(-1)
        onFailure(proc)
        throw McpError.ConnectionError(
            "STDIO process '$command' exited with code $exitCode before initialization completed",
        )
    }
    return facade
}

private fun rethrowIfCancelled(failure: Throwable) {
    if (failure is CancellationException && failure !is TimeoutCancellationException) {
        throw failure
    }
}

private class LoggingTransport(
    private val delegate: Transport,
    private val logger: Logger,
) : Transport {
    private val trackedRequests = ConcurrentHashMap<RequestId, String>()

    override suspend fun start() {
        delegate.start()
    }

    override suspend fun send(
        message: JSONRPCMessage,
        options: TransportSendOptions?,
    ) {
        if (message is JSONRPCRequest) {
            when (message.method) {
                Method.Defined.ToolsList.value -> {
                    trackedRequests[message.id] = "tools/list"
                    logger.info("STDIO tools/list request id=${message.id}")
                }

                Method.Defined.ResourcesList.value -> {
                    trackedRequests[message.id] = "resources/list"
                    logger.info("STDIO resources/list request id=${message.id}")
                }

                Method.Defined.PromptsList.value -> {
                    trackedRequests[message.id] = "prompts/list"
                    logger.info("STDIO prompts/list request id=${message.id}")
                }
            }
        }
        delegate.send(message, options)
    }

    override suspend fun close() {
        trackedRequests.clear()
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
            val processed =
                when (message) {
                    is JSONRPCResponse -> {
                        trackedRequests.remove(message.id)?.let { requestType ->
                            logRaw("$requestType response", message)
                        }
                        message
                    }

                    is JSONRPCNotification -> {
                        when (message.method) {
                            Method.Defined.NotificationsResourcesListChanged.value -> {
                                logRaw("resources/list_changed notification", message)
                            }

                            Method.Defined.NotificationsToolsListChanged.value -> {
                                logRaw("tools/list_changed notification", message)
                            }

                            Method.Defined.NotificationsPromptsListChanged.value -> {
                                logRaw("prompts/list_changed notification", message)
                            }
                        }
                        message
                    }

                    else -> message
                }
            block(processed)
        }
    }

    private fun logRaw(
        label: String,
        message: JSONRPCMessage,
    ) {
        val raw =
            runCatching { serializeMessage(message).trimEnd() }
                .getOrElse { "unable to serialize: ${it.message}" }
        logger.info("STDIO raw $label: $raw")
    }
}
