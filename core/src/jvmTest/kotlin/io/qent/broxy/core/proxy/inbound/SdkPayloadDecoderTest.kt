package io.qent.broxy.core.proxy.inbound

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SdkPayloadDecoderTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decode_call_tool_result_infers_missing_content_type() {
        val element =
            json.parseToJsonElement(
                """
                {
                  "content": [
                    { "text": "hello" }
                  ],
                  "structuredContent": { "ok": true }
                }
                """.trimIndent(),
            )

        val result = decodeCallToolResult(json, element)

        assertEquals(1, result.content.size)
        val text = assertIs<TextContent>(result.content.first())
        assertEquals("hello", text.text)
    }

    @Test
    fun decode_call_tool_result_infers_image_from_mime_type() {
        val element =
            json.parseToJsonElement(
                """
                {
                  "content": [
                    { "data": "abc", "mimeType": "image/png" }
                  ],
                  "structuredContent": { "ok": true }
                }
                """.trimIndent(),
            )

        val result = decodeCallToolResult(json, element)

        assertEquals(1, result.content.size)
        assertNotNull(result.structuredContent)
    }

    @Test
    fun decode_call_tool_result_normalizes_mixed_content_entries() {
        val element =
            json.parseToJsonElement(
                """
                {
                  "content": [
                    { "type": "text", "text": "a" },
                    { "text": "b" },
                    { "data": "abc", "mimeType": "image/png" }
                  ],
                  "structuredContent": { "ok": true }
                }
                """.trimIndent(),
            )

        val result = decodeCallToolResult(json, element)

        assertEquals(3, result.content.size)
        val encoded = Json.encodeToJsonElement(CallToolResult.serializer(), result)
        val content = encoded.jsonObject["content"]?.jsonArray ?: error("missing content")
        val missingType = content.any { (it as JsonObject)["type"] == null }
        assertTrue(!missingType)
    }

    @Test
    fun decode_prompt_result_normalizes_message_content() {
        val element =
            json.parseToJsonElement(
                """
                {
                  "messages": [
                    { "role": "assistant", "content": { "text": "hi" } }
                  ]
                }
                """.trimIndent(),
            )

        val result = decodePromptResult(json, element)

        assertIs<GetPromptResult>(result)
        assertEquals(1, result.messages.size)
    }

    @Test
    fun fallback_call_tool_result_uses_meta_and_error_flag() {
        val raw =
            buildJsonObject {
                put(
                    "content",
                    json.parseToJsonElement("[{\"text\":\"fail\"}]"),
                )
                put(
                    "_meta",
                    buildJsonObject {
                        put("trace", JsonPrimitive("t1"))
                    },
                )
                put("isError", JsonPrimitive(true))
            }

        val result = fallbackCallToolResult(raw)

        assertEquals(true, result.isError)
        assertTrue(result.meta.toString().contains("trace"))
        assertEquals(1, result.content.size)
        assertIs<TextContent>(result.content.first())
    }

    @Test
    fun fallback_call_tool_result_wraps_raw_when_not_object() {
        val raw = JsonNull

        val result = fallbackCallToolResult(raw)

        assertIs<CallToolResult>(result)
        assertTrue(result.structuredContent.toString().contains("raw"))
    }
}
