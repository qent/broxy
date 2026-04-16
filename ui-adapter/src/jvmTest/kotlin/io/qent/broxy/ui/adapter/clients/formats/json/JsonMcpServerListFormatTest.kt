package io.qent.broxy.ui.adapter.clients.formats.json

import io.qent.broxy.ui.adapter.clients.common.BroxyServerEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JsonMcpServerListFormatTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun listServersReadsMcpServers() {
        val format = JsonMcpServerListFormat(serversKey = "mcpServers")
        val content =
            """
            {
              "mcpServers": {
                "one": { "url": "http://localhost:1111/mcp" },
                "two": { "command": "npx" }
              }
            }
            """.trimIndent()

        val servers = format.listServers(content)

        assertEquals(listOf("one", "two"), servers)
    }

    @Test
    fun listServersReadsCustomServersKey() {
        val format = JsonMcpServerListFormat(serversKey = "servers")
        val content =
            """
            {
              "servers": {
                "a": { "url": "http://localhost:1111/mcp" },
                "b": { "url": "http://localhost:2222/mcp" }
              }
            }
            """.trimIndent()

        val servers = format.listServers(content)

        assertEquals(listOf("a", "b"), servers)
    }

    @Test
    fun listServerEntriesParsesTransportAndMetadata() {
        val format = JsonMcpServerListFormat(serversKey = "mcpServers")
        val content =
            """
            {
              "mcpServers": {
                "alpha": {
                  "name": "Alpha",
                  "enabled": false,
                  "type": "ws",
                  "url": "wss://example.test/mcp",
                  "headers": {
                    "Authorization": "Bearer token"
                  },
                  "env": {
                    "TOKEN": "abc"
                  }
                }
              }
            }
            """.trimIndent()

        val entries = format.listServerEntries(content)

        assertEquals(1, entries.size)
        val entry = entries.first()
        assertEquals("alpha", entry.sourceServerId)
        assertEquals("Alpha", entry.name)
        assertEquals(false, entry.enabled)
        assertEquals("ws", entry.type)
        assertEquals("wss://example.test/mcp", entry.url)
        assertEquals("Bearer token", entry.headers["Authorization"])
        assertEquals("abc", entry.env["TOKEN"])
    }

    @Test
    fun listServerEntriesSupportsAliasFieldsAndDisabledFlag() {
        val format = JsonMcpServerListFormat(serversKey = "mcpServers")
        val content =
            """
            {
              "mcpServers": {
                "streamable": {
                  "type": "streamable-http",
                  "httpUrl": "https://example.test/mcp",
                  "headers": {
                    "Authorization": "Bearer token"
                  }
                },
                "windsurf": {
                  "serverUrl": "https://windsurf.example.test/mcp",
                  "disabled": true
                }
              }
            }
            """.trimIndent()

        val entries = format.listServerEntries(content)

        assertEquals(2, entries.size)
        assertEquals("streamable-http", entries[0].type)
        assertEquals("https://example.test/mcp", entries[0].url)
        assertEquals("Bearer token", entries[0].headers["Authorization"])
        assertEquals(false, entries[1].enabled)
        assertEquals("https://windsurf.example.test/mcp", entries[1].url)
    }

    @Test
    fun upsertBroxyPreservesOtherFields() {
        val format = JsonMcpServerListFormat(serversKey = "mcpServers")
        val input =
            """
            {
              "app": "demo",
              "mcpServers": {
                "other": {
                  "command": "echo"
                }
              },
              "extra": {
                "enabled": true
              }
            }
            """.trimIndent()

        val updated =
            format.upsertBroxy(
                content = input,
                entry = BroxyServerEntry.JsonEntry(JsonObject(mapOf("url" to JsonPrimitive("http://localhost:3335/mcp")))),
            )

        val root = json.parseToJsonElement(updated).jsonObject
        assertEquals("demo", root["app"]?.jsonPrimitive?.content)
        assertEquals(
            true,
            root["extra"]
                ?.jsonObject
                ?.get("enabled")
                ?.jsonPrimitive
                ?.boolean,
        )
        val servers = root["mcpServers"]?.jsonObject
        assertNotNull(servers)
        assertTrue(servers.containsKey("other"))
        assertEquals(
            "http://localhost:3335/mcp",
            servers["broxy"]
                ?.jsonObject
                ?.get("url")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun removeBroxyDeletesOnlyBroxyEntry() {
        val format = JsonMcpServerListFormat(serversKey = "mcpServers")
        val input =
            """
            {
              "mcpServers": {
                "broxy": { "url": "http://localhost:3335/mcp" },
                "other": { "url": "http://localhost:4444/mcp" }
              },
              "meta": "keep"
            }
            """.trimIndent()

        val updated = format.removeBroxy(content = input)
        val root = json.parseToJsonElement(updated).jsonObject
        val servers = root["mcpServers"]?.jsonObject
        assertNotNull(servers)
        assertFalse(servers.containsKey("broxy"))
        assertTrue(servers.containsKey("other"))
        assertEquals("keep", root["meta"]?.jsonPrimitive?.content)
    }

    @Test
    fun readBroxyStatusReturnsConfiguredUrl() {
        val format = JsonMcpServerListFormat(serversKey = "mcpServers")
        val input =
            """
            {
              "mcpServers": {
                "broxy": { "url": "http://localhost:3335/mcp" }
              }
            }
            """.trimIndent()

        val status = format.readBroxyStatus(input)

        assertTrue(status.isConfigured)
        assertEquals("http://localhost:3335/mcp", status.configuredUrl)
    }
}
