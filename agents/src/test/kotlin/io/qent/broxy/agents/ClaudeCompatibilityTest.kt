package io.qent.broxy.agents

import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.TransportConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClaudeCompatibilityTest {
    @Test
    fun resolveClaudeFileSystemAccess_mapsToolsToReadWrite() {
        val agent =
            AgentDefinition(
                id = "a",
                name = "A",
                systemPrompt = "prompt",
                description = "desc",
                claudeTools = listOf("Read", "Edit", "Bash"),
            )

        val resolved =
            resolveClaudeFileSystemAccess(
                agent = agent,
                requestedAccess = AgentFileSystemAccess.NONE,
            )

        assertEquals(AgentFileSystemAccess.READ_WRITE, resolved.access)
        assertTrue(resolved.warnings.isNotEmpty())
    }

    @Test
    fun resolveClaudeFileSystemAccess_appliesDisallowedWriteTools() {
        val agent =
            AgentDefinition(
                id = "a",
                name = "A",
                systemPrompt = "prompt",
                description = "desc",
                claudeTools = listOf("Read", "Edit"),
                claudeDisallowedTools = listOf("Edit"),
            )

        val resolved =
            resolveClaudeFileSystemAccess(
                agent = agent,
                requestedAccess = AgentFileSystemAccess.READ_WRITE,
            )

        assertEquals(AgentFileSystemAccess.READ_ONLY, resolved.access)
    }

    @Test
    fun resolveClaudePermissionModeWarning_isAdvisoryOnly() {
        val agent =
            AgentDefinition(
                id = "a",
                name = "A",
                systemPrompt = "prompt",
                description = "desc",
                claudePermissionMode = "bypassPermissions",
            )

        val warning = resolveClaudePermissionModeWarning(agent)
        assertNotNull(warning)
        assertTrue(warning.contains("advisory-only"))
    }

    @Test
    fun mergeAgentMcpServers_appliesInlineOverrideAndReportsMissingIds() {
        val base =
            McpServersConfig(
                servers =
                    listOf(
                        McpServerConfig(
                            id = "github",
                            name = "GitHub",
                            transport =
                                TransportConfig.StreamableHttpTransport(
                                    url = "https://old.example.com/mcp",
                                ),
                        ),
                    ),
            )
        val agent =
            AgentDefinition(
                id = "a",
                name = "A",
                systemPrompt = "prompt",
                description = "desc",
                claudeMcpServers =
                    listOf(
                        AgentMcpServerReference(id = "missing-only-id"),
                        AgentMcpServerReference(
                            id = "github",
                            inlineConfig =
                                McpServerConfig(
                                    id = "github",
                                    name = "GitHub override",
                                    transport =
                                        TransportConfig.StreamableHttpTransport(
                                            url = "https://new.example.com/mcp",
                                        ),
                                ),
                        ),
                    ),
            )

        val merged = mergeAgentMcpServers(base, agent)
        val github = merged.config.servers.firstOrNull { it.id == "github" }
        val transport = github?.transport as? TransportConfig.StreamableHttpTransport
        assertNotNull(transport)
        assertEquals("https://new.example.com/mcp", transport.url)
        assertTrue(merged.warnings.any { it.contains("missing-only-id") })
        assertTrue(merged.warnings.any { it.contains("overrides server 'github'") })
    }
}
