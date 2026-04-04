package io.qent.broxy.ui.adapter.clients

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AiClientConnectorsJvmTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun provideAiClientConnectors_returns_all_expected_connectors() {
        val connectors = provideAiClientConnectors()
        val ids = connectors.map { it.descriptor.id }.sorted()

        assertEquals(
            listOf(
                "antigravity",
                "claude",
                "claude-code",
                "cline",
                "codex",
                "cursor",
                "gemini-cli",
                "kilo",
                "kiro",
                "lmstudio",
                "roo-code",
                "vscode",
                "windsurf",
            ),
            ids,
        )
    }

    @Test
    fun default_constructors_produce_stable_descriptors_for_json_connectors() {
        val connectors =
            listOf(
                ClaudeClientConnector(),
                ClineClientConnector(),
                CursorClientConnector(),
                GoogleAntigravityClientConnector(),
                KiloCodeClientConnector(),
                KiroClientConnector(),
                LmStudioClientConnector(),
                RooCodeClientConnector(),
                VisualStudioCodeClientConnector(),
                WindsurfClientConnector(),
            )

        connectors.forEach { connector ->
            assertTrue(connector.descriptor.id.isNotBlank())
            assertTrue(connector.descriptor.name.isNotBlank())
            assertTrue(connector.descriptor.infoUrl.startsWith("https://"))
        }
    }

    @Test
    fun claude_connector_connect_writes_stdio_command_and_args() =
        runTest {
            val baseDir = Files.createTempDirectory("claude-client")
            val connector =
                ClaudeClientConnector(
                    baseDir = baseDir,
                    command = "/tmp/broxy",
                    args = listOf("--stdio-proxy", "--verbose"),
                )

            connector.connect(AiClientConnectionRequest("http://localhost:3335/mcp")).getOrThrow()

            val configPath = baseDir.resolve("claude_desktop_config.json")
            val root = json.parseToJsonElement(Files.readString(configPath)).jsonObject
            val servers = root["mcpServers"]?.jsonObject
            val broxy = servers?.get("broxy")?.jsonObject
            assertNotNull(broxy)
            assertEquals("/tmp/broxy", broxy["command"]?.jsonPrimitive?.content)
            assertEquals(
                listOf("--stdio-proxy", "--verbose"),
                broxy["args"]?.jsonArray?.map { it.jsonPrimitive.content },
            )
        }
}
