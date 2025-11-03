package io.qent.broxy.core.proxy.inbound

import io.modelcontextprotocol.kotlin.sdk.TextContent
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SdkServerFactoryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes content without explicit type by inferring text`() {
        val element = json.parseToJsonElement(
            """
            {
              "content": [
                { "text": "hello world" }
              ],
              "structuredContent": { "foo": "bar" }
            }
            """.trimIndent()
        )

        val result = decodeWithFallback(element)

        assertEquals(1, result.content.size)
        val textContent = assertIs<TextContent>(result.content.first())
        assertEquals("hello world", textContent.text)
    }

    @Test
    fun `fallback preserves payload when decoding content fails`() {
        val element = json.parseToJsonElement(
            """
            {
              "content": [
                { "text": { "timezone": "UTC", "date_time": "2024-06-01T12:00:00Z" } }
              ],
              "structuredContent": { "foo": "bar" }
            }
            """.trimIndent()
        )

        val callResult = decodeWithFallback(element)

        assertEquals(1, callResult.content.size)
        val textContent = assertIs<TextContent>(callResult.content.first())
        assertFalse(textContent.text.isNullOrBlank())
        assertTrue(textContent.text!!.contains("timezone"))
    }

    private fun decodeWithFallback(element: kotlinx.serialization.json.JsonElement) =
        runCatching { decodeCallToolResult(json, element) }
            .getOrElse { fallbackCallToolResult(element) }
}
