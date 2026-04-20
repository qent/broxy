package io.qent.broxy.core.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LogRedactorTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `redacts sensitive keys recursively`() {
        val input =
            json.parseToJsonElement(
                """
                {
                  "token": "abc123",
                  "nested": {
                    "password": "secret",
                    "safe": "ok"
                  },
                  "list": [
                    { "apiKey": "key1" },
                    { "value": "x" }
                  ]
                }
                """.trimIndent(),
            )

        val redacted = LogRedactor.redact(input)
        val obj = assertIs<JsonObject>(redacted)
        assertEquals("***", obj["token"]?.jsonPrimitive?.content)
        val nested = assertIs<JsonObject>(obj["nested"])
        assertEquals("***", nested["password"]?.jsonPrimitive?.content)
        assertEquals("ok", nested["safe"]?.jsonPrimitive?.content)
        val list = obj["list"] ?: error("Missing list")
        val first = list.jsonArray[0] as JsonObject
        assertEquals("***", first["apiKey"]?.jsonPrimitive?.content)
    }

    @Test
    fun `keeps non sensitive primitives`() {
        val input =
            json.parseToJsonElement(
                """
                {
                  "count": 2,
                  "enabled": true,
                  "name": "alpha"
                }
                """.trimIndent(),
            )

        val redacted = LogRedactor.redact(input)
        val obj = assertIs<JsonObject>(redacted)
        assertEquals(JsonPrimitive(2), obj["count"])
        assertEquals(JsonPrimitive(true), obj["enabled"])
        assertEquals(JsonPrimitive("alpha"), obj["name"])
    }

    @Test
    fun `redacts sensitive keys inside nested arrays`() {
        val input =
            json.parseToJsonElement(
                """
                {
                  "payload": [
                    { "token": "one" },
                    [
                      { "secret": "two" },
                      { "safe": "ok" }
                    ],
                    "plain"
                  ],
                  "meta": {
                    "items": [
                      { "key": "value" },
                      { "nested": [ { "password": "p" } ] }
                    ]
                  }
                }
                """.trimIndent(),
            )

        val redacted = LogRedactor.redact(input)
        val obj = assertIs<JsonObject>(redacted)
        val payload = obj["payload"]?.jsonArray ?: error("Missing payload")
        val first = payload[0] as JsonObject
        assertEquals("***", first["token"]?.jsonPrimitive?.content)
        val nestedArray = payload[1].jsonArray
        val firstNested = nestedArray[0] as JsonObject
        assertEquals("***", firstNested["secret"]?.jsonPrimitive?.content)
        val safeObj = nestedArray[1] as JsonObject
        assertEquals("ok", safeObj["safe"]?.jsonPrimitive?.content)
        val meta = assertIs<JsonObject>(obj["meta"])
        val items = meta["items"]?.jsonArray ?: error("Missing meta items")
        val firstItem = items[0] as JsonObject
        assertEquals("***", firstItem["key"]?.jsonPrimitive?.content)
        val nested = items[1] as JsonObject
        val nestedValues = nested["nested"]?.jsonArray ?: error("Missing nested array")
        val nestedValue = nestedValues[0] as JsonObject
        assertEquals("***", nestedValue["password"]?.jsonPrimitive?.content)
    }
}
