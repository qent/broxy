package io.qent.broxy.core.config

import io.qent.broxy.core.models.AuthConfig
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.utils.ConfigurationException
import io.qent.broxy.core.utils.Logger
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfigMapperTest {
    @Test
    fun map_file_to_domain_resolves_env_and_applies_defaults() {
        val env = mapOf("TOKEN" to "resolved-token", "CLIENT_SECRET" to "resolved-secret")
        val logger = RecordingLogger()
        val mapper =
            ConfigMapper(
                envResolver = EnvironmentVariableResolver({ env }, logger),
                logger = logger,
                errors = ConfigErrorHandler(logger),
                defaults = ConfigDefaults(),
            )
        val appConfig =
            FileAppConfig(
                inboundHttpPort = 70_000,
                connectionRetryCount = 0,
            )
        val root =
            FileMcpRoot(
                mcpServers =
                    mapOf(
                        "alpha" to
                            FileMcpServer(
                                type = "http",
                                url = "http://localhost:8080",
                                env = mapOf("TOKEN" to "\${TOKEN}"),
                                oauth =
                                    AuthConfig.OAuth(
                                        clientId = "client",
                                        clientSecret = "\${CLIENT_SECRET}",
                                        redirectUri = "http://localhost:8080/callback",
                                    ),
                            ),
                    ),
            )

        val mapped =
            mapper.mapFileToDomain(
                appConfig = appConfig,
                mcpRoot = root,
                mcpFileDirectory = Paths.get("/tmp"),
                defaultMcpFilePath = "/tmp/default-mcp.json",
            )
        val config = mapped.config

        assertEquals(65_535, config.inboundHttpPort)
        assertEquals(1, config.connectionRetryCount)
        assertEquals(false, config.ignoreHttpsCertificateErrors)
        assertEquals(false, config.fallbackPromptsAndResourcesToTools)
        assertEquals(false, config.adapterMode)
        assertEquals("/tmp/default-mcp.json", config.mcpFilePath)
        assertEquals("alpha", config.servers.single().name)
        assertEquals("resolved-token", config.servers.single().env["TOKEN"])

        val auth = config.servers.single().auth as AuthConfig.OAuth
        assertEquals("resolved-secret", auth.clientSecret)
    }

    @Test
    fun map_domain_to_file_preserves_raw_placeholders() {
        val env = mapOf("TOKEN" to "resolved-token", "CLIENT_SECRET" to "resolved-secret")
        val logger = RecordingLogger()
        val mapper =
            ConfigMapper(
                envResolver = EnvironmentVariableResolver({ env }, logger),
                logger = logger,
                errors = ConfigErrorHandler(logger),
                defaults = ConfigDefaults(),
            )
        val root =
            FileMcpRoot(
                mcpServers =
                    mapOf(
                        "alpha" to
                            FileMcpServer(
                                type = "http",
                                url = "http://localhost:8080",
                                env = mapOf("TOKEN" to "\${TOKEN}"),
                                oauth =
                                    AuthConfig.OAuth(
                                        clientId = "client",
                                        clientSecret = "\${CLIENT_SECRET}",
                                        redirectUri = "http://localhost:8080/callback",
                                    ),
                            ),
                    ),
            )

        val mapped =
            mapper.mapFileToDomain(
                appConfig = FileAppConfig(),
                mcpRoot = root,
                mcpFileDirectory = Paths.get("/tmp"),
                defaultMcpFilePath = "/tmp/default-mcp.json",
            )
        val saved = mapper.mapDomainToMcpFile(mapped.config, mapped.snapshot)
        val savedServer = saved.mcpServers["alpha"]!!
        val savedAuth = savedServer.oauth as AuthConfig.OAuth
        val savedEnv = requireNotNull(savedServer.env)

        assertEquals("\${TOKEN}", savedEnv["TOKEN"])
        assertEquals("\${CLIENT_SECRET}", savedAuth.clientSecret)
    }

    @Test
    fun map_domain_to_file_preserves_auth_scopes() {
        val logger = RecordingLogger()
        val mapper =
            ConfigMapper(
                envResolver = EnvironmentVariableResolver({ emptyMap() }, logger),
                logger = logger,
                errors = ConfigErrorHandler(logger),
                defaults = ConfigDefaults(),
            )
        val root =
            FileMcpRoot(
                mcpServers =
                    mapOf(
                        "alpha" to
                            FileMcpServer(
                                type = "http",
                                url = "http://localhost:8080",
                                oauth =
                                    AuthConfig.OAuth(
                                        clientId = "client",
                                        redirectUri = "http://localhost:8080/callback",
                                        scopes = listOf("files:read", "files:write"),
                                    ),
                            ),
                    ),
            )

        val mapped =
            mapper.mapFileToDomain(
                appConfig = FileAppConfig(),
                mcpRoot = root,
                mcpFileDirectory = Paths.get("/tmp"),
                defaultMcpFilePath = "/tmp/default-mcp.json",
            )
        val saved = mapper.mapDomainToMcpFile(mapped.config, mapped.snapshot)
        val savedAuth = saved.mcpServers["alpha"]!!.oauth as AuthConfig.OAuth

        assertEquals(listOf("files:read", "files:write"), savedAuth.scopes)
    }

    @Test
    fun map_domain_to_file_preserves_raw_placeholders_for_env_and_auth_fields() {
        val logger = RecordingLogger()
        val mapper =
            ConfigMapper(
                envResolver = EnvironmentVariableResolver({ placeholderEnv() }, logger),
                logger = logger,
                errors = ConfigErrorHandler(logger),
                defaults = ConfigDefaults(),
            )
        val root = placeholderRoot()

        val mapped =
            mapper.mapFileToDomain(
                appConfig = FileAppConfig(),
                mcpRoot = root,
                mcpFileDirectory = Paths.get("/tmp"),
                defaultMcpFilePath = "/tmp/default-mcp.json",
            )
        val saved = mapper.mapDomainToMcpFile(mapped.config, mapped.snapshot)
        val savedServer = saved.mcpServers["alpha"]!!
        val savedAuth = savedServer.oauth as AuthConfig.OAuth
        val savedEnv = requireNotNull(savedServer.env)

        assertEquals("\${TOKEN}", savedEnv["TOKEN"])
        assertEquals("{URL}", savedEnv["URL"])
        assertEquals("{CLIENT_ID}", savedAuth.clientId)
        assertEquals("\${CLIENT_SECRET}", savedAuth.clientSecret)
        assertEquals("\${CLIENT_ID_METADATA}", savedAuth.clientIdMetadataUrl)
        assertEquals("{AUTH_SERVER}", savedAuth.authorizationServer)
        assertEquals("client_secret_post", savedAuth.tokenEndpointAuthMethod)
    }

    @Test
    fun map_domain_to_file_does_not_persist_envFile_values_into_inline_env() {
        val dir = Files.createTempDirectory("broxy-config-mapper")
        Files.writeString(dir.resolve(".env"), "FILE_ONLY=file-value")
        val logger = RecordingLogger()
        val mapper =
            ConfigMapper(
                envResolver = EnvironmentVariableResolver({ mapOf("INLINE" to "inline-value") }, logger),
                logger = logger,
                errors = ConfigErrorHandler(logger),
                defaults = ConfigDefaults(),
            )
        val root =
            FileMcpRoot(
                mcpServers =
                    mapOf(
                        "alpha" to
                            FileMcpServer(
                                command = "run",
                                envFile = ".env",
                                env = mapOf("INLINE" to "\${INLINE}"),
                            ),
                    ),
            )

        val mapped =
            mapper.mapFileToDomain(
                appConfig = FileAppConfig(),
                mcpRoot = root,
                mcpFileDirectory = dir,
                defaultMcpFilePath = "/tmp/default-mcp.json",
            )
        val saved = mapper.mapDomainToMcpFile(mapped.config, mapped.snapshot)
        val savedServer = saved.mcpServers.getValue("alpha")
        val savedEnv = requireNotNull(savedServer.env)

        assertEquals(".env", savedServer.envFile)
        assertEquals(mapOf("INLINE" to "\${INLINE}"), savedEnv)
    }

    @Test
    fun map_file_to_domain_resolves_transport_placeholders() {
        val logger = RecordingLogger()
        val mapper =
            ConfigMapper(
                envResolver = EnvironmentVariableResolver({ mapOf("API_KEY" to "secret-key") }, logger),
                logger = logger,
                errors = ConfigErrorHandler(logger),
                defaults = ConfigDefaults(),
            )
        val workspace = Paths.get("/tmp/workspace")
        val root =
            FileMcpRoot(
                mcpServers =
                    mapOf(
                        "local" to
                            FileMcpServer(
                                command = "\${workspaceFolder}/bin/server",
                                args = listOf("--token", "\${input:api-key}", "--mode", "\${MODE:-safe}"),
                            ),
                        "remote" to
                            FileMcpServer(
                                type = "http",
                                url = "https://api.example.com/\${workspaceFolderBasename}",
                                headers = mapOf("Authorization" to "Bearer \${input:api-key}"),
                            ),
                    ),
            )

        val mapped =
            mapper.mapFileToDomain(
                appConfig = FileAppConfig(),
                mcpRoot = root,
                mcpFileDirectory = workspace,
                defaultMcpFilePath = "/tmp/default-mcp.json",
            )
        val local = mapped.config.servers.first { it.id == "local" }
        val localTransport = local.transport as TransportConfig.StdioTransport
        val remote = mapped.config.servers.first { it.id == "remote" }
        val remoteTransport = remote.transport as TransportConfig.StreamableHttpTransport

        assertEquals("/tmp/workspace/bin/server", localTransport.command)
        assertEquals(listOf("--token", "secret-key", "--mode", "safe"), localTransport.args)
        assertEquals("https://api.example.com/workspace", remoteTransport.url)
        assertEquals("Bearer secret-key", remoteTransport.headers["Authorization"])
    }

    @Test
    fun map_domain_to_file_preserves_raw_transport_placeholders_when_unchanged() {
        val logger = RecordingLogger()
        val mapper =
            ConfigMapper(
                envResolver = EnvironmentVariableResolver({ mapOf("API_KEY" to "secret-key") }, logger),
                logger = logger,
                errors = ConfigErrorHandler(logger),
                defaults = ConfigDefaults(),
            )
        val workspace = Paths.get("/tmp/workspace")
        val root =
            FileMcpRoot(
                mcpServers =
                    mapOf(
                        "local" to
                            FileMcpServer(
                                command = "\${workspaceFolder}/bin/server",
                                args = listOf("--token", "\${input:api-key}"),
                            ),
                        "remote" to
                            FileMcpServer(
                                type = "http",
                                url = "https://api.example.com/\${workspaceFolderBasename}",
                                headers =
                                    mapOf(
                                        "Authorization" to "Bearer \${input:api-key}",
                                        "X-Workspace" to "\${workspaceFolderBasename}",
                                    ),
                            ),
                    ),
            )

        val mapped =
            mapper.mapFileToDomain(
                appConfig = FileAppConfig(),
                mcpRoot = root,
                mcpFileDirectory = workspace,
                defaultMcpFilePath = "/tmp/default-mcp.json",
            )
        val saved = mapper.mapDomainToMcpFile(mapped.config, mapped.snapshot)

        val localSaved = saved.mcpServers.getValue("local")
        assertEquals("\${workspaceFolder}/bin/server", localSaved.command)
        assertEquals(listOf("--token", "\${input:api-key}"), localSaved.args)

        val remoteSaved = saved.mcpServers.getValue("remote")
        assertEquals("https://api.example.com/\${workspaceFolderBasename}", remoteSaved.url)
        val headers = requireNotNull(remoteSaved.headers)
        assertEquals("Bearer \${input:api-key}", headers["Authorization"])
        assertEquals("\${workspaceFolderBasename}", headers["X-Workspace"])
    }

    @Test
    fun map_domain_to_file_updates_changed_transport_fields_without_restoring_raw_values() {
        val logger = RecordingLogger()
        val mapper =
            ConfigMapper(
                envResolver = EnvironmentVariableResolver({ mapOf("API_KEY" to "secret-key") }, logger),
                logger = logger,
                errors = ConfigErrorHandler(logger),
                defaults = ConfigDefaults(),
            )
        val workspace = Paths.get("/tmp/workspace")
        val root =
            FileMcpRoot(
                mcpServers =
                    mapOf(
                        "remote" to
                            FileMcpServer(
                                type = "http",
                                url = "https://api.example.com/\${workspaceFolderBasename}",
                                headers =
                                    mapOf(
                                        "Authorization" to "Bearer \${input:api-key}",
                                        "X-Workspace" to "\${workspaceFolderBasename}",
                                    ),
                            ),
                    ),
            )

        val mapped =
            mapper.mapFileToDomain(
                appConfig = FileAppConfig(),
                mcpRoot = root,
                mcpFileDirectory = workspace,
                defaultMcpFilePath = "/tmp/default-mcp.json",
            )
        val remote = mapped.config.servers.single()
        val transport = remote.transport as TransportConfig.StreamableHttpTransport
        val updated =
            mapped.config.copy(
                servers =
                    listOf(
                        remote.copy(
                            transport =
                                transport.copy(
                                    url = "https://api.changed.com/mcp",
                                    headers =
                                        transport.headers.toMutableMap().apply {
                                            this["Authorization"] = "Bearer manual-token"
                                        },
                                ),
                        ),
                    ),
            )
        val saved = mapper.mapDomainToMcpFile(updated, mapped.snapshot)
        val remoteSaved = saved.mcpServers.getValue("remote")

        assertEquals("https://api.changed.com/mcp", remoteSaved.url)
        val headers = requireNotNull(remoteSaved.headers)
        assertEquals("Bearer manual-token", headers["Authorization"])
        assertEquals("\${workspaceFolderBasename}", headers["X-Workspace"])
    }

    @Test
    fun snapshot_from_save_handles_missing_raw_values() {
        val logger = RecordingLogger()
        val mapper =
            ConfigMapper(
                envResolver = EnvironmentVariableResolver({ emptyMap() }, logger),
                logger = logger,
                errors = ConfigErrorHandler(logger),
                defaults = ConfigDefaults(),
            )
        val config =
            McpServersConfig(
                servers =
                    listOf(
                        McpServerConfig(
                            id = "alpha",
                            name = "alpha",
                            transport = TransportConfig.StreamableHttpTransport(url = "http://localhost:8080"),
                            env = mapOf("TOKEN" to "value"),
                            enabled = true,
                            auth =
                                AuthConfig.OAuth(
                                    clientId = "client",
                                    redirectUri = "http://localhost:8080/callback",
                                ),
                        ),
                    ),
            )
        val root =
            FileMcpRoot(
                mcpServers =
                    mapOf(
                        "alpha" to
                            FileMcpServer(
                                type = "http",
                                url = "http://localhost:8080",
                            ),
                    ),
            )

        val snapshot = mapper.snapshotFromSave(config, root)
        val saved = mapper.mapDomainToMcpFile(config, snapshot)
        val savedServer = saved.mcpServers["alpha"]!!
        val savedAuth = savedServer.oauth as AuthConfig.OAuth
        val savedEnv = requireNotNull(savedServer.env)

        assertEquals("value", savedEnv["TOKEN"])
        assertEquals("client", savedAuth.clientId)
    }

    @Test
    fun map_file_to_domain_supports_all_transports() {
        val logger = RecordingLogger()
        val mapper =
            ConfigMapper(
                envResolver = EnvironmentVariableResolver({ emptyMap() }, logger),
                logger = logger,
                errors = ConfigErrorHandler(logger),
                defaults = ConfigDefaults(),
            )
        val root =
            FileMcpRoot(
                mcpServers =
                    mapOf(
                        "stdio" to FileMcpServer(type = "stdio", command = "cmd"),
                        "http" to FileMcpServer(type = "http", url = "http://localhost:8080"),
                        "sse" to FileMcpServer(type = "sse", url = "http://localhost:8081"),
                        "ws" to FileMcpServer(type = "ws", url = "ws://localhost:8082"),
                    ),
            )

        val config =
            mapper
                .mapFileToDomain(
                    appConfig = FileAppConfig(),
                    mcpRoot = root,
                    mcpFileDirectory = Paths.get("/tmp"),
                    defaultMcpFilePath = "/tmp/default-mcp.json",
                ).config
        val transports = config.servers.associateBy({ it.id }, { it.transport })

        assertTrue(transports["stdio"] is TransportConfig.StdioTransport)
        assertTrue(transports["http"] is TransportConfig.StreamableHttpTransport)
        assertTrue(transports["sse"] is TransportConfig.HttpTransport)
        assertTrue(transports["ws"] is TransportConfig.WebSocketTransport)
    }

    @Test
    fun snapshot_from_save_requires_matching_raw_config() {
        val logger = RecordingLogger()
        val mapper =
            ConfigMapper(
                envResolver = EnvironmentVariableResolver({ emptyMap() }, logger),
                logger = logger,
                errors = ConfigErrorHandler(logger),
                defaults = ConfigDefaults(),
            )
        val config =
            McpServersConfig(
                servers =
                    listOf(
                        McpServerConfig(
                            id = "alpha",
                            name = "alpha",
                            transport = TransportConfig.StdioTransport(command = "cmd"),
                            env = emptyMap(),
                            enabled = true,
                            auth = null,
                        ),
                    ),
            )
        val root = FileMcpRoot(mcpServers = emptyMap())

        assertFailsWith<ConfigurationException> {
            mapper.snapshotFromSave(config, root)
        }
    }

    @Test
    fun map_file_to_domain_rejects_unknown_transport() {
        val logger = RecordingLogger()
        val mapper =
            ConfigMapper(
                envResolver = EnvironmentVariableResolver({ emptyMap() }, logger),
                logger = logger,
                errors = ConfigErrorHandler(logger),
                defaults = ConfigDefaults(),
            )
        val root =
            FileMcpRoot(
                mcpServers =
                    mapOf(
                        "alpha" to FileMcpServer(type = "ftp"),
                    ),
            )

        assertFailsWith<ConfigurationException> {
            mapper.mapFileToDomain(
                appConfig = FileAppConfig(),
                mcpRoot = root,
                mcpFileDirectory = Paths.get("/tmp"),
                defaultMcpFilePath = "/tmp/default-mcp.json",
            )
        }
    }

    private class RecordingLogger : Logger {
        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(
            message: String,
            throwable: Throwable?,
        ) = Unit

        override fun error(
            message: String,
            throwable: Throwable?,
        ) = Unit
    }

    private fun placeholderEnv(): Map<String, String> =
        mapOf(
            "TOKEN" to "resolved-token",
            "URL" to "resolved-url",
            "CLIENT_ID" to "resolved-client",
            "CLIENT_SECRET" to "resolved-secret",
            "CLIENT_ID_METADATA" to "resolved-metadata",
            "AUTH_SERVER" to "resolved-auth",
        )

    private fun placeholderRoot(): FileMcpRoot =
        FileMcpRoot(
            mcpServers =
                mapOf(
                    "alpha" to
                        FileMcpServer(
                            type = "http",
                            url = "http://localhost:8080",
                            env = mapOf("TOKEN" to "\${TOKEN}", "URL" to "{URL}"),
                            oauth =
                                AuthConfig.OAuth(
                                    clientId = "{CLIENT_ID}",
                                    clientSecret = "\${CLIENT_SECRET}",
                                    clientIdMetadataUrl = "\${CLIENT_ID_METADATA}",
                                    redirectUri = "http://localhost:8080/callback",
                                    authorizationServer = "{AUTH_SERVER}",
                                    tokenEndpointAuthMethod = "client_secret_post",
                                ),
                        ),
                ),
        )
}
