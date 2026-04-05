package io.qent.broxy.core.config

import io.qent.broxy.core.models.AuthConfig
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.utils.ConfigurationException
import io.qent.broxy.core.utils.Logger
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ConfigValidatorTest {
    @Test
    fun validate_rejects_duplicate_server_ids() {
        val validator = ConfigValidator(ConfigErrorHandler(NoopLogger))
        val config =
            McpServersConfig(
                servers =
                    listOf(
                        server("alpha", TransportConfig.StdioTransport("cmd")),
                        server("alpha", TransportConfig.StreamableHttpTransport("http://localhost:8080")),
                    ),
            )

        assertFailsWith<ConfigurationException> {
            validator.validate(config)
        }
    }

    @Test
    fun validate_accepts_oauth_on_stdio_transport_with_bootstrap() {
        val validator = ConfigValidator(ConfigErrorHandler(NoopLogger))
        val config =
            McpServersConfig(
                servers =
                    listOf(
                        server(
                            "alpha",
                            TransportConfig.StdioTransport("cmd"),
                            auth =
                                AuthConfig.OAuth(
                                    stdioBootstrap =
                                        AuthConfig.StdioBootstrap(
                                            tool = "start_google_auth",
                                            args = mapOf("service_name" to "Gmail"),
                                        ),
                                ),
                        ),
                    ),
            )
        validator.validate(config)
    }

    @Test
    fun validate_rejects_stdio_bootstrap_for_remote_transport() {
        val validator = ConfigValidator(ConfigErrorHandler(NoopLogger))
        val config =
            McpServersConfig(
                servers =
                    listOf(
                        server(
                            "alpha",
                            TransportConfig.StreamableHttpTransport("http://localhost:8080"),
                            auth =
                                AuthConfig.OAuth(
                                    stdioBootstrap =
                                        AuthConfig.StdioBootstrap(
                                            tool = "start_google_auth",
                                        ),
                                ),
                        ),
                    ),
            )

        assertFailsWith<ConfigurationException> {
            validator.validate(config)
        }
    }

    @Test
    fun validate_rejects_redirect_uri_without_port() {
        val validator = ConfigValidator(ConfigErrorHandler(NoopLogger))
        val config =
            McpServersConfig(
                servers =
                    listOf(
                        server(
                            "alpha",
                            TransportConfig.StreamableHttpTransport("http://localhost:8080"),
                            auth =
                                AuthConfig.OAuth(
                                    clientId = "client",
                                    redirectUri = "http://localhost/callback",
                                ),
                        ),
                    ),
            )

        assertFailsWith<ConfigurationException> {
            validator.validate(config)
        }
    }

    @Test
    fun validate_rejects_client_id_metadata_without_path() {
        val validator = ConfigValidator(ConfigErrorHandler(NoopLogger))
        val config =
            McpServersConfig(
                servers =
                    listOf(
                        server(
                            "alpha",
                            TransportConfig.StreamableHttpTransport("http://localhost:8080"),
                            auth =
                                AuthConfig.OAuth(
                                    clientId = "client",
                                    redirectUri = "http://localhost:8080/callback",
                                    clientIdMetadataUrl = "https://auth.example.com",
                                ),
                        ),
                    ),
            )

        assertFailsWith<ConfigurationException> {
            validator.validate(config)
        }
    }

    @Test
    fun validate_rejects_non_https_authorization_server() {
        val validator = ConfigValidator(ConfigErrorHandler(NoopLogger))
        val config =
            McpServersConfig(
                servers =
                    listOf(
                        server(
                            "alpha",
                            TransportConfig.StreamableHttpTransport("http://localhost:8080"),
                            auth =
                                AuthConfig.OAuth(
                                    clientId = "client",
                                    redirectUri = "http://localhost:8080/callback",
                                    authorizationServer = "http://example.com",
                                ),
                        ),
                    ),
            )

        assertFailsWith<ConfigurationException> {
            validator.validate(config)
        }
    }

    @Test
    fun validate_rejects_invalid_token_endpoint_auth_method() {
        val validator = ConfigValidator(ConfigErrorHandler(NoopLogger))
        val config =
            McpServersConfig(
                servers =
                    listOf(
                        server(
                            "alpha",
                            TransportConfig.StreamableHttpTransport("http://localhost:8080"),
                            auth =
                                AuthConfig.OAuth(
                                    clientId = "client",
                                    redirectUri = "http://localhost:8080/callback",
                                    tokenEndpointAuthMethod = "unknown",
                                ),
                        ),
                    ),
            )

        assertFailsWith<ConfigurationException> {
            validator.validate(config)
        }
    }

    @Test
    fun validate_accepts_valid_oauth_config() {
        val validator = ConfigValidator(ConfigErrorHandler(NoopLogger))
        val config =
            McpServersConfig(
                servers =
                    listOf(
                        server(
                            "alpha",
                            TransportConfig.StreamableHttpTransport("http://localhost:8080"),
                            auth =
                                AuthConfig.OAuth(
                                    clientId = "client",
                                    clientSecret = "secret",
                                    redirectUri = "http://127.0.0.1:8080/callback",
                                    clientIdMetadataUrl = "https://auth.example.com/metadata",
                                    authorizationServer = "https://auth.example.com",
                                    tokenEndpointAuthMethod = "client_secret_post",
                                    scopes = listOf("files:read"),
                                ),
                        ),
                    ),
            )

        validator.validate(config)
    }

    @Test
    fun validate_accepts_https_loopback_redirect_uri() {
        val validator = ConfigValidator(ConfigErrorHandler(NoopLogger))
        val localhostConfig =
            McpServersConfig(
                servers =
                    listOf(
                        server(
                            "alpha",
                            TransportConfig.StreamableHttpTransport("http://localhost:8080"),
                            auth =
                                AuthConfig.OAuth(
                                    clientId = "client",
                                    redirectUri = "https://localhost:8080/callback",
                                ),
                        ),
                    ),
            )
        val loopbackConfig =
            McpServersConfig(
                servers =
                    listOf(
                        server(
                            "beta",
                            TransportConfig.StreamableHttpTransport("http://localhost:8080"),
                            auth =
                                AuthConfig.OAuth(
                                    clientId = "client",
                                    redirectUri = "https://127.0.0.1:8081/callback",
                                ),
                        ),
                    ),
            )

        validator.validate(localhostConfig)
        validator.validate(loopbackConfig)
    }

    private fun server(
        id: String,
        transport: TransportConfig,
        auth: AuthConfig? = null,
    ): McpServerConfig =
        McpServerConfig(
            id = id,
            name = id,
            transport = transport,
            env = emptyMap(),
            enabled = true,
            auth = auth,
        )

    private object NoopLogger : Logger {
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
