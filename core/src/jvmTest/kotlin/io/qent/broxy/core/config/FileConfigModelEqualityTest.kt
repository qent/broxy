package io.qent.broxy.core.config

import io.qent.broxy.core.models.AuthConfig
import kotlin.test.Test
import kotlin.test.assertTrue

class FileConfigModelEqualityTest {
    @Test
    fun file_mcp_server_compares_all_fields() {
        val base =
            FileMcpServer(
                name = "Server",
                enabled = true,
                type = "http",
                command = "cmd",
                args = listOf("a"),
                url = "http://localhost:8080",
                headers = mapOf("h" to "v"),
                env = mapOf("k" to "v"),
                envFile = ".env",
                oauth = AuthConfig.OAuth(clientId = "client"),
                auth = AuthConfig.OAuth(clientId = "legacy-client"),
                iconPath = "icons/server.png",
            )

        val variants =
            listOf(
                base.copy(name = "Other"),
                base.copy(enabled = false),
                base.copy(type = "sse"),
                base.copy(command = "cmd2"),
                base.copy(args = listOf("b")),
                base.copy(url = "http://localhost:8081"),
                base.copy(headers = mapOf("h2" to "v2")),
                base.copy(env = mapOf("k2" to "v2")),
                base.copy(envFile = ".env.local"),
                base.copy(oauth = null),
                base.copy(auth = null),
                base.copy(iconPath = "icons/other.png"),
            )

        variants.forEach { variant ->
            assertTrue(base != variant)
        }
    }

    @Test
    fun file_mcp_root_compares_all_fields() {
        val base =
            FileMcpRoot(
                mcpServers = mapOf("s1" to FileMcpServer(type = "stdio", command = "cmd")),
            )

        val variants =
            listOf(
                base.copy(mcpServers = emptyMap()),
                base.copy(
                    mcpServers =
                        mapOf(
                            "s1" to FileMcpServer(type = "http", url = "http://localhost:8080"),
                        ),
                ),
            )

        variants.forEach { variant ->
            assertTrue(base != variant)
        }
    }

    @Test
    fun file_app_config_compares_all_fields() {
        val base =
            FileAppConfig(
                mcpFilePath = "/tmp/mcp.json",
                defaultPresetId = "preset",
                inboundHttpPort = 3335,
                requestTimeoutSeconds = 10,
                capabilitiesTimeoutSeconds = 11,
                authorizationTimeoutSeconds = 12,
                connectionRetryCount = 2,
                ignoreHttpsCertificateErrors = true,
                capabilitiesRefreshIntervalSeconds = 13,
                fallbackPromptsAndResourcesToTools = true,
                adapterMode = true,
            )

        val variants =
            listOf(
                base.copy(mcpFilePath = "/tmp/other.json"),
                base.copy(defaultPresetId = "other"),
                base.copy(inboundHttpPort = 3336),
                base.copy(requestTimeoutSeconds = 20),
                base.copy(capabilitiesTimeoutSeconds = 21),
                base.copy(authorizationTimeoutSeconds = 22),
                base.copy(connectionRetryCount = 3),
                base.copy(ignoreHttpsCertificateErrors = false),
                base.copy(capabilitiesRefreshIntervalSeconds = 14),
                base.copy(fallbackPromptsAndResourcesToTools = false),
                base.copy(adapterMode = false),
            )

        variants.forEach { variant ->
            assertTrue(base != variant)
        }
    }
}
