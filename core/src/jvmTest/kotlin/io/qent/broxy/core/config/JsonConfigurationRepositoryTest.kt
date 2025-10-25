package io.qent.broxy.core.config

import io.qent.broxy.core.mcp.auth.InMemorySecureStorage
import io.qent.broxy.core.mcp.auth.OAuthStateSnapshot
import io.qent.broxy.core.mcp.auth.OAuthStateStore
import io.qent.broxy.core.mcp.auth.OAuthToken
import io.qent.broxy.core.models.AuthConfig
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.utils.ConfigurationException
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonConfigurationRepositoryTest {
    @Test
    fun loadMcpConfig_applies_defaults_and_coerces_port() {
        val dir = Files.createTempDirectory("broxy-config")
        val json =
            """
            {
              "requestTimeoutSeconds": 10,
              "capabilitiesTimeoutSeconds": 5,
              "inboundSsePort": 70000,
              "mcpServers": {
                "alpha": {
                  "name": "Alpha",
                  "transport": "http",
                  "url": "http://localhost:9999",
                  "headers": {"X-Test": "1"}
                }
              }
            }
            """.trimIndent()
        Files.writeString(dir.resolve("mcp.json"), json)

        val repo =
            JsonConfigurationRepository(
                baseDir = dir,
                json =
                    Json {
                        ignoreUnknownKeys = true
                        prettyPrint = true
                    },
                logger = ConfigTestLogger,
            )

        val config = repo.loadMcpConfig()
        assertEquals(65535, config.inboundSsePort)
        assertEquals(10, config.requestTimeoutSeconds)
        assertEquals(5, config.capabilitiesTimeoutSeconds)
        assertEquals(120, config.authorizationTimeoutSeconds)
        assertEquals(3, config.connectionRetryCount)
        assertEquals(1, config.servers.size)
        val server = config.servers.single()
        assertEquals("alpha", server.id)
        assertEquals("Alpha", server.name)
        val transport = server.transport as TransportConfig.StreamableHttpTransport
        assertEquals("http://localhost:9999", transport.url)
        assertEquals(mapOf("X-Test" to "1"), transport.headers)
    }

    @Test
    fun loadMcpConfig_resolves_env_for_sse() {
        val dir = Files.createTempDirectory("broxy-config")
        val json =
            """
            {
              "mcpServers": {
                "beta": {
                  "transport": "sse",
                  "url": "http://localhost:7000/mcp",
                  "env": {"TOKEN": "${'$'}{TOKEN}"}
                }
              }
            }
            """.trimIndent()
        Files.writeString(dir.resolve("mcp.json"), json)

        val repo =
            JsonConfigurationRepository(
                baseDir = dir,
                logger = ConfigTestLogger,
                envResolver =
                    EnvironmentVariableResolver(
                        envProvider = { mapOf("TOKEN" to "secret") },
                        logger = ConfigTestLogger,
                    ),
            )

        val config = repo.loadMcpConfig()
        val server = config.servers.single()
        assertEquals("beta", server.id)
        assertEquals(mapOf("TOKEN" to "secret"), server.env)
        assertTrue(server.transport is TransportConfig.HttpTransport)
    }

    @Test
    fun loadMcpConfig_rejects_legacy_transport_aliases() {
        val dir = Files.createTempDirectory("broxy-config")
        val json =
            """
            {
              "mcpServers": {
                "gamma": {
                  "transport": "streamable-http",
                  "url": "http://localhost:7000/mcp"
                }
              }
            }
            """.trimIndent()
        Files.writeString(dir.resolve("mcp.json"), json)

        val repo = JsonConfigurationRepository(baseDir = dir, logger = ConfigTestLogger)

        assertFailsWith<ConfigurationException> {
            repo.loadMcpConfig()
        }
    }

    @Test
    fun loadMcpConfig_rejects_websocket_alias() {
        val dir = Files.createTempDirectory("broxy-config")
        val json =
            """
            {
              "mcpServers": {
                "gamma": {
                  "transport": "websocket",
                  "url": "ws://localhost:7000/mcp"
                }
              }
            }
            """.trimIndent()
        Files.writeString(dir.resolve("mcp.json"), json)

        val repo = JsonConfigurationRepository(baseDir = dir, logger = ConfigTestLogger)

        assertFailsWith<ConfigurationException> {
            repo.loadMcpConfig()
        }
    }

    @Test
    fun loadMcpConfig_throws_for_missing_env_vars() {
        val dir = Files.createTempDirectory("broxy-config")
        val json =
            """
            {
              "mcpServers": {
                "alpha": {
                  "transport": "stdio",
                  "command": "run",
                  "env": {"TOKEN": "${'$'}{TOKEN}"}
                }
              }
            }
            """.trimIndent()
        Files.writeString(dir.resolve("mcp.json"), json)

        val repo =
            JsonConfigurationRepository(
                baseDir = dir,
                logger = ConfigTestLogger,
                envResolver = EnvironmentVariableResolver(envProvider = { emptyMap() }, logger = ConfigTestLogger),
            )

        assertFailsWith<ConfigurationException> {
            repo.loadMcpConfig()
        }
    }

    @Test
    fun loadMcpConfig_throws_for_blank_name() {
        val dir = Files.createTempDirectory("broxy-config")
        val json =
            """
            {
              "mcpServers": {
                "alpha": {
                  "name": " ",
                  "transport": "stdio",
                  "command": "run"
                }
              }
            }
            """.trimIndent()
        Files.writeString(dir.resolve("mcp.json"), json)

        val repo = JsonConfigurationRepository(baseDir = dir, logger = ConfigTestLogger)

        assertFailsWith<ConfigurationException> {
            repo.loadMcpConfig()
        }
    }

    @Test
    fun loadMcpConfig_returns_empty_when_missing_file() {
        val dir = Files.createTempDirectory("broxy-config")
        val repo = JsonConfigurationRepository(baseDir = dir, logger = ConfigTestLogger)

        val config = repo.loadMcpConfig()

        assertEquals(McpServersConfig(emptyList()), config.copy(defaultPresetId = null))
    }

    @Test
    fun loadMcpConfig_parses_oauth_auth_and_resolves_env() {
        val dir = Files.createTempDirectory("broxy-config")
        val json =
            """
            {
              "mcpServers": {
                "alpha": {
                  "transport": "sse",
                  "url": "http://localhost:9999/mcp",
                  "auth": {
                    "type": "oauth",
                    "clientId": "client",
                    "clientSecret": "${'$'}{TOKEN}",
                    "redirectUri": "http://localhost:8080/callback",
                    "tokenEndpointAuthMethod": "client_secret_post",
                    "authorizationServer": "https://auth.example.com"
                  }
                }
              }
            }
            """.trimIndent()
        Files.writeString(dir.resolve("mcp.json"), json)

        val repo =
            JsonConfigurationRepository(
                baseDir = dir,
                logger = ConfigTestLogger,
                envResolver =
                    EnvironmentVariableResolver(
                        envProvider = { mapOf("TOKEN" to "secret") },
                        logger = ConfigTestLogger,
                    ),
            )

        val config = repo.loadMcpConfig()
        val server = config.servers.single()
        val auth = server.auth as io.qent.broxy.core.models.AuthConfig.OAuth
        assertEquals("client", auth.clientId)
        assertEquals("secret", auth.clientSecret)
        assertEquals("client_secret_post", auth.tokenEndpointAuthMethod)
        assertEquals("https://auth.example.com", auth.authorizationServer)
    }

    @Test
    fun saveMcpConfig_removes_oauth_state_for_deleted_servers() {
        val dir = Files.createTempDirectory("broxy-config")
        val json =
            """
            {
              "mcpServers": {
                "alpha": {
                  "transport": "sse",
                  "url": "http://localhost:9999/mcp",
                  "auth": {
                    "type": "oauth",
                    "clientId": "client",
                    "redirectUri": "http://localhost:8080/callback"
                  }
                }
              }
            }
            """.trimIndent()
        Files.writeString(dir.resolve("mcp.json"), json)

        val authStore =
            OAuthStateStore.forTesting(
                baseDir = dir,
                logger = ConfigTestLogger,
                secureStorage = InMemorySecureStorage(),
            )
        val resourceUrl = "http://localhost:9999/mcp"
        authStore.save(
            "alpha",
            OAuthStateSnapshot(
                resourceUrl = resourceUrl,
                token = OAuthToken(accessToken = "token"),
            ),
        )
        assertNotNull(authStore.load("alpha", resourceUrl))

        val repo =
            JsonConfigurationRepository(
                baseDir = dir,
                logger = ConfigTestLogger,
                authStateStore = authStore,
            )
        repo.saveMcpConfig(McpServersConfig(servers = emptyList()))

        assertNull(authStore.load("alpha", resourceUrl))
    }

    @Test
    fun saveMcpConfig_preserves_placeholders_for_unchanged_values() {
        val dir = Files.createTempDirectory("broxy-config")
        val json =
            """
            {
              "mcpServers": {
                "alpha": {
                  "transport": "http",
                  "url": "http://localhost:9999/mcp",
                  "env": {"TOKEN": "${'$'}{TOKEN}"},
                  "auth": {
                    "type": "oauth",
                    "clientId": "client",
                    "clientSecret": "${'$'}{CLIENT_SECRET}",
                    "redirectUri": "http://localhost:8080/callback"
                  }
                }
              }
            }
            """.trimIndent()
        Files.writeString(dir.resolve("mcp.json"), json)

        val repo =
            JsonConfigurationRepository(
                baseDir = dir,
                logger = ConfigTestLogger,
                envResolver =
                    EnvironmentVariableResolver(
                        envProvider = { mapOf("TOKEN" to "resolved-token", "CLIENT_SECRET" to "resolved-secret") },
                        logger = ConfigTestLogger,
                    ),
            )

        val config = repo.loadMcpConfig()
        repo.saveMcpConfig(config)

        val saved = Files.readString(dir.resolve("mcp.json"))
        assertTrue(saved.contains("\"TOKEN\": \"${'$'}{TOKEN}\""))
        assertTrue(saved.contains("\"clientSecret\": \"${'$'}{CLIENT_SECRET}\""))
        assertTrue(saved.contains("http://localhost:8080/callback"))
        assertTrue(!saved.contains("resolved-token"))
        assertTrue(!saved.contains("resolved-secret"))
    }

    @Test
    fun saveMcpConfig_updates_changed_values_but_keeps_unmodified_placeholders() {
        val dir = Files.createTempDirectory("broxy-config")
        val json =
            """
            {
              "mcpServers": {
                "alpha": {
                  "transport": "http",
                  "url": "http://localhost:9999/mcp",
                  "env": {"TOKEN": "${'$'}{TOKEN}"},
                  "auth": {
                    "type": "oauth",
                    "clientId": "client",
                    "clientSecret": "${'$'}{CLIENT_SECRET}",
                    "redirectUri": "http://localhost:8080/callback"
                  }
                }
              }
            }
            """.trimIndent()
        Files.writeString(dir.resolve("mcp.json"), json)

        val repo =
            JsonConfigurationRepository(
                baseDir = dir,
                logger = ConfigTestLogger,
                envResolver =
                    EnvironmentVariableResolver(
                        envProvider = { mapOf("TOKEN" to "resolved-token", "CLIENT_SECRET" to "resolved-secret") },
                        logger = ConfigTestLogger,
                    ),
            )

        val config = repo.loadMcpConfig()
        val updatedServer =
            config.servers.single().copy(
                env = mapOf("TOKEN" to "new-token"),
                auth =
                    (config.servers.single().auth as AuthConfig.OAuth).copy(
                        redirectUri = "http://localhost:9090/callback",
                    ),
            )
        val updated = config.copy(servers = listOf(updatedServer))

        repo.saveMcpConfig(updated)

        val saved = Files.readString(dir.resolve("mcp.json"))
        assertTrue(saved.contains("\"TOKEN\": \"new-token\""))
        assertTrue(saved.contains("\"clientSecret\": \"${'$'}{CLIENT_SECRET}\""))
        assertTrue(saved.contains("http://localhost:9090/callback"))
        assertTrue(!saved.contains("resolved-secret"))
    }

    @Test
    fun saveMcpConfig_rejects_invalid_oauth_redirect() {
        val dir = Files.createTempDirectory("broxy-config")
        val repo = JsonConfigurationRepository(baseDir = dir, logger = ConfigTestLogger)
        val server =
            McpServerConfig(
                id = "alpha",
                name = "Alpha",
                transport = TransportConfig.StreamableHttpTransport(url = "http://localhost:9999/mcp"),
                auth =
                    AuthConfig.OAuth(
                        clientId = "client",
                        redirectUri = "https://example.com/callback",
                    ),
            )

        assertFailsWith<ConfigurationException> {
            repo.saveMcpConfig(McpServersConfig(servers = listOf(server)))
        }
    }
}
