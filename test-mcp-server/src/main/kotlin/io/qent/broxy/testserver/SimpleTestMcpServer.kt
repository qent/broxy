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
import io.ktor.server.response.respondText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.Prompt
import io.modelcontextprotocol.kotlin.sdk.types.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.types.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

fun main(args: Array<String>) {
    SimpleTestMcpServer(ServerCliOptions.parse(args)).start()
}

class SimpleTestMcpServer(
    private val options: ServerCliOptions = ServerCliOptions()
) {
    fun start() {
        when (options.mode) {
            ServerCliOptions.Mode.STDIO -> runBlocking { startStdio() }
            ServerCliOptions.Mode.HTTP_STREAMABLE -> startHttpStreamable()
        }
    }

    private suspend fun startStdio() {
        val server = buildServer()
        val transport = StdioServerTransport(
            System.`in`.asSource().buffered(),
            System.out.asSink().buffered()
        )
        val shutdownSignal = CompletableDeferred<Unit>()
        transport.onClose {
            if (!shutdownSignal.isCompleted) {
                shutdownSignal.complete(Unit)
            }
        }
        System.err.println("SimpleTestMcpServer: waiting for STDIO client")
        try {
            server.createSession(transport)
            shutdownSignal.await()
        } catch (t: Throwable) {
            System.err.println("SimpleTestMcpServer: STDIO connection failed - ${t.message}")
            throw t
        } finally {
            runCatching { transport.close() }
            System.err.println("SimpleTestMcpServer: STDIO connection closed")
        }
    }

    private fun startHttpStreamable() {
        val normalizedPath = normalizePath(options.path)
        println("Starting HTTP Streamable test server on http://${options.host}:${options.port}${normalizedPath.display}")
        val server = buildServer()
        val sessions = StreamableHttpSessionRegistry(server)
        embeddedServer(
            Netty,
            host = options.host,
            port = options.port
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

    private fun Route.mountStreamableHttpRoute(
        sessions: StreamableHttpSessionRegistry
    ) {
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
            val message = runCatching { McpJson.decodeFromString<JSONRPCMessage>(body) }
                .getOrElse { error ->
                    call.respond(HttpStatusCode.BadRequest, "Invalid MCP message: ${error.message ?: error::class.simpleName}")
                    return@post
                }

            when (message) {
                is JSONRPCRequest -> {
                    val response = runCatching { session.transport.awaitResponse(message) }
                        .getOrElse { error ->
                            val status =
                                if (error is TimeoutCancellationException) HttpStatusCode.RequestTimeout else HttpStatusCode.InternalServerError
                            call.respond(status, error.message ?: "Failed to handle MCP request")
                            return@post
                        }
                    call.respondText(
                        text = McpJson.encodeToString(JSONRPCMessage.serializer(), response),
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.OK
                    )
                }
                else -> {
                    runCatching { session.transport.handleMessage(message) }
                    call.respond(HttpStatusCode.OK)
                }
            }
        }
    }

    private fun buildServer(): Server {
        val server = Server(
            serverInfo = Implementation(name = SERVER_NAME, version = SERVER_VERSION),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    // Provide explicit initialize capabilities per MCP spec
                    prompts = ServerCapabilities.Prompts(listChanged = false),
                    resources = ServerCapabilities.Resources(subscribe = false, listChanged = false),
                    tools = ServerCapabilities.Tools(listChanged = false)
                )
            )
        )
        registerTools(server)
        registerResources(server)
        registerPrompts(server)
        return server
    }

    private fun registerTools(server: Server) {
        server.addTool(
            name = ADD_TOOL_NAME,
            title = "Add Numbers",
            description = "Adds two numbers together",
            inputSchema = ToolSchema(),
            outputSchema = null,
            toolAnnotations = null
        ) { req ->
            val args = req.arguments ?: JsonObject(emptyMap())
            val a = args.value("a")
            val b = args.value("b")
            CallToolResult(
                content = listOf(
                    TextContent("$a + $b = ${a + b}")
                ),
                structuredContent = JsonObject(
                    mapOf(
                        "operation" to JsonPrimitive("addition"),
                        "result" to JsonPrimitive(a + b)
                    )
                ),
                isError = false,
                meta = JsonObject(emptyMap())
            )
        }

        server.addTool(
            name = SUBTRACT_TOOL_NAME,
            title = "Subtract Numbers",
            description = "Subtracts the second number from the first",
            inputSchema = ToolSchema(),
            outputSchema = null,
            toolAnnotations = null
        ) { req ->
            val args = req.arguments ?: JsonObject(emptyMap())
            val a = args.value("a")
            val b = args.value("b")
            CallToolResult(
                content = listOf(
                    TextContent("$a - $b = ${a - b}")
                ),
                structuredContent = JsonObject(
                    mapOf(
                        "operation" to JsonPrimitive("subtraction"),
                        "result" to JsonPrimitive(a - b)
                    )
                ),
                isError = false,
                meta = JsonObject(emptyMap())
            )
        }
    }

    private fun registerResources(server: Server) {
        server.addResource(
            uri = RESOURCE_ALPHA_URI,
            name = "alpha",
            description = "Alpha sample text",
            mimeType = "text/plain"
        ) {
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        text = "Alpha resource content",
                        uri = RESOURCE_ALPHA_URI,
                        mimeType = "text/plain"
                    )
                ),
                meta = JsonObject(emptyMap())
            )
        }

        server.addResource(
            uri = RESOURCE_BETA_URI,
            name = "beta",
            description = "Beta sample text",
            mimeType = "text/plain"
        ) {
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        text = "Beta resource content",
                        uri = RESOURCE_BETA_URI,
                        mimeType = "text/plain"
                    )
                ),
                meta = JsonObject(emptyMap())
            )
        }
    }

    private fun registerPrompts(server: Server) {
        val argument = PromptArgument(
            name = "name",
            description = "Name to include in the response",
            required = true
        )

        server.addPrompt(
            Prompt(
                name = HELLO_PROMPT,
                description = "Says hello",
                arguments = listOf(argument)
            )
        ) { req ->
            GetPromptResult(
                description = "Friendly hello",
                messages = listOf(
                    PromptMessage(
                        role = Role.Assistant,
                        content = TextContent("Hello ${req.arguments?.get("name") ?: "friend"}!")
                    )
                ),
                meta = JsonObject(emptyMap())
            )
        }

        server.addPrompt(
            Prompt(
                name = BYE_PROMPT,
                description = "Says goodbye",
                arguments = listOf(argument)
            )
        ) { req ->
            GetPromptResult(
                description = "Friendly goodbye",
                messages = listOf(
                    PromptMessage(
                        role = Role.Assistant,
                        content = TextContent("Bye ${req.arguments?.get("name") ?: "friend"}!")
                    )
                ),
                meta = JsonObject(emptyMap())
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

    companion object {
        private const val SERVER_NAME = "broxy-test-mcp"
        private const val SERVER_VERSION = "0.0.1"
        private const val ADD_TOOL_NAME = "add"
        private const val SUBTRACT_TOOL_NAME = "subtract"
        private const val RESOURCE_ALPHA_URI = "test://resource/alpha"
        private const val RESOURCE_BETA_URI = "test://resource/beta"
        private const val HELLO_PROMPT = "hello"
        private const val BYE_PROMPT = "bye"
        private const val MCP_SESSION_ID_HEADER = "mcp-session-id"
    }
}

private data class NormalizedPath(
    val display: String,
    val routeSegments: String
)

private const val REQUEST_TIMEOUT_MILLIS = 60_000L

private class StreamableHttpSessionRegistry(
    private val server: Server
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
    val session: io.modelcontextprotocol.kotlin.sdk.server.ServerSession
)

private class StreamableHttpServerTransport : AbstractTransport() {
    val sessionId: String = java.util.UUID.randomUUID().toString()
    private val responseWaiters = ConcurrentHashMap<RequestId, CompletableDeferred<JSONRPCResponse>>()
    @Volatile private var started: Boolean = false

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

    override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
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
    val path: String = DEFAULT_PATH
) {
    enum class Mode {
        STDIO,
        HTTP_STREAMABLE
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
                        val value = args.getOrNull(++index) ?: error("Missing value for --mode")
                        mode = when (value.lowercase()) {
                            "stdio" -> Mode.STDIO
                            "http", "http-streamable", "streamable-http" -> Mode.HTTP_STREAMABLE
                            // Backward compatibility for older scripts
                            "http-sse" -> Mode.HTTP_STREAMABLE
                            else -> error("Unsupported mode '$value'. Use 'stdio' or 'streamable-http'.")
                        }
                    }
                    "--host" -> host = args.getOrNull(++index) ?: error("Missing value for --host")
                    "--port" -> {
                        val value = args.getOrNull(++index) ?: error("Missing value for --port")
                        port = value.toIntOrNull() ?: error("Invalid port '$value'")
                    }
                    "--path" -> path = args.getOrNull(++index) ?: error("Missing value for --path")
                    else -> error("Unknown argument '$arg'")
                }
                index++
            }

            return ServerCliOptions(
                mode = mode,
                host = host,
                port = port,
                path = path.ifBlank { DEFAULT_PATH }
            )
        }
    }
}
