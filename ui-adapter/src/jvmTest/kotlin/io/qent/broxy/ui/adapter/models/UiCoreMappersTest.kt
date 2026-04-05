package io.qent.broxy.ui.adapter.models

import io.qent.broxy.core.models.AuthConfig
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.PromptReference
import io.qent.broxy.core.models.ResourceReference
import io.qent.broxy.core.models.ToolReference
import io.qent.broxy.core.models.TransportConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UiCoreMappersTest {
    @Test
    fun configRoundTrip_preservesValues() {
        val config =
            McpServersConfig(
                servers =
                    listOf(
                        McpServerConfig(
                            id = "stdio",
                            name = "Stdio Server",
                            transport = TransportConfig.StdioTransport(command = "cmd", args = listOf("--flag")),
                            env = mapOf("PATH" to "/usr/bin"),
                            enabled = true,
                        ),
                        McpServerConfig(
                            id = "sse",
                            name = "SSE Server",
                            transport = TransportConfig.HttpTransport(url = "https://example.test/sse", headers = mapOf("H" to "V")),
                            enabled = false,
                        ),
                        McpServerConfig(
                            id = "http",
                            name = "HTTP Server",
                            transport = TransportConfig.StreamableHttpTransport(url = "https://example.test/mcp"),
                            auth =
                                AuthConfig.OAuth(
                                    clientId = "client",
                                    clientSecret = "secret",
                                    clientIdMetadataUrl = "https://example.test/metadata",
                                    redirectUri = "http://localhost/callback",
                                    clientName = "Broxy",
                                    tokenEndpointAuthMethod = "client_secret_basic",
                                    authorizationServer = "https://example.test/auth",
                                    scopes = listOf("read", "write"),
                                    allowDynamicRegistration = false,
                                    stdioBootstrap =
                                        AuthConfig.StdioBootstrap(
                                            tool = "start_google_auth",
                                            args = mapOf("service_name" to "Gmail"),
                                        ),
                                ),
                        ),
                        McpServerConfig(
                            id = "ws",
                            name = "WS Server",
                            transport = TransportConfig.WebSocketTransport(url = "wss://example.test/ws"),
                        ),
                    ),
                defaultPresetId = "preset-1",
                inboundHttpPort = 7777,
                requestTimeoutSeconds = 11,
                capabilitiesTimeoutSeconds = 22,
                authorizationTimeoutSeconds = 33,
                connectionRetryCount = 2,
                capabilitiesRefreshIntervalSeconds = 44,
                fallbackPromptsAndResourcesToTools = true,
                adapterMode = true,
            )

        val roundTrip = config.toUi().toCore()

        assertEquals(config, roundTrip)
    }

    @Test
    fun presetRoundTrip_preservesListsAndNulls() {
        val presetWithLists =
            Preset(
                id = "preset-full",
                name = "Full",
                tools = listOf(ToolReference(serverId = "s1", toolName = "tool", enabled = false)),
                prompts = listOf(PromptReference(serverId = "s1", promptName = "prompt", enabled = true)),
                resources = listOf(ResourceReference(serverId = "s1", resourceKey = "res", enabled = true)),
                orderIndex = 42,
            )
        val roundTripLists = presetWithLists.toUi().toCore()
        assertEquals(presetWithLists, roundTripLists)

        val presetWithNulls =
            Preset(
                id = "preset-null",
                name = "Nulls",
                tools = emptyList(),
                prompts = null,
                resources = null,
            )
        val uiPreset = presetWithNulls.toUi()
        assertNull(uiPreset.prompts)
        assertNull(uiPreset.resources)
        assertEquals(presetWithNulls, uiPreset.toCore())
    }
}
