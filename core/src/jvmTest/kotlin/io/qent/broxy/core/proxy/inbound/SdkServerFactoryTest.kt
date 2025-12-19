package io.qent.broxy.core.proxy.inbound

import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.qent.broxy.core.mcp.PromptDescriptor
import io.qent.broxy.core.mcp.ResourceDescriptor
import io.qent.broxy.core.mcp.ToolDescriptor
import io.qent.broxy.core.utils.ConsoleLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities as SdkServerCapabilities
import io.qent.broxy.core.mcp.ServerCapabilities as ProxyServerCapabilities

class SdkServerFactoryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes content without explicit type by inferring text`() {
        val element =
            json.parseToJsonElement(
                """
                {
                  "content": [
                    { "text": "hello world" }
                  ],
                  "structuredContent": { "foo": "bar" }
                }
                """.trimIndent(),
            )

        val result = decodeWithFallback(element)

        assertEquals(1, result.content.size)
        val textContent = assertIs<TextContent>(result.content.first())
        assertEquals("hello world", textContent.text)
    }

    @Test
    fun `fallback preserves payload when decoding content fails`() {
        val element =
            json.parseToJsonElement(
                """
                {
                  "content": [
                    { "text": { "timezone": "UTC", "date_time": "2024-06-01T12:00:00Z" } }
                  ],
                  "structuredContent": { "foo": "bar" }
                }
                """.trimIndent(),
            )

        val callResult = decodeWithFallback(element)

        assertEquals(1, callResult.content.size)
        val textContent = assertIs<TextContent>(callResult.content.first())
        assertFalse(textContent.text.isNullOrBlank())
        assertTrue(textContent.text!!.contains("timezone"))
    }

    @Test
    fun `syncSdkServer replaces registered capabilities snapshot`() {
        val server =
            Server(
                serverInfo = Implementation(name = "test", version = "0"),
                options =
                    ServerOptions(
                        capabilities =
                            SdkServerCapabilities(
                                prompts = SdkServerCapabilities.Prompts(listChanged = false),
                                resources = SdkServerCapabilities.Resources(listChanged = false, subscribe = false),
                                tools = SdkServerCapabilities.Tools(listChanged = false),
                                logging = SdkServerCapabilities.Logging,
                            ),
                    ),
            )

        val backend =
            ProxyBackend(
                callTool = { _, _ -> Result.success(JsonObject(emptyMap())) },
                getPrompt = { _, _ -> Result.success(JsonObject(emptyMap())) },
                readResource = { _ -> Result.success(JsonObject(emptyMap())) },
            )

        syncSdkServer(
            server = server,
            capabilities =
                ProxyServerCapabilities(
                    tools = listOf(ToolDescriptor(name = "s1:t1")),
                    prompts = listOf(PromptDescriptor(name = "p1")),
                    resources = listOf(ResourceDescriptor(name = "r1", uri = "file:///r1")),
                ),
            backend = backend,
            logger = ConsoleLogger,
        )

        assertTrue(server.tools.containsKey("s1:t1"))
        assertTrue(server.prompts.containsKey("p1"))
        assertTrue(server.resources.containsKey("file:///r1"))

        syncSdkServer(
            server = server,
            capabilities =
                ProxyServerCapabilities(
                    tools = listOf(ToolDescriptor(name = "s2:t2")),
                    prompts = emptyList(),
                    resources = listOf(ResourceDescriptor(name = "r2", uri = null)),
                ),
            backend = backend,
            logger = ConsoleLogger,
        )

        assertFalse(server.tools.containsKey("s1:t1"))
        assertTrue(server.tools.containsKey("s2:t2"))
        assertTrue(server.prompts.isEmpty())
        assertFalse(server.resources.containsKey("file:///r1"))
        assertTrue(server.resources.containsKey("r2"))
    }

    private fun decodeWithFallback(element: kotlinx.serialization.json.JsonElement) =
        runCatching { decodeCallToolResult(json, element) }
            .getOrElse { fallbackCallToolResult(element) }
}
