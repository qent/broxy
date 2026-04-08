package io.qent.broxy.agents.runtime.mcp

import io.qent.broxy.agents.AgentDefinition
import io.qent.broxy.agents.AgentMcpServerReference
import io.qent.broxy.core.models.ToolReference
import kotlin.test.Test
import kotlin.test.assertEquals

class ScopedMcpConnectionsFactoryTest {
    @Test
    fun resolveUsedServerIds_includesClaudeMcpServerIds() {
        val agent =
            AgentDefinition(
                id = "a",
                name = "A",
                systemPrompt = "prompt",
                description = "desc",
                tools = listOf(ToolReference(serverId = "tools-server", toolName = "search", enabled = true)),
                claudeMcpServers =
                    listOf(
                        AgentMcpServerReference(id = "mcp-id-1"),
                        AgentMcpServerReference(id = "mcp-id-2"),
                    ),
            )

        val resolved = resolveUsedServerIds(agent)
        assertEquals(
            setOf("tools-server", "mcp-id-1", "mcp-id-2"),
            resolved,
        )
    }
}
