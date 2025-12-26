package io.qent.broxy.testserver

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.SseServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.mcpWebSocket
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.Prompt
import io.modelcontextprotocol.kotlin.sdk.types.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.types.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

fun main(args: Array<String>) {
    SimpleTestMcpServer(ServerCliOptions.parse(args)).start()
}

@Suppress("TooManyFunctions")
class SimpleTestMcpServer(
    private val options: ServerCliOptions = ServerCliOptions(),
) {
    fun start() {
        val profile = TestServerProfiles.forMode(options.mode)
        when (options.mode) {
            ServerCliOptions.Mode.STDIO -> runBlocking { startStdio(profile) }
            ServerCliOptions.Mode.HTTP_STREAMABLE -> startHttpStreamable(profile)
            ServerCliOptions.Mode.HTTP_SSE -> startHttpSse(profile)
            ServerCliOptions.Mode.WS -> startWebSocket(profile)
        }
    }

    private suspend fun startStdio(profile: ModeProfile) {
        val server = buildServer(profile)
        val transport =
            StdioServerTransport(
                System.`in`.asSource().buffered(),
                System.out.asSink().buffered(),
            )
        val shutdownSignal = CompletableDeferred<Unit>()
        transport.onClose {
            if (!shutdownSignal.isCompleted) {
                shutdownSignal.complete(Unit)
            }
        }
        System.err.println("SimpleTestMcpServer: waiting for STDIO client")
        val result =
            runCatching {
                server.createSession(transport)
                shutdownSignal.await()
            }.onFailure { error ->
                System.err.println("SimpleTestMcpServer: STDIO connection failed - ${error.message}")
            }
        try {
            result.getOrThrow()
        } finally {
            runCatching { transport.close() }
            System.err.println("SimpleTestMcpServer: STDIO connection closed")
        }
    }

    private fun startHttpStreamable(profile: ModeProfile) {
        val normalizedPath = normalizePath(options.path)
        val displayUrl = "http://${options.host}:${options.port}${normalizedPath.display}"
        println("Starting HTTP Streamable test server on $displayUrl")
        val server = buildServer(profile)
        val sessions = StreamableHttpSessionRegistry(server)
        embeddedServer(
            Netty,
            host = options.host,
            port = options.port,
        ) {
            install(CallLogging)
            routing {
                if (normalizedPath.routeSegments.isBlank()) {
                    mountStreamableHttpRoute(sessions)
                } else {
                    route("/${normalizedPath.routeSegments}") {
                        mountStreamableHttpRoute(sessions)
                    }
                }
            }
        }.start(wait = true)
    }

    private fun startHttpSse(profile: ModeProfile) {
        val normalizedPath = normalizePath(options.path)
        val ssePath = normalizedPath.display
        val messagePath = buildSseMessagePath(normalizedPath.display)
        val displayUrl = "http://${options.host}:${options.port}$ssePath"
        println("Starting HTTP SSE test server on $displayUrl")
        val server = buildServer(profile)
        val sessions = SseSessionRegistry(server)
        embeddedServer(
            Netty,
            host = options.host,
            port = options.port,
        ) {
            install(CallLogging)
            install(SSE)
            routing {
                sse(ssePath) {
                    sessions.register(messagePath, this)
                    awaitCancellation()
                }
                post(messagePath) {
                    val sessionId = call.request.queryParameters[SSE_SESSION_ID_PARAM]
                    val transport = sessions.getTransport(sessionId)
                    if (transport == null) {
                        call.respond(HttpStatusCode.BadRequest, "Unknown SSE session: $sessionId")
                        return@post
                    }
                    transport.handlePostMessage(call)
                }
            }
        }.start(wait = true)
    }

    private fun startWebSocket(profile: ModeProfile) {
        val normalizedPath = normalizePath(options.path)
        val displayUrl = "ws://${options.host}:${options.port}${normalizedPath.display}"
        println("Starting WebSocket test server on $displayUrl")
        embeddedServer(
            Netty,
            host = options.host,
            port = options.port,
        ) {
            install(CallLogging)
            mcpWebSocket(normalizedPath.display) {
                buildServer(profile)
            }
        }.start(wait = true)
    }

    private fun Route.mountStreamableHttpRoute(sessions: StreamableHttpSessionRegistry) {
        get {
            call.respond(HttpStatusCode.MethodNotAllowed, "SSE stream is not supported; use Streamable HTTP POST")
        }

        delete {
            val sessionId = call.request.headers[MCP_SESSION_ID_HEADER]
            if (sessionId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "Missing $MCP_SESSION_ID_HEADER header")
                return@delete
            }
            sessions.remove(sessionId)
            call.respond(HttpStatusCode.NoContent)
        }

        post {
            val ct = call.request.contentType()
            if (!isApplicationJson(ct)) {
                call.respond(HttpStatusCode.BadRequest, "Unsupported content-type: $ct")
                return@post
            }

            val session = sessions.getOrCreate(call.request.headers[MCP_SESSION_ID_HEADER])
            call.response.headers.append(MCP_SESSION_ID_HEADER, session.transport.sessionId)

            val body = call.receiveText()
            val message =
                runCatching { McpJson.decodeFromString<JSONRPCMessage>(body) }
                    .getOrElse { error ->
                        call.respond(
                            HttpStatusCode.BadRequest,
                            "Invalid MCP message: ${error.message ?: error::class.simpleName}",
                        )
                        return@post
                    }

            when (message) {
                is JSONRPCRequest -> {
                    val response =
                        runCatching { session.transport.awaitResponse(message) }
                            .getOrElse { error ->
                                val status =
                                    if (error is TimeoutCancellationException) {
                                        HttpStatusCode.RequestTimeout
                                    } else {
                                        HttpStatusCode.InternalServerError
                                    }
                                call.respond(status, error.message ?: "Failed to handle MCP request")
                                return@post
                            }
                    call.respondText(
                        text = McpJson.encodeToString(JSONRPCMessage.serializer(), response),
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.OK,
                    )
                }

                else -> {
                    runCatching { session.transport.handleMessage(message) }
                    call.respond(HttpStatusCode.OK)
                }
            }
        }
    }

    private fun buildServer(profile: ModeProfile): Server {
        val server =
            Server(
                serverInfo =
                    Implementation(
                        name = "${SERVER_NAME_PREFIX}-${profile.displayName}",
                        version = SERVER_VERSION,
                    ),
                options =
                    ServerOptions(
                        capabilities =
                            ServerCapabilities(
                                // Provide explicit initialize capabilities per MCP spec
                                prompts = ServerCapabilities.Prompts(listChanged = false),
                                resources = ServerCapabilities.Resources(subscribe = false, listChanged = false),
                                tools = ServerCapabilities.Tools(listChanged = false),
                            ),
                    ),
            )
        registerTools(server, profile)
        registerResources(server, profile)
        registerPrompts(server, profile)
        return server
    }

    private fun registerTools(
        server: Server,
        profile: ModeProfile,
    ) {
        val operation = profile.toolOperation
        server.addTool(
            name = profile.toolName,
            title = "${profile.displayName} tool",
            description = profile.toolDescription,
            inputSchema = ToolSchema(),
            outputSchema = null,
            toolAnnotations = null,
        ) { req ->
            val args = req.arguments ?: JsonObject(emptyMap())
            val a = args.value("a")
            val b = args.value("b")
            val result = operation.apply(a, b)
            CallToolResult(
                content =
                    listOf(
                        TextContent("${operation.label} result: $result"),
                    ),
                structuredContent =
                    JsonObject(
                        mapOf(
                            "operation" to JsonPrimitive(operation.label),
                            "result" to JsonPrimitive(result),
                            "mode" to JsonPrimitive(profile.displayName),
                        ),
                    ),
                isError = false,
                meta = JsonObject(emptyMap()),
            )
        }
    }

    private fun registerResources(
        server: Server,
        profile: ModeProfile,
    ) {
        server.addResource(
            uri = profile.resourceUri,
            name = profile.resourceName,
            description = profile.resourceDescription,
            mimeType = "text/plain",
        ) {
            ReadResourceResult(
                contents =
                    listOf(
                        TextResourceContents(
                            text = profile.resourceText,
                            uri = profile.resourceUri,
                            mimeType = "text/plain",
                        ),
                    ),
                meta = JsonObject(emptyMap()),
            )
        }

        server.addResource(
            uri = profile.resourceTemplateUri,
            name = profile.resourceTemplateName,
            description = profile.resourceTemplateDescription,
            mimeType = "text/plain",
        ) { request ->
            ReadResourceResult(
                contents =
                    listOf(
                        TextResourceContents(
                            text = profile.resourceTemplateText,
                            uri = request.uri,
                            mimeType = "text/plain",
                        ),
                    ),
                meta = JsonObject(emptyMap()),
            )
        }
    }

    private fun registerPrompts(
        server: Server,
        profile: ModeProfile,
    ) {
        val argument =
            PromptArgument(
                name = TestServerProfiles.PROMPT_ARGUMENT_NAME,
                description = "Name to include in the response",
                required = true,
            )

        server.addPrompt(
            Prompt(
                name = profile.promptName,
                description = profile.promptDescription,
                arguments = listOf(argument),
            ),
        ) { req ->
            val name = req.arguments?.get(TestServerProfiles.PROMPT_ARGUMENT_NAME) ?: "friend"
            GetPromptResult(
                description = profile.promptDescription,
                messages =
                    listOf(
                        PromptMessage(
                            role = Role.Assistant,
                            content = TextContent("${profile.promptPrefix} $name!"),
                        ),
                    ),
                meta = JsonObject(emptyMap()),
            )
        }

        server.addPrompt(
            Prompt(
                name = profile.promptNoArgsName,
                description = profile.promptNoArgsDescription,
                arguments = emptyList(),
            ),
        ) {
            GetPromptResult(
                description = profile.promptNoArgsDescription,
                messages =
                    listOf(
                        PromptMessage(
                            role = Role.Assistant,
                            content = TextContent(profile.promptNoArgsText),
                        ),
                    ),
                meta = JsonObject(emptyMap()),
            )
        }
    }

    private fun JsonObject.value(key: String): Double {
        val primitive = this[key]?.jsonPrimitive ?: return 0.0
        return primitive.doubleOrNull ?: primitive.content.toDoubleOrNull() ?: 0.0
    }

    private fun normalizePath(rawPath: String): NormalizedPath {
        val trimmed = rawPath.trim().ifBlank { "/" }
        val withoutPrefix = trimmed.removePrefix("/")
        val routeSegments = withoutPrefix.trim()
        val display = if (routeSegments.isBlank()) "/" else "/$routeSegments"
        return NormalizedPath(display = display, routeSegments = routeSegments)
    }

    private fun buildSseMessagePath(basePath: String): String =
        if (basePath == "/") {
            "/message"
        } else {
            "$basePath/message"
        }

    companion object {
        private const val SERVER_NAME_PREFIX = "broxy-test-mcp"
        private const val SERVER_VERSION = "0.0.1"
        private const val MCP_SESSION_ID_HEADER = "mcp-session-id"
        private const val SSE_SESSION_ID_PARAM = "sessionId"
    }
}

