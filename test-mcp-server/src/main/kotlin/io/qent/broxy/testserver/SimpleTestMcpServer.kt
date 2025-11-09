package io.qent.broxy.testserver

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
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

fun main() {
    SimpleTestMcpServer().start()
}

class SimpleTestMcpServer {
    fun start() = runBlocking {
        val server = buildServer()
        val transport = StdioServerTransport(
            System.`in`.asSource().buffered(),
            System.out.asSink().buffered()
        )
        server.connect(transport)
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

    companion object {
        private const val SERVER_NAME = "broxy-test-mcp"
        private const val SERVER_VERSION = "0.0.1"
        private const val ADD_TOOL_NAME = "add"
        private const val SUBTRACT_TOOL_NAME = "subtract"
        private const val RESOURCE_ALPHA_URI = "test://resource/alpha"
        private const val RESOURCE_BETA_URI = "test://resource/beta"
        private const val HELLO_PROMPT = "hello"
        private const val BYE_PROMPT = "bye"
    }
}
