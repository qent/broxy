package io.qent.broxy.core.models

import kotlin.test.Test
import kotlin.test.assertTrue

class ModelEqualityTest {
    @Test
    fun mcp_servers_config_changes_when_fields_differ() {
        val server =
            McpServerConfig(
                id = "s1",
                name = "Server",
                transport = TransportConfig.StdioTransport(command = "cmd"),
                env = mapOf("A" to "1"),
                enabled = true,
                auth = null,
            )
        val base =
            McpServersConfig(
                servers = listOf(server),
                defaultPresetId = "preset",
                inboundHttpPort = 3335,
                requestTimeoutSeconds = 10,
                capabilitiesTimeoutSeconds = 11,
                authorizationTimeoutSeconds = 12,
                connectionRetryCount = 2,
                capabilitiesRefreshIntervalSeconds = 13,
                fallbackPromptsAndResourcesToTools = true,
                adapterMode = true,
            )

        val variants =
            listOf(
                base.copy(servers = emptyList()),
                base.copy(defaultPresetId = "other"),
                base.copy(inboundHttpPort = 9999),
                base.copy(requestTimeoutSeconds = 20),
                base.copy(capabilitiesTimeoutSeconds = 21),
                base.copy(authorizationTimeoutSeconds = 22),
                base.copy(connectionRetryCount = 3),
                base.copy(capabilitiesRefreshIntervalSeconds = 14),
                base.copy(fallbackPromptsAndResourcesToTools = false),
                base.copy(adapterMode = false),
            )

        variants.forEach { variant ->
            assertTrue(base != variant)
        }
    }

    @Test
    fun auth_config_oauth_compares_all_fields() {
        val base =
            AuthConfig.OAuth(
                clientId = "client",
                clientSecret = "secret",
                clientIdMetadataUrl = "https://auth.example.com/metadata",
                redirectUri = "http://localhost:8080/callback",
                clientName = "Broxy",
                tokenEndpointAuthMethod = "client_secret_basic",
                authorizationServer = "https://auth.example.com",
                scopes = listOf("files:read"),
                allowDynamicRegistration = true,
            )

        val variants =
            listOf(
                base.copy(clientId = "other"),
                base.copy(clientSecret = "changed"),
                base.copy(clientIdMetadataUrl = "https://auth.example.com/other"),
                base.copy(redirectUri = "http://localhost:8081/callback"),
                base.copy(clientName = "Other"),
                base.copy(tokenEndpointAuthMethod = "none"),
                base.copy(authorizationServer = "https://auth2.example.com"),
                base.copy(scopes = listOf("files:write")),
                base.copy(allowDynamicRegistration = false),
            )

        variants.forEach { variant ->
            assertTrue(base != variant)
        }
    }

    @Test
    fun preset_and_references_compare_fields() {
        val tool = ToolReference(serverId = "s1", toolName = "t1", enabled = true)
        val prompt = PromptReference(serverId = "s1", promptName = "p1", enabled = true)
        val resource = ResourceReference(serverId = "s1", resourceKey = "r1", enabled = true)
        val base =
            Preset(
                id = "p1",
                name = "Preset",
                tools = listOf(tool),
                prompts = listOf(prompt),
                resources = listOf(resource),
                createdAtEpochMillis = 1L,
            )

        val variants =
            listOf(
                base.copy(id = "p2"),
                base.copy(name = "Other"),
                base.copy(tools = listOf(tool.copy(toolName = "t2"))),
                base.copy(prompts = listOf(prompt.copy(promptName = "p2"))),
                base.copy(resources = listOf(resource.copy(resourceKey = "r2"))),
                base.copy(createdAtEpochMillis = 2L),
            )

        variants.forEach { variant ->
            assertTrue(base != variant)
        }

        assertTrue(tool != tool.copy(toolName = "t2"))
        assertTrue(prompt != prompt.copy(promptName = "p2"))
        assertTrue(resource != resource.copy(resourceKey = "r2"))
    }

    @Test
    fun transport_configs_compare_fields() {
        assertTrue(
            TransportConfig.StdioTransport(command = "a") !=
                TransportConfig.StdioTransport(command = "b"),
        )
        assertTrue(
            TransportConfig.StreamableHttpTransport(url = "http://a") !=
                TransportConfig.StreamableHttpTransport(url = "http://b"),
        )
        assertTrue(
            TransportConfig.HttpTransport(url = "http://a") !=
                TransportConfig.HttpTransport(url = "http://b"),
        )
        assertTrue(
            TransportConfig.WebSocketTransport(url = "ws://a") !=
                TransportConfig.WebSocketTransport(url = "ws://b"),
        )
    }
}
