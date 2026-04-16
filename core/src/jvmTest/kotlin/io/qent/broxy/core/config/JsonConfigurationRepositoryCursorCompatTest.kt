package io.qent.broxy.core.config

import io.qent.broxy.core.models.AuthConfig
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.utils.ConfigurationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonConfigurationRepositoryCursorCompatTest {
    @Test
    fun loadMcpConfig_merges_stdio_envFile_then_inline_env() {
        val dir = Files.createTempDirectory("broxy-config")
        val envDir = Files.createDirectories(dir.resolve("env"))
        Files.writeString(
            envDir.resolve("server.env"),
            """
            # from file
            export TOKEN=${'$'}{TOKEN_FROM_FILE}
            FILE_ONLY="value-from-file"
            """.trimIndent(),
        )
        Files.writeString(
            dir.resolve("mcp.json"),
            """
            {
              "mcpServers": {
                "alpha": {
                  "command": "run",
                  "envFile": "env/server.env",
                  "env": {
                    "TOKEN": "${'$'}{TOKEN_FROM_INLINE}"
                  }
                }
              }
            }
            """.trimIndent(),
        )

        val repo =
            JsonConfigurationRepository(
                baseDir = dir,
                logger = ConfigTestLogger,
                envResolver =
                    EnvironmentVariableResolver(
                        envProvider =
                            {
                                mapOf(
                                    "TOKEN_FROM_FILE" to "file-token",
                                    "TOKEN_FROM_INLINE" to "inline-token",
                                )
                            },
                        logger = ConfigTestLogger,
                    ),
            )

        val config = repo.loadMcpConfig()
        val server = config.servers.first { it.id == "alpha" }
        assertTrue(server.transport is TransportConfig.StdioTransport)
        assertEquals("env/server.env", server.envFile)
        assertEquals("inline-token", server.env["TOKEN"])
        assertEquals("value-from-file", server.env["FILE_ONLY"])
    }

    @Test
    fun loadMcpConfig_ignores_envFile_for_remote_transport() {
        val dir = Files.createTempDirectory("broxy-config")
        Files.writeString(
            dir.resolve("mcp.json"),
            """
            {
              "mcpServers": {
                "remote": {
                  "type": "http",
                  "url": "https://example.com/mcp",
                  "envFile": "missing.env"
                }
              }
            }
            """.trimIndent(),
        )

        val repo = JsonConfigurationRepository(baseDir = dir, logger = ConfigTestLogger)
        val config = repo.loadMcpConfig()

        val server = config.servers.first { it.id == "remote" }
        assertTrue(server.transport is TransportConfig.StreamableHttpTransport)
        assertNull(server.envFile)
    }

    @Test
    fun loadMcpConfig_fails_when_stdio_envFile_is_missing() {
        val dir = Files.createTempDirectory("broxy-config")
        Files.writeString(
            dir.resolve("mcp.json"),
            """
            {
              "mcpServers": {
                "alpha": {
                  "type": "stdio",
                  "command": "run",
                  "envFile": "missing.env"
                }
              }
            }
            """.trimIndent(),
        )
        val repo = JsonConfigurationRepository(baseDir = dir, logger = ConfigTestLogger)

        assertFailsWith<ConfigurationException> {
            repo.loadMcpConfig()
        }
    }

    @Test
    fun saveMcpConfig_writes_canonical_oauth_type_and_envFile_fields() {
        val dir = Files.createTempDirectory("broxy-config")
        val repo = JsonConfigurationRepository(baseDir = dir, logger = ConfigTestLogger)
        val config =
            McpServersConfig(
                servers =
                    listOf(
                        McpServerConfig(
                            id = "stdio-server",
                            name = "stdio-server",
                            transport = TransportConfig.StdioTransport(command = "run"),
                            env = mapOf("TOKEN" to "token"),
                            envFile = ".env.local",
                        ),
                        McpServerConfig(
                            id = "remote-server",
                            name = "remote-server",
                            transport = TransportConfig.StreamableHttpTransport(url = "https://example.com/mcp"),
                            auth =
                                AuthConfig.OAuth(
                                    clientId = "client",
                                    redirectUri = "http://localhost:8080/callback",
                                ),
                        ),
                    ),
                mcpFilePath = dir.resolve("mcp.json").toString(),
            )

        repo.saveMcpConfig(config)

        val parsed = Json.parseToJsonElement(Files.readString(dir.resolve("mcp.json"))).jsonObject
        val servers = parsed.getValue("mcpServers").jsonObject
        val stdio = servers.getValue("stdio-server").jsonObject
        assertEquals("stdio", stdio.getValue("type").jsonPrimitive.content)
        assertEquals(".env.local", stdio.getValue("envFile").jsonPrimitive.content)
        assertTrue("oauth" !in stdio)
        assertTrue("auth" !in stdio)
        assertTrue("headersHelper" !in stdio)

        val remote = servers.getValue("remote-server").jsonObject
        assertEquals("http", remote.getValue("type").jsonPrimitive.content)
        assertTrue("oauth" in remote)
        assertTrue("auth" !in remote)
        assertTrue("headersHelper" !in remote)
    }

    @Test
    fun roundTrip_cursor_and_claude_mcp_json_stays_connectable() {
        val dir = Files.createTempDirectory("broxy-config")
        Files.writeString(
            dir.resolve("mcp.json"),
            """
            {
              "mcpServers": {
                "cursor-stdio": {
                  "command": "run",
                  "envFile": ".env"
                },
                "claude-http": {
                  "type": "http",
                  "url": "https://example.com/mcp",
                  "oauth": {
                    "type": "oauth",
                    "clientId": "client",
                    "redirectUri": "http://localhost:8080/callback"
                  }
                }
              }
            }
            """.trimIndent(),
        )
        Files.writeString(dir.resolve(".env"), "TOKEN=token")

        val repo =
            JsonConfigurationRepository(
                baseDir = dir,
                logger = ConfigTestLogger,
                envResolver = EnvironmentVariableResolver(envProvider = { emptyMap() }, logger = ConfigTestLogger),
            )

        val loaded = repo.loadMcpConfig()
        repo.saveMcpConfig(loaded)

        val parsed = Json.parseToJsonElement(Files.readString(dir.resolve("mcp.json"))).jsonObject
        val servers = parsed.getValue("mcpServers").jsonObject
        val cursor = servers.getValue("cursor-stdio").jsonObject
        assertEquals("stdio", cursor.getValue("type").jsonPrimitive.content)
        assertEquals(".env", cursor.getValue("envFile").jsonPrimitive.content)
        assertTrue("oauth" !in cursor)

        val claude = servers.getValue("claude-http").jsonObject
        assertEquals("http", claude.getValue("type").jsonPrimitive.content)
        assertTrue("oauth" in claude)
    }
}
