package io.qent.broxy.ui.adapter.remote.ws

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

class ProxyWebSocketTransportTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun describeJsonRpcPayloadSurfacesSchemasAndCapabilities() {
        val payload =
            json.parseToJsonElement(
                """
                {
                  "jsonrpc": "2.0",
                  "id": "1",
                  "result": {
                    "tools": [
                      {
                        "name": "s1:search",
                        "input_schema": {
                          "type": "object",
                          "properties": {
                            "query": { "type": "string" },
                            "top_k": { "type": "integer" }
                          },
                          "required": ["query"]
                        },
                        "output_schema": {
                          "type": "object",
                          "properties": {
                            "results": { "type": "array" }
                          }
                        }
                      }
                    ],
                    "prompts": [
                      { "name": "hello" }
                    ],
                    "resources": [
                      { "name": "doc", "uri": "docs/{id}" }
                    ]
                  }
                }
                """.trimIndent(),
            )

        val summary = describeJsonRpcPayload(payload)

        assertTrue(summary.contains("tools=1"), summary)
        assertTrue(summary.contains("tool_names=s1:search"), summary)
        assertTrue(
            summary.contains("input_schema_fields=") && summary.contains("query") && summary.contains("top_k"),
            summary,
        )
        assertTrue(summary.contains("output_schema_fields=") && summary.contains("results"), summary)
        assertTrue(summary.contains("prompts=1"), summary)
        assertTrue(summary.contains("resources=1"), summary)
    }

    @Test
    fun describeJsonRpcPayloadShowsCallShapes() {
        val request =
            json.parseToJsonElement(
                """
                {
                  "jsonrpc": "2.0",
                  "id": "42",
                  "method": "tools/call",
                  "params": {
                    "name": "s1:search",
                    "arguments": {
                      "query": "kotlin",
                      "top_k": 5
                    },
                    "meta": {
                      "trace_id": "abc123"
                    }
                  }
                }
                """.trimIndent(),
            )
        val requestSummary = describeJsonRpcPayload(request)
        assertTrue(requestSummary.contains("target=s1:search"), requestSummary)
        assertTrue(
            requestSummary.contains("args=") && requestSummary.contains("query") && requestSummary.contains("top_k"),
            requestSummary,
        )
        assertTrue(requestSummary.contains("meta=trace_id"), requestSummary)

        val response =
            json.parseToJsonElement(
                """
                {
                  "jsonrpc": "2.0",
                  "id": "42",
                  "result": {
                    "content": [
                      { "type": "text", "text": "ok" }
                    ],
                    "structured_content": {
                      "result": { "count": 1 }
                    },
                    "meta": {
                      "trace_id": "abc123",
                      "duration_ms": 12
                    },
                    "is_error": false
                  }
                }
                """.trimIndent(),
            )
        val responseSummary = describeJsonRpcPayload(response)
        assertTrue(responseSummary.contains("content=1"), responseSummary)
        assertTrue(responseSummary.contains("content_types=text"), responseSummary)
        assertTrue(responseSummary.contains("structured_keys=") && responseSummary.contains("result"), responseSummary)
        assertTrue(
            responseSummary.contains("meta_keys=") && responseSummary.contains("trace_id") &&
                responseSummary.contains(
                    "duration_ms",
                ),
            responseSummary,
        )
        assertTrue(responseSummary.contains("is_error=false"), responseSummary)
    }
}
