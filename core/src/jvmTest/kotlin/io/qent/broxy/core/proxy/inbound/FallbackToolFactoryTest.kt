package io.qent.broxy.core.proxy.inbound

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.PromptArgument
import io.qent.broxy.core.mcp.PromptDescriptor
import io.qent.broxy.core.mcp.ResourceDescriptor
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FallbackToolFactoryTest {
    @Test
    fun prompt_fallback_tools_call_backend_with_arguments() {
        val prompt =
            PromptDescriptor(
                name = "greet",
                description = "Greeting prompt",
                arguments =
                    listOf(
                        PromptArgument(
                            name = "name",
                            description = "User name",
                            title = "Name",
                            required = true,
                        ),
                    ),
            )
        val backend =
            ProxyBackend(
                callTool = { _, _ -> Result.failure(IllegalStateException("unused")) },
                getPrompt = { name, args ->
                    assertEquals("greet", name)
                    assertEquals(mapOf("name" to "Ada", "count" to "1"), args)
                    Result.success(JsonObject(mapOf("text" to JsonPrimitive("hello"))))
                },
                readResource = { _ -> Result.failure(IllegalStateException("unused")) },
            )

        val tools = FallbackToolFactory.buildPromptFallbackTools(listOf(prompt), backend, NoopLogger)
        val tool = tools.single()
        val request =
            CallToolRequest(
                CallToolRequestParams(
                    name = tool.tool.name,
                    arguments =
                        buildJsonObject {
                            put("name", JsonPrimitive("Ada"))
                            put("count", JsonPrimitive(1))
                            put("skip", JsonNull)
                        },
                    meta = null,
                ),
            )

        val result = runBlocking { tool.handler(request) }

        assertEquals(false, result.isError)
        assertNotNull(result.structuredContent)
        assertTrue(result.structuredContent.toString().contains("hello"))
        assertEquals("prompt_greet", tool.tool.name)
    }

    @Test
    fun resource_fallback_tools_return_error_on_failure() {
        val resource = ResourceDescriptor(name = "doc", uri = "file://{folder}/{id}", description = "Doc")
        val backend =
            ProxyBackend(
                callTool = { _, _ -> Result.failure(IllegalStateException("unused")) },
                getPrompt = { _, _ -> Result.failure(IllegalStateException("unused")) },
                readResource = { key -> Result.failure(IllegalStateException("missing $key")) },
            )

        val tools = FallbackToolFactory.buildResourceFallbackTools(listOf(resource), backend, NoopLogger)
        val tool = tools.single()
        val request =
            CallToolRequest(
                CallToolRequestParams(
                    name = tool.tool.name,
                    arguments = JsonObject(emptyMap()),
                    meta = null,
                ),
            )

        val result = runBlocking { tool.handler(request) }

        assertEquals(true, result.isError)
        assertTrue(result.structuredContent.toString().contains("missing"))
        assertEquals("resource_file://{folder}/{id}", tool.tool.name)
        assertEquals(listOf("folder", "id"), tool.tool.inputSchema.required)
    }

    private object NoopLogger : Logger {
        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(
            message: String,
            throwable: Throwable?,
        ) = Unit

        override fun error(
            message: String,
            throwable: Throwable?,
        ) = Unit
    }
}