private data class NormalizedPath(
    val display: String,
    val routeSegments: String,
)

private const val REQUEST_TIMEOUT_MILLIS = 60_000L

private class StreamableHttpSessionRegistry(
    private val server: Server,
) {
    private val sessions = ConcurrentHashMap<String, StreamableHttpSession>()

    suspend fun getOrCreate(requestedSessionId: String?): StreamableHttpSession {
        if (!requestedSessionId.isNullOrBlank()) {
            sessions[requestedSessionId]?.let { return it }
        }
        val transport = StreamableHttpServerTransport()
        val session = server.createSession(transport)
        val entry = StreamableHttpSession(transport, session)
        sessions[transport.sessionId] = entry
        return entry
    }

    suspend fun remove(sessionId: String?) {
        if (sessionId.isNullOrBlank()) return
        val existing = sessions.remove(sessionId) ?: return
        runCatching { existing.session.close() }
        runCatching { existing.transport.close() }
    }
}

private data class StreamableHttpSession(
    val transport: StreamableHttpServerTransport,
    val session: io.modelcontextprotocol.kotlin.sdk.server.ServerSession,
)

private class SseSessionRegistry(
    private val server: Server,
) {
    private val sessions = ConcurrentHashMap<String, SseSession>()

    suspend fun register(
        messagePath: String,
        sseSession: io.ktor.server.sse.ServerSSESession,
    ) {
        val transport = SseServerTransport(messagePath, sseSession)
        val session = server.createSession(transport)
        sessions[transport.sessionId] = SseSession(transport, session)
        session.onClose {
            sessions.remove(transport.sessionId)
        }
    }

    fun getTransport(sessionId: String?): SseServerTransport? {
        if (sessionId.isNullOrBlank()) return null
        return sessions[sessionId]?.transport
    }
}

