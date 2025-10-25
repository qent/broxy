package io.qent.broxy.ui.adapter.icons

import io.qent.broxy.ui.adapter.models.UiHttpTransport
import io.qent.broxy.ui.adapter.models.UiMcpServerConfig
import io.qent.broxy.ui.adapter.models.UiServerIcon
import io.qent.broxy.ui.adapter.models.UiStdioTransport
import io.qent.broxy.ui.adapter.models.UiStreamableHttpTransport
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerIconResolverTest {
    @Test
    fun resolvesBraveFromDockerStdio() {
        val config =
            UiMcpServerConfig(
                id = "brave",
                name = "Brave",
                transport =
                    UiStdioTransport(
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
            UiMcpServerConfig(
                id = "context7",
                name = "Context7",
                transport =
                    UiStreamableHttpTransport(
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
            UiMcpServerConfig(
                id = "exa",
                name = "Exa",
                transport =
                    UiStreamableHttpTransport(
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
            UiMcpServerConfig(
                id = "notion",
                name = "Notion",
                transport =
                    UiStreamableHttpTransport(
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
            UiMcpServerConfig(
                id = "jetbrains",
                name = "JetBrains",
                transport =
                    UiHttpTransport(
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
            UiMcpServerConfig(
                id = "github",
                name = "GitHub",
                transport =
                    UiStdioTransport(
                        command = "npx",
                        args = listOf("@modelcontextprotocol/server-github"),
                    ),
            )

        val icon = ServerIconResolver.resolve(config)

        assertEquals(UiServerIcon.Default, icon)
    }
}
