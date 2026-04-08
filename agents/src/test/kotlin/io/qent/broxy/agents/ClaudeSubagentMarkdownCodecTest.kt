package io.qent.broxy.agents

import io.qent.broxy.agents.infrastructure.persistence.ClaudeSubagentMarkdownCodec
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.utils.ConfigurationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClaudeSubagentMarkdownCodecTest {
    private val codec = ClaudeSubagentMarkdownCodec()

    @Test
    fun decode_requiresNameAndDescription() {
        val markdown =
            """
            ---
            name: Missing description
            ---
            Prompt
            """.trimIndent()

        assertFailsWith<ConfigurationException> {
            codec.decode(markdown, "missing.md")
        }
    }

    @Test
    fun decode_parsesClaudeMcpServers_withIdsAndInlineConfigs() {
        val markdown =
            """
            ---
            name: MCP agent
            description: Parses MCP servers.
            mcpServers:
              - github
              - id: local-stdio
                type: stdio
                command: npx
                args:
                  - -y
                  - some-server
              - cloud-http:
                  type: http
                  url: https://example.test/mcp
            ---
            System prompt
            """.trimIndent()

        val parsed = codec.decode(markdown, "mcp-agent.md")
        assertEquals(3, parsed.mcpServers?.size)
        val stdio = parsed.mcpServers?.firstOrNull { it.id == "local-stdio" }?.inlineConfig
        assertNotNull(stdio)
        val transport = stdio.transport as? TransportConfig.StdioTransport
        assertNotNull(transport)
        assertEquals("npx", transport.command)
        assertEquals(listOf("-y", "some-server"), transport.args)
    }

    @Test
    fun encode_preservesUnknownFrontmatterFields() {
        val markdown =
            """
            ---
            name: Unknown fields
            description: Keep unknown keys.
            hooks:
              PostToolUse:
                - command: echo done
            customFlag: true
            ---
            Original prompt
            """.trimIndent()
        val decoded = codec.decode(markdown, "unknown.md")
        val encoded =
            codec.encode(
                agent =
                    AgentDefinition(
                        id = "unknown",
                        name = decoded.name,
                        systemPrompt = "Updated prompt",
                        description = decoded.description,
                    ),
                existingFrontmatter = decoded.frontmatter,
            )

        assertTrue(encoded.contains("hooks"))
        assertTrue(encoded.contains("customFlag"))
        assertTrue(encoded.contains("Updated prompt"))
    }
}
