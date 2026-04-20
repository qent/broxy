package io.qent.broxy.ui.adapter.clients

import io.qent.broxy.ui.adapter.models.UiAiClientMissingConfigNotice
import io.qent.broxy.ui.adapter.models.UiAiClientNoticeSeverity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpJsonClientConnectorTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val descriptor =
        AiClientDescriptor(
            id = "test-client",
            name = "Test Client",
            description = "Test",
            iconId = "test",
            infoUrl = "https://example.com",
        )

    @Test
    fun loadStatusReportsMissingInstall() =
        runTest {
            val tempDir = Files.createTempDirectory("mcp-json-client")
            val missingDir = tempDir.resolve("missing")
            val connector = McpJsonClientConnector(descriptor = descriptor, baseDir = missingDir)

            val status = connector.loadStatus(AiClientConnectionRequest("http://localhost:3335/mcp")).getOrThrow()

            assertFalse(status.isConnected)
            assertFalse(status.canConnect)
            val notice = assertNotNull(status.notice)
            assertEquals(UiAiClientNoticeSeverity.Error, notice.severity)
            val missingNotice = notice as? UiAiClientMissingConfigNotice
            assertNotNull(missingNotice)
            assertEquals("Test Client", missingNotice.clientName)
        }

    @Test
    fun loadStatusAllowsConnectWhenFileMissing() =
        runTest {
            val baseDir = Files.createTempDirectory("mcp-json-client")
            val connector = McpJsonClientConnector(descriptor = descriptor, baseDir = baseDir)

            val status = connector.loadStatus(AiClientConnectionRequest("http://localhost:3335/mcp")).getOrThrow()

            assertFalse(status.isConnected)
            assertTrue(status.canConnect)
            assertNull(status.notice)
        }

    @Test
    fun loadStatusRequiresExistingConfigFile() =
        runTest {
            val baseDir = Files.createTempDirectory("mcp-json-client")
            val connector =
                McpJsonClientConnector(
                    descriptor = descriptor,
                    baseDir = baseDir,
                    requireConfigFile = true,
                )

            val status = connector.loadStatus(AiClientConnectionRequest("http://localhost:3335/mcp")).getOrThrow()

            assertFalse(status.isConnected)
            assertFalse(status.canConnect)
            val notice = assertNotNull(status.notice)
            assertEquals(UiAiClientNoticeSeverity.Error, notice.severity)
            val missingNotice = notice as? UiAiClientMissingConfigNotice
            assertNotNull(missingNotice)
            assertEquals("Test Client", missingNotice.clientName)
        }

    @Test
    fun connectFailsWhenConfigMissingAndRequired() =
        runTest {
            val baseDir = Files.createTempDirectory("mcp-json-client")
            val connector =
                McpJsonClientConnector(
                    descriptor = descriptor,
                    baseDir = baseDir,
                    requireConfigFile = true,
                )

            val result = connector.connect(AiClientConnectionRequest("http://localhost:3335/mcp"))

            assertTrue(result.isFailure)
            assertFalse(baseDir.resolve("mcp.json").exists())
        }

    @Test
    fun connectCreatesFileAndPreservesFields() =
        runTest {
            val baseDir = Files.createTempDirectory("mcp-json-client")
            val configPath = baseDir.resolve("mcp.json")
            Files.writeString(
                configPath,
                """
                {
                  "app": "demo",
                  "mcpServers": {
                    "other": {
                      "command": "echo",
                      "args": [
                        "hello"
                      ]
                    }
                  },
                  "extra": {
                    "enabled": true
                  }
                }
                """.trimIndent(),
            )
            val connector = McpJsonClientConnector(descriptor = descriptor, baseDir = baseDir)

            connector.connect(AiClientConnectionRequest("http://localhost:3335/mcp")).getOrThrow()

            val root = readRoot(configPath)
            assertEquals("demo", root["app"]?.jsonPrimitive?.content)
            assertEquals(
                true,
                root["extra"]
                    ?.jsonObject
                    ?.get("enabled")
                    ?.jsonPrimitive
                    ?.booleanOrNull,
            )
            val servers = root["mcpServers"]?.jsonObject
            assertNotNull(servers)
            assertTrue(servers.containsKey("other"))
            val broxy = servers["broxy"]?.jsonObject
            assertNotNull(broxy)
            assertEquals("http://localhost:3335/mcp", broxy["url"]?.jsonPrimitive?.content)
        }

    @Test
    fun disconnectRemovesBroxyOnly() =
        runTest {
            val baseDir = Files.createTempDirectory("mcp-json-client")
            val configPath = baseDir.resolve("mcp.json")
            Files.writeString(
                configPath,
                """
                {
                  "mcpServers": {
                    "broxy": {
                      "url": "http://localhost:3335/mcp"
                    },
                    "other": {
                      "url": "http://localhost:5555/mcp"
                    }
                  },
                  "meta": "keep"
                }
                """.trimIndent(),
            )
            val connector = McpJsonClientConnector(descriptor = descriptor, baseDir = baseDir)

            connector.disconnect(AiClientConnectionRequest("http://localhost:3335/mcp")).getOrThrow()

            val root = readRoot(configPath)
            assertEquals("keep", root["meta"]?.jsonPrimitive?.content)
            val servers = root["mcpServers"]?.jsonObject
            assertNotNull(servers)
            assertFalse(servers.containsKey("broxy"))
            assertTrue(servers.containsKey("other"))
        }

    @Test
    fun connectUsesCustomEntryProvider() =
        runTest {
            val baseDir = Files.createTempDirectory("mcp-json-client")
            val connector =
                McpJsonClientConnector(
                    descriptor = descriptor,
                    baseDir = baseDir,
                    broxyEntryProvider = {
                        JsonObject(
                            mapOf(
                                "command" to JsonPrimitive("broxy"),
                                "args" to JsonArray(listOf(JsonPrimitive("--stdio-proxy"))),
                            ),
                        )
                    },
                )

            connector.connect(AiClientConnectionRequest("http://localhost:3335/mcp")).getOrThrow()

            val root = readRoot(baseDir.resolve("mcp.json"))
            val broxy = root["mcpServers"]?.jsonObject?.get("broxy")?.jsonObject
            assertNotNull(broxy)
            assertEquals("broxy", broxy["command"]?.jsonPrimitive?.content)
            val args = broxy["args"]?.jsonArray
            assertNotNull(args)
            assertEquals("--stdio-proxy", args[0].jsonPrimitive.content)
        }

    @Test
    fun connectUsesCustomServersKey() =
        runTest {
            val baseDir = Files.createTempDirectory("mcp-json-client")
            val configPath = baseDir.resolve("mcp.json")
            Files.writeString(
                configPath,
                """
                {
                  "servers": {
                    "other": {
                      "url": "http://localhost:4444/mcp"
                    }
                  }
                }
                """.trimIndent(),
            )
            val connector = McpJsonClientConnector(descriptor = descriptor, baseDir = baseDir, serversKey = "servers")

            connector.connect(AiClientConnectionRequest("http://localhost:3335/mcp")).getOrThrow()

            val root = readRoot(configPath)
            assertNull(root["mcpServers"])
            val servers = root["servers"]?.jsonObject
            assertNotNull(servers)
            assertTrue(servers.containsKey("other"))
            val broxy = servers["broxy"]?.jsonObject
            assertNotNull(broxy)
            assertEquals("http://localhost:3335/mcp", broxy["url"]?.jsonPrimitive?.content)
        }

    @Test
    fun disconnectUsesCustomServersKey() =
        runTest {
            val baseDir = Files.createTempDirectory("mcp-json-client")
            val configPath = baseDir.resolve("mcp.json")
            Files.writeString(
                configPath,
                """
                {
                  "servers": {
                    "broxy": {
                      "url": "http://localhost:3335/mcp"
                    },
                    "other": {
                      "url": "http://localhost:4444/mcp"
                    }
                  }
                }
                """.trimIndent(),
            )
            val connector = McpJsonClientConnector(descriptor = descriptor, baseDir = baseDir, serversKey = "servers")

            connector.disconnect(AiClientConnectionRequest("http://localhost:3335/mcp")).getOrThrow()

            val root = readRoot(configPath)
            assertNull(root["mcpServers"])
            val servers = root["servers"]?.jsonObject
            assertNotNull(servers)
            assertFalse(servers.containsKey("broxy"))
            assertTrue(servers.containsKey("other"))
        }

    private fun readRoot(path: Path): JsonObject {
        val content = Files.readString(path)
        return json.parseToJsonElement(content).jsonObject
    }
}