private data class SseSession(
    val transport: SseServerTransport,
    val session: io.modelcontextprotocol.kotlin.sdk.server.ServerSession,
)

private class StreamableHttpServerTransport : AbstractTransport() {
    val sessionId: String = java.util.UUID.randomUUID().toString()
    private val responseWaiters = ConcurrentHashMap<RequestId, CompletableDeferred<JSONRPCResponse>>()

    @Volatile
    private var started: Boolean = false

    override suspend fun start() {
        check(!started) { "StreamableHttpServerTransport already started" }
        started = true
    }

    suspend fun handleMessage(message: JSONRPCMessage) {
        check(started) { "Transport is not started" }
        _onMessage.invoke(message)
    }

    suspend fun awaitResponse(request: JSONRPCRequest): JSONRPCResponse {
        val deferred = CompletableDeferred<JSONRPCResponse>()
        val previous = responseWaiters.putIfAbsent(request.id, deferred)
        check(previous == null) { "Duplicate in-flight request id ${request.id}" }
        try {
            handleMessage(request)
            return withTimeout(REQUEST_TIMEOUT_MILLIS) { deferred.await() }
        } finally {
            responseWaiters.remove(request.id)
        }
    }

    override suspend fun send(
        message: JSONRPCMessage,
        options: TransportSendOptions?,
    ) {
        check(started) { "Not connected" }
        when (message) {
            is JSONRPCResponse -> responseWaiters[message.id]?.complete(message)
            else -> Unit // JSON-only mode: drop server->client notifications
        }
    }

