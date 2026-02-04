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
                transport = "http",
                command = "cmd",
                args = listOf("a"),
                url = "http://localhost:8080",
                headers = mapOf("h" to "v"),
                env = mapOf("k" to "v"),
                auth = AuthConfig.OAuth(clientId = "client"),
            )

        val variants =
            listOf(
                base.copy(name = "Other"),
                base.copy(enabled = false),
                base.copy(transport = "sse"),
                base.copy(command = "cmd2"),
                base.copy(args = listOf("b")),
                base.copy(url = "http://localhost:8081"),
                base.copy(headers = mapOf("h2" to "v2")),
                base.copy(env = mapOf("k2" to "v2")),
                base.copy(auth = null),
            )

        variants.forEach { variant ->
            assertTrue(base != variant)
        }
    }

    @Test
    fun file_mcp_root_compares_all_fields() {
        val server = FileMcpServer(transport = "stdio", command = "cmd")
        val base =
            FileMcpRoot(
                defaultPresetId = "preset",
                inboundHttpPort = 3335,
                requestTimeoutSeconds = 10,
                capabilitiesTimeoutSeconds = 11,
                authorizationTimeoutSeconds = 12,
                connectionRetryCount = 2,
                capabilitiesRefreshIntervalSeconds = 13,
                fallbackPromptsAndResourcesToTools = true,
                adapterMode = true,
                mcpServers = mapOf("s1" to server),
            )

        val variants =
            listOf(
                base.copy(defaultPresetId = "other"),
                base.copy(inboundHttpPort = 3336),
                base.copy(requestTimeoutSeconds = 20),
                base.copy(capabilitiesTimeoutSeconds = 21),
                base.copy(authorizationTimeoutSeconds = 22),
                base.copy(connectionRetryCount = 3),
                base.copy(capabilitiesRefreshIntervalSeconds = 14),
                base.copy(fallbackPromptsAndResourcesToTools = false),
                base.copy(adapterMode = false),
                base.copy(mcpServers = emptyMap()),
            )

        variants.forEach { variant ->
            assertTrue(base != variant)
        }
    }
}
