package io.qent.broxy.ui.adapter.clients

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class GeminiCliClientConnectorTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun connectWritesSseEndpoint() =
        runTest {
            val baseDir = Files.createTempDirectory("gemini-cli")
            val configPath = baseDir.resolve("settings.json")
            Files.writeString(configPath, "{}")
            val connector = GeminiCliClientConnector(baseDir = baseDir)

            connector.connect(AiClientConnectionRequest("http://localhost:3335/mcp")).getOrThrow()

            val root = json.parseToJsonElement(Files.readString(configPath)).jsonObject
            val servers = root["mcpServers"]?.jsonObject
            val broxy = servers?.get("broxy")?.jsonObject
            assertNotNull(broxy)
            val url = broxy["url"]?.jsonPrimitive?.content
            assertEquals("http://localhost:3335/sse", url)
        }
}
