package io.qent.broxy.core.config

import io.qent.broxy.core.models.AuthConfig
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.utils.ConfigurationException
import io.qent.broxy.core.utils.Logger
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
        val root =
            FileMcpRoot(
                inboundSsePort = 70_000,
                connectionRetryCount = 0,
                mcpServers =
                    mapOf(
                        "alpha" to
                            FileMcpServer(
                                transport = "http",
                                url = "http://localhost:8080",
                                env = mapOf("TOKEN" to "\${TOKEN}"),
                                auth =
                                    AuthConfig.OAuth(
                                        clientId = "client",
                                        clientSecret = "\${CLIENT_SECRET}",
                                        redirectUri = "http://localhost:8080/callback",
                                    ),
                            ),
                    ),
            )

        val mapped = mapper.mapFileToDomain(root)
        val config = mapped.config

        assertEquals(65_535, config.inboundSsePort)
        assertEquals(1, config.connectionRetryCount)
        assertEquals(false, config.fallbackPromptsAndResourcesToTools)
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
                                transport = "http",
                                url = "http://localhost:8080",
                                env = mapOf("TOKEN" to "\${TOKEN}"),
                                auth =
                                    AuthConfig.OAuth(
                                        clientId = "client",
                                        clientSecret = "\${CLIENT_SECRET}",
                                        redirectUri = "http://localhost:8080/callback",
                                    ),
                            ),
                    ),
            )

        val mapped = mapper.mapFileToDomain(root)
        val saved = mapper.mapDomainToFile(mapped.config, mapped.snapshot)
        val savedServer = saved.mcpServers["alpha"]!!
        val savedAuth = savedServer.auth as AuthConfig.OAuth
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
                                transport = "http",
                                url = "http://localhost:8080",
                                auth =
                                    AuthConfig.OAuth(
                                        clientId = "client",
                                        redirectUri = "http://localhost:8080/callback",
                                        scopes = listOf("files:read", "files:write"),
                                    ),
                            ),
                    ),
            )

        val mapped = mapper.mapFileToDomain(root)
        val saved = mapper.mapDomainToFile(mapped.config, mapped.snapshot)
        val savedAuth = saved.mcpServers["alpha"]!!.auth as AuthConfig.OAuth

        assertEquals(listOf("files:read", "files:write"), savedAuth.scopes)
    }

    @Test
    fun map_domain_to_file_preserves_raw_placeholders_for_env_and_auth_fields() {
        val env =
            mapOf(
                "TOKEN" to "resolved-token",
                "URL" to "resolved-url",
                "CLIENT_ID" to "resolved-client",
                "CLIENT_SECRET" to "resolved-secret",
                "CLIENT_ID_METADATA" to "resolved-metadata",
                "AUTH_SERVER" to "resolved-auth",
            )
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
                                transport = "http",
                                url = "http://localhost:8080",
                                env =
                                    mapOf(
                                        "TOKEN" to "\${TOKEN}",
                                        "URL" to "{URL}",
                                    ),
                                auth =
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

        val mapped = mapper.mapFileToDomain(root)
        val saved = mapper.mapDomainToFile(mapped.config, mapped.snapshot)
        val savedServer = saved.mcpServers["alpha"]!!
        val savedAuth = savedServer.auth as AuthConfig.OAuth
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
                        io.qent.broxy.core.models.McpServerConfig(
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
                                transport = "http",
                                url = "http://localhost:8080",
                            ),
                    ),
            )

        val snapshot = mapper.snapshotFromSave(config, root)
        val saved = mapper.mapDomainToFile(config, snapshot)
        val savedServer = saved.mcpServers["alpha"]!!
        val savedAuth = savedServer.auth as AuthConfig.OAuth
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
                        "stdio" to FileMcpServer(transport = "stdio", command = "cmd"),
                        "http" to FileMcpServer(transport = "http", url = "http://localhost:8080"),
                        "sse" to FileMcpServer(transport = "sse", url = "http://localhost:8081"),
                        "ws" to FileMcpServer(transport = "ws", url = "ws://localhost:8082"),
                    ),
            )

        val config = mapper.mapFileToDomain(root).config
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
                        io.qent.broxy.core.models.McpServerConfig(
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
                        "alpha" to FileMcpServer(transport = "ftp"),
                    ),
            )

        assertFailsWith<ConfigurationException> {
            mapper.mapFileToDomain(root)
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
}