    override suspend fun close() {
        started = false
        _onClose.invoke()
    }
}

private fun isApplicationJson(contentType: ContentType): Boolean =
    contentType.contentType == ContentType.Application.Json.contentType &&
        contentType.contentSubtype == ContentType.Application.Json.contentSubtype

data class ServerCliOptions(
    val mode: Mode = Mode.STDIO,
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    val path: String = DEFAULT_PATH,
) {
    enum class Mode {
        STDIO,
        HTTP_STREAMABLE,
        HTTP_SSE,
        WS,
    }

    companion object {
        private const val DEFAULT_HOST = "127.0.0.1"
        private const val DEFAULT_PORT = 8080
        private const val DEFAULT_PATH = "/mcp"

        fun parse(args: Array<String>): ServerCliOptions {
            var mode = Mode.STDIO
            var host = DEFAULT_HOST
            var port = DEFAULT_PORT
            var path = DEFAULT_PATH

            var index = 0
            while (index < args.size) {
                when (val arg = args[index]) {
                    "--mode" -> {
                        val value = requireNextArg(args, index, "--mode")
                        mode = parseMode(value)
                        index++
                    }

                    "--host" -> {
                        host = requireNextArg(args, index, "--host")
                        index++
                    }

                    "--port" -> {
                        val value = requireNextArg(args, index, "--port")
                        port = value.toIntOrNull() ?: error("Invalid port '$value'")
                        index++
                    }

                    "--path" -> {
                        path = requireNextArg(args, index, "--path")
                        index++
                    }
                    else -> error("Unknown argument '$arg'")
                }
                index++
            }

            return ServerCliOptions(
                mode = mode,
                host = host,
                port = port,
                path = path.ifBlank { DEFAULT_PATH },
            )
        }

        private fun requireNextArg(
            args: Array<String>,
            index: Int,
            flag: String,
        ): String {
            val nextIndex = index + 1
            return args.getOrNull(nextIndex) ?: error("Missing value for $flag")
        }

        private fun parseMode(value: String): Mode =
            when (value.lowercase()) {
                "stdio" -> Mode.STDIO
                "http", "http-streamable", "streamable-http" -> Mode.HTTP_STREAMABLE
                "http-sse", "sse" -> Mode.HTTP_SSE
                "ws", "websocket", "web-socket" -> Mode.WS
                else -> error("Unsupported mode '$value'. Use 'stdio', 'streamable-http', 'http-sse', or 'ws'.")
            }
    }
}
