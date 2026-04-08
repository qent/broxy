package io.qent.broxy.core.config

import io.qent.broxy.core.mcp.auth.InMemorySecureStorage
import io.qent.broxy.core.mcp.auth.OAuthStateSnapshot
import io.qent.broxy.core.mcp.auth.OAuthToken
import io.qent.broxy.core.mcp.auth.oauthStateStoreForTesting
import io.qent.broxy.core.models.AuthConfig
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.utils.ConfigurationException
import kotlinx.serialization.json.Json
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonConfigurationRepositoryTest {
    @Test
    fun resolveMcpFilePath_expands_tilde_to_home() {
        val baseDir = Files.createTempDirectory("broxy-config")
        val resolved = JsonConfigurationRepository.resolveMcpFilePath(baseDir, "~/my-mcp.json")
        val expected = Paths.get(System.getProperty("user.home"), "my-mcp.json").normalize()

        assertEquals(expected, resolved)
    }

    @Test
    fun loadMcpConfig_applies_defaults_and_coerces_port() {
        val dir = Files.createTempDirectory("broxy-config")
        Files.writeString(
            dir.resolve("config.json"),
            """
            {
              "requestTimeoutSeconds": 10,
              "capabilitiesTimeoutSeconds": 5,
              "inboundHttpPort": 70000
            }
            """.trimIndent(),
        )
        Files.writeString(
            dir.resolve("mcp.json"),
            """
            {
              "mcpServers": {
                "alpha": {
                  "name": "Alpha",
                  "type": "http",
                  "url": "http://localhost:9999",
                  "headers": {"X-Test": "1"}
                }
              }
            }
            """.trimIndent(),
        )

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
        assertEquals(65535, config.inboundHttpPort)
        assertEquals(10, config.requestTimeoutSeconds)
        assertEquals(5, config.capabilitiesTimeoutSeconds)
        assertEquals(120, config.authorizationTimeoutSeconds)
        assertEquals(3, config.connectionRetryCount)
        assertEquals(false, config.ignoreHttpsCertificateErrors)
        val defaultMcpPath =
            dir
                .resolve("mcp.json")
                .toAbsolutePath()
                .normalize()
                .toString()
        assertEquals(defaultMcpPath, config.mcpFilePath)
        assertEquals(1, config.servers.size)
        val server = config.servers.first { it.id == "alpha" }
        assertEquals("alpha", server.id)
        assertEquals("Alpha", server.name)
        val transport = server.transport as TransportConfig.StreamableHttpTransport
        assertEquals("http://localhost:9999", transport.url)
        assertEquals(mapOf("X-Test" to "1"), transport.headers)
    }

    @Test
    fun loadMcpConfig_reads_servers_from_configured_mcp_file_path() {
        val dir = Files.createTempDirectory("broxy-config")
        val externalDir = Files.createTempDirectory("broxy-external-mcp")
        val externalMcp = externalDir.resolve("shared-mcp.json")
        Files.writeString(
            dir.resolve("config.json"),
            """
            {
              "mcpFilePath": "${externalMcp.toAbsolutePath().toString().replace("\\", "\\\\")}",
              "requestTimeoutSeconds": 42
            }
            """.trimIndent(),
        )
        Files.writeString(
            externalMcp,
            """
            {
              "mcpServers": {
                "beta": {
                  "type": "stdio",
                  "command": "run"
                }
              }
            }
            """.trimIndent(),
        )

        val repo = JsonConfigurationRepository(baseDir = dir, logger = ConfigTestLogger)
        val config = repo.loadMcpConfig()

        assertEquals(externalMcp.toAbsolutePath().normalize().toString(), config.mcpFilePath)
        assertEquals(42, config.requestTimeoutSeconds)
        assertEquals("beta", config.servers.single().id)
        assertTrue(config.servers.single().transport is TransportConfig.StdioTransport)
    }

    @Test
    fun loadMcpConfig_resolves_env_for_sse() {
        val dir = Files.createTempDirectory("broxy-config")
        val json =
            """
            {
              "mcpServers": {
                "beta": {
                  "type": "sse",
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
        val server = config.servers.first { it.id == "beta" }
        assertEquals("beta", server.id)
        assertEquals(mapOf("TOKEN" to "secret"), server.env)
        assertTrue(server.transport is TransportConfig.HttpTransport)
    }

    @Test
    fun loadMcpConfig_rejects_legacy_transport_aliases() {
        val dir = Files.createTempDirectory("broxy-config")
        Files.writeString(
            dir.resolve("mcp.json"),
            """
            {
              "mcpServers": {
                "gamma": {
                  "type": "streamable-http",
                  "url": "http://localhost:7000/mcp"
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
    fun loadMcpConfig_rejects_websocket_alias() {
        val dir = Files.createTempDirectory("broxy-config")
        Files.writeString(
            dir.resolve("mcp.json"),
            """
            {
              "mcpServers": {
                "gamma": {
                  "type": "websocket",
                  "url": "ws://localhost:7000/mcp"
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
    fun loadMcpConfig_throws_for_missing_env_vars() {
        val dir = Files.createTempDirectory("broxy-config")
        Files.writeString(
            dir.resolve("mcp.json"),
            """
            {
              "mcpServers": {
                "alpha": {
                  "type": "stdio",
                  "command": "run",
                  "env": {"TOKEN": "${'$'}{TOKEN}"}
                }
              }
            }
            """.trimIndent(),
        )

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
        Files.writeString(
            dir.resolve("mcp.json"),
            """
            {
              "mcpServers": {
                "alpha": {
                  "name": " ",
                  "type": "stdio",
                  "command": "run"
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
    fun loadMcpConfig_returns_empty_when_missing_file() {
        val dir = Files.createTempDirectory("broxy-config")
        val repo = JsonConfigurationRepository(baseDir = dir, logger = ConfigTestLogger)

        val config = repo.loadMcpConfig()

        assertEquals(emptyList(), config.servers)
        val defaultMcpPath =
            dir
                .resolve("mcp.json")
                .toAbsolutePath()
                .normalize()
                .toString()
        assertEquals(defaultMcpPath, config.mcpFilePath)
        assertEquals(3335, config.inboundHttpPort)
    }

    @Test
    fun loadMcpConfig_parses_oauth_auth_and_resolves_env() {
        val dir = Files.createTempDirectory("broxy-config")
        Files.writeString(
            dir.resolve("mcp.json"),
            """
            {
              "mcpServers": {
                "alpha": {
                  "type": "sse",
                  "url": "http://localhost:9999/mcp",
                  "oauth": {
                    "type": "oauth",
                    "clientId": "client",
                    "clientSecret": "${'$'}{TOKEN}",
                    "redirectUri": "http://localhost:8080/callback",
                    "tokenEndpointAuthMethod": "client_secret_post",
                    "authorizationServer": "https://auth.example.com"
                  }
                },
                "beta": {
                  "type": "http",
                  "url": "https://example.com/mcp",
                  "auth": {
                    "type": "oauth",
                    "clientId": "legacy-client",
                    "redirectUri": "http://localhost:8081/callback"
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
                        envProvider = { mapOf("TOKEN" to "secret") },
                        logger = ConfigTestLogger,
                    ),
            )

        val config = repo.loadMcpConfig()
        val server = config.servers.first { it.id == "alpha" }
        val auth = server.auth as AuthConfig.OAuth
        assertEquals("client", auth.clientId)
        assertEquals("secret", auth.clientSecret)
        assertEquals("client_secret_post", auth.tokenEndpointAuthMethod)
        assertEquals("https://auth.example.com", auth.authorizationServer)
        val legacy = config.servers.first { it.id == "beta" }.auth as AuthConfig.OAuth
        assertEquals("legacy-client", legacy.clientId)
    }

    @Test
    fun saveMcpConfig_removes_oauth_state_for_deleted_servers() {
        val dir = Files.createTempDirectory("broxy-config")
        Files.writeString(
            dir.resolve("mcp.json"),
            """
            {
              "mcpServers": {
                "alpha": {
                  "type": "sse",
                  "url": "http://localhost:9999/mcp",
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

        val authStore =
            oauthStateStoreForTesting(
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

        repo.loadMcpConfig()
        repo.saveMcpConfig(McpServersConfig(servers = emptyList(), mcpFilePath = dir.resolve("mcp.json").toString()))

        assertNull(authStore.load("alpha", resourceUrl))
    }

    @Test
    fun saveMcpConfig_preserves_placeholders_for_unchanged_values() {
        val dir = Files.createTempDirectory("broxy-config")
        Files.writeString(
            dir.resolve("mcp.json"),
            """
            {
              "mcpServers": {
                "alpha": {
                  "type": "http",
                  "url": "http://localhost:9999/mcp",
                  "env": {"TOKEN": "${'$'}{TOKEN}"},
                  "oauth": {
                    "type": "oauth",
                    "clientId": "client",
                    "clientSecret": "${'$'}{CLIENT_SECRET}",
                    "redirectUri": "http://localhost:8080/callback"
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
    fun load_and_save_mcp_config_resolves_transport_placeholders_and_preserves_raw_values() {
        val dir = Files.createTempDirectory("broxy-config")
        Files.writeString(
            dir.resolve("mcp.json"),
            """
            {
              "mcpServers": {
                "local": {
                  "command": "${'$'}{workspaceFolder}/bin/server",
                  "args": ["--token", "${'$'}{input:api-key}", "--mode", "${'$'}{MODE:-safe}"]
                },
                "remote": {
                  "type": "http",
                  "url": "https://api.example.com/${'$'}{workspaceFolderBasename}",
                  "headers": {
                    "Authorization": "Bearer ${'$'}{input:api-key}",
                    "X-Separator": "${'$'}{pathSeparator}"
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
                        envProvider = { mapOf("API_KEY" to "secret-key") },
                        logger = ConfigTestLogger,
                    ),
            )

        val loaded = repo.loadMcpConfig()
        val localTransport = loaded.servers.first { it.id == "local" }.transport as TransportConfig.StdioTransport
        val remoteTransport =
            loaded.servers.first { it.id == "remote" }.transport as TransportConfig.StreamableHttpTransport

        assertEquals("${dir.toAbsolutePath().normalize()}/bin/server", localTransport.command)
        assertEquals(listOf("--token", "secret-key", "--mode", "safe"), localTransport.args)
        assertEquals("https://api.example.com/${dir.fileName}", remoteTransport.url)
        assertEquals("Bearer secret-key", remoteTransport.headers["Authorization"])
        assertEquals(FileSystems.getDefault().separator, remoteTransport.headers["X-Separator"])

        repo.saveMcpConfig(loaded)
        val saved = Files.readString(dir.resolve("mcp.json"))

        assertTrue(saved.contains("\"command\": \"${'$'}{workspaceFolder}/bin/server\""))
        assertTrue(saved.contains("\"${'$'}{input:api-key}\""))
        assertTrue(saved.contains("\"url\": \"https://api.example.com/${'$'}{workspaceFolderBasename}\""))
        assertTrue(saved.contains("\"X-Separator\": \"${'$'}{pathSeparator}\""))
        assertTrue(!saved.contains("secret-key"))
    }

    @Test
    fun loadMcpConfig_throws_for_missing_input_placeholder_env() {
        val dir = Files.createTempDirectory("broxy-config")
        Files.writeString(
            dir.resolve("mcp.json"),
            """
            {
              "mcpServers": {
                "alpha": {
                  "command": "run",
                  "args": ["${'$'}{input:api-key}"]
                }
              }
            }
            """.trimIndent(),
        )

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
    fun saveMcpConfig_updates_changed_values_but_keeps_unmodified_placeholders() {
        val dir = Files.createTempDirectory("broxy-config")
        Files.writeString(
            dir.resolve("mcp.json"),
            """
            {
              "mcpServers": {
                "alpha": {
                  "type": "http",
                  "url": "http://localhost:9999/mcp",
                  "env": {"TOKEN": "${'$'}{TOKEN}"},
                  "oauth": {
                    "type": "oauth",
                    "clientId": "client",
                    "clientSecret": "${'$'}{CLIENT_SECRET}",
                    "redirectUri": "http://localhost:8080/callback"
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
    fun saveMcpConfig_writes_config_and_external_mcp_file() {
        val dir = Files.createTempDirectory("broxy-config")
        val externalDir = Files.createTempDirectory("broxy-external-mcp")
        val externalMcp = externalDir.resolve("servers.json")
        val repo = JsonConfigurationRepository(baseDir = dir, logger = ConfigTestLogger)

        val server =
            McpServerConfig(
                id = "alpha",
                name = "Alpha",
                transport = TransportConfig.StreamableHttpTransport(url = "http://localhost:9999/mcp"),
            )
        repo.saveMcpConfig(
            McpServersConfig(
                servers = listOf(server),
                mcpFilePath = externalMcp.toString(),
                requestTimeoutSeconds = 99,
            ),
        )

        val configText = Files.readString(dir.resolve("config.json"))
        val mcpText = Files.readString(externalMcp)

        assertTrue(
            configText.contains(
                "\"mcpFilePath\": \"${externalMcp.toAbsolutePath().normalize()}\"",
            ),
        )
        assertTrue(configText.contains("\"requestTimeoutSeconds\": 99"))
        assertTrue(mcpText.contains("\"mcpServers\""))
        assertTrue(mcpText.contains("\"alpha\""))
        assertTrue(!mcpText.contains("requestTimeoutSeconds"))
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
            repo.saveMcpConfig(
                McpServersConfig(
                    servers = listOf(server),
                    mcpFilePath = dir.resolve("mcp.json").toString(),
                ),
            )
        }
    }
}
