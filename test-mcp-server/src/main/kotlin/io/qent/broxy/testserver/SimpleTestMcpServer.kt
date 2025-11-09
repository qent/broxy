package io.qent.broxy.testserver

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.Prompt
import io.modelcontextprotocol.kotlin.sdk.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.Role
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.SseServerTransport
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
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
            ServerCliOptions.Mode.HTTP_SSE -> startHttpSse()
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
            server.connect(transport)
            shutdownSignal.await()
        } catch (t: Throwable) {
            System.err.println("SimpleTestMcpServer: STDIO connection failed - ${t.message}")
            throw t
        } finally {
            runCatching { transport.close() }
            System.err.println("SimpleTestMcpServer: STDIO connection closed")
        }
    }

    private fun startHttpSse() {
        val normalizedPath = normalizePath(options.path)
        val registry = OutboundSseRegistry()
        println("Starting HTTP SSE test server on http://${options.host}:${options.port}${normalizedPath.display}")
        embeddedServer(
            Netty,
            host = options.host,
            port = options.port
        ) {
            install(CallLogging)
            install(SSE)
            routing {
                val serverFactory: ServerSSESession.() -> Server = { buildServer() }
                if (normalizedPath.routeSegments.isBlank()) {
                    mountHttpEndpoints("", serverFactory, registry)
                } else {
                    route("/${normalizedPath.routeSegments}") {
                        mountHttpEndpoints(normalizedPath.routeSegments, serverFactory, registry)
                    }
                }
            }
        }.start(wait = true)
    }

    private fun Route.mountHttpEndpoints(
        endpointSegments: String,
        serverFactory: ServerSSESession.() -> Server,
        registry: OutboundSseRegistry
    ) {
        sse {
            val transport = SseServerTransport(endpointSegments, this)
            registry.add(transport)
            val server = serverFactory(this)
            server.onClose { registry.remove(transport.sessionId) }
            try {
                server.connect(transport)
            } catch (t: Throwable) {
                registry.remove(transport.sessionId)
                throw t
            }
        }
        post {
            val sessionId = call.request.queryParameters[SESSION_ID_PARAM]
            if (sessionId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "sessionId query parameter is required")
                return@post
            }
            val transport = registry[sessionId]
            if (transport == null) {
                call.respond(HttpStatusCode.NotFound, "Session '$sessionId' not found")
                return@post
            }
            transport.handlePostMessage(call)
        }
    }

    private fun buildServer(): Server {
        val server = Server(
            serverInfo = Implementation(name = SERVER_NAME, version = SERVER_VERSION),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    prompts = ServerCapabilities.Prompts(listChanged = null),
                    resources = ServerCapabilities.Resources(subscribe = null, listChanged = null),
                    tools = ServerCapabilities.Tools(listChanged = null)
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
            inputSchema = Tool.Input(),
            outputSchema = null,
            toolAnnotations = null
        ) { req ->
            val a = req.arguments.value("a")
            val b = req.arguments.value("b")
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
                _meta = JsonObject(emptyMap())
            )
        }

        server.addTool(
            name = SUBTRACT_TOOL_NAME,
            title = "Subtract Numbers",
            description = "Subtracts the second number from the first",
            inputSchema = Tool.Input(),
            outputSchema = null,
            toolAnnotations = null
        ) { req ->
            val a = req.arguments.value("a")
            val b = req.arguments.value("b")
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
                _meta = JsonObject(emptyMap())
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
                _meta = JsonObject(emptyMap())
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
                _meta = JsonObject(emptyMap())
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
                        role = Role.assistant,
                        content = TextContent("Hello ${req.arguments?.get("name") ?: "friend"}!")
                    )
                ),
                _meta = JsonObject(emptyMap())
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
                        role = Role.assistant,
                        content = TextContent("Bye ${req.arguments?.get("name") ?: "friend"}!")
                    )
                ),
                _meta = JsonObject(emptyMap())
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
        private const val SESSION_ID_PARAM = "sessionId"
    }
}

private data class NormalizedPath(
    val display: String,
    val routeSegments: String
)

private class OutboundSseRegistry {
    private val transports = ConcurrentHashMap<String, SseServerTransport>()

    fun add(transport: SseServerTransport) {
        transports[transport.sessionId] = transport
    }

    operator fun get(sessionId: String?): SseServerTransport? =
        sessionId?.let { transports[it] }

    fun remove(sessionId: String?) {
        if (sessionId.isNullOrBlank()) return
        transports.remove(sessionId)
    }
}

data class ServerCliOptions(
    val mode: Mode = Mode.STDIO,
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    val path: String = DEFAULT_PATH
) {
    enum class Mode {
        STDIO,
        HTTP_SSE
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
                            "http-sse", "http" -> Mode.HTTP_SSE
                            else -> error("Unsupported mode '$value'. Use 'stdio' or 'http-sse'.")
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
