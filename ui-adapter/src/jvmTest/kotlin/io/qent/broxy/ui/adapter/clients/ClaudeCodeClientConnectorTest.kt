package io.qent.broxy.ui.adapter.clients

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ClaudeCodeClientConnectorTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun connectWritesTypeAndUrl() =
        runTest {
            val baseDir = Files.createTempDirectory("claude-code")
            val configPath = baseDir.resolve(".claude.json")
            Files.writeString(configPath, "{}")
            val connector = ClaudeCodeClientConnector(baseDir = baseDir)

            connector.connect(AiClientConnectionRequest("http://localhost:3335/mcp")).getOrThrow()

            val root = json.parseToJsonElement(Files.readString(configPath)).jsonObject
            val servers = root["mcpServers"]?.jsonObject
            val broxy = servers?.get("broxy")?.jsonObject
            assertNotNull(broxy)
            val type = broxy["type"]?.jsonPrimitive?.content
            val url = broxy["url"]?.jsonPrimitive?.content
            assertEquals("http", type)
            assertEquals("http://localhost:3335/mcp", url)
        }
}
