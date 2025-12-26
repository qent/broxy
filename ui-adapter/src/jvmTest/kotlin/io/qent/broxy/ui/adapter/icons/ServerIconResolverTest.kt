package io.qent.broxy.ui.adapter.icons

import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.ui.adapter.models.UiServerIcon
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerIconResolverTest {
    @Test
    fun resolvesBraveFromDockerStdio() {
        val config =
            McpServerConfig(
                id = "brave",
                name = "Brave",
                transport =
                    TransportConfig.StdioTransport(
                        command = "docker",
                        args = listOf("run", "docker.io/mcp/brave-search", "--rm"),
                    ),
            )

        val icon = ServerIconResolver.resolve(config)

        assertEquals(UiServerIcon.Asset("brave"), icon)
    }

    @Test
    fun resolvesContext7FromStreamableHttpUrl() {
        val config =
            McpServerConfig(
                id = "context7",
                name = "Context7",
                transport =
                    TransportConfig.StreamableHttpTransport(
                        url = "https://mcp.context7.com/mcp",
                        headers = emptyMap(),
                    ),
            )

        val icon = ServerIconResolver.resolve(config)

        assertEquals(UiServerIcon.Asset("context7"), icon)
    }

    @Test
    fun resolvesExaFromStreamableHttpUrl() {
        val config =
            McpServerConfig(
                id = "exa",
                name = "Exa",
                transport =
                    TransportConfig.StreamableHttpTransport(
                        url = "https://mcp.exa.ai/mcp",
                        headers = emptyMap(),
                    ),
            )

        val icon = ServerIconResolver.resolve(config)

        assertEquals(UiServerIcon.Asset("exa"), icon)
    }

    @Test
    fun resolvesNotionFromStreamableHttpUrl() {
        val config =
            McpServerConfig(
                id = "notion",
                name = "Notion",
                transport =
                    TransportConfig.StreamableHttpTransport(
                        url = "https://mcp.notion.com/mcp",
                        headers = emptyMap(),
                    ),
            )

        val icon = ServerIconResolver.resolve(config)

        assertEquals(UiServerIcon.Asset("notion"), icon)
    }

    @Test
    fun resolvesJetBrainsFromHttpHeader() {
        val config =
            McpServerConfig(
                id = "jetbrains",
                name = "JetBrains",
                transport =
                    TransportConfig.HttpTransport(
                        url = "https://example.com/sse",
                        headers = mapOf("IJ_MCP_SERVER_PROJECT_PATH" to "/tmp/project"),
                    ),
            )

        val icon = ServerIconResolver.resolve(config)

        assertEquals(UiServerIcon.Asset("jetbrains"), icon)
    }

    @Test
    fun fallsBackToDefaultWhenNoRuleMatches() {
        val config =
            McpServerConfig(
                id = "github",
                name = "GitHub",
                transport =
                    TransportConfig.StdioTransport(
                        command = "npx",
                        args = listOf("@modelcontextprotocol/server-github"),
                    ),
            )

        val icon = ServerIconResolver.resolve(config)

        assertEquals(UiServerIcon.Default, icon)
    }
}
