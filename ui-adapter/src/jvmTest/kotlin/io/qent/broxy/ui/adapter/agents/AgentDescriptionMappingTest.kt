package io.qent.broxy.ui.adapter.agents

import io.qent.broxy.ui.adapter.models.UiAgentDraft
import io.qent.broxy.ui.adapter.models.UiCapabilityArgument
import io.qent.broxy.ui.adapter.models.UiPromptRef
import io.qent.broxy.ui.adapter.models.UiPromptSummary
import io.qent.broxy.ui.adapter.models.UiResourceRef
import io.qent.broxy.ui.adapter.models.UiResourceSummary
import io.qent.broxy.ui.adapter.models.UiServerCapsSnapshot
import io.qent.broxy.ui.adapter.models.UiToolRef
import io.qent.broxy.ui.adapter.models.UiToolSummary
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentDescriptionMappingTest {
    @Test
    fun mapDescriptionGenerationCommand_mapsDraftAndCapabilityContext() {
        val draft =
            UiAgentDraft(
                id = "",
                name = " Agent Planner ",
                systemPrompt = " Plan tasks for users. ",
                description = " Existing summary ",
                tools = listOf(UiToolRef(serverId = "s1", toolName = "search", enabled = true)),
                prompts = listOf(UiPromptRef(serverId = "s1", promptName = "plan", enabled = true)),
                resources = listOf(UiResourceRef(serverId = "s1", resourceKey = "res://tasks", enabled = true)),
                promptsConfigured = true,
                resourcesConfigured = true,
            )
        val snapshots =
            listOf(
                UiServerCapsSnapshot(
                    serverId = "s1",
                    name = "Server One",
                    tools =
                        listOf(
                            UiToolSummary(
                                name = "search",
                                description = "Searches indexed data",
                                arguments =
                                    listOf(
                                        UiCapabilityArgument(name = "query", type = "string", required = true),
                                    ),
                            ),
                        ),
                    prompts =
                        listOf(
                            UiPromptSummary(
                                name = "plan",
                                description = "Builds plan drafts",
                                arguments =
                                    listOf(
                                        UiCapabilityArgument(name = "goal", type = "string", required = true),
                                    ),
                            ),
                        ),
                    resources =
                        listOf(
                            UiResourceSummary(
                                key = "res://tasks",
                                name = "tasks",
                                description = "Task backlog",
                                arguments =
                                    listOf(
                                        UiCapabilityArgument(name = "id", type = "string", required = false),
                                    ),
                            ),
                        ),
                ),
            )

        val command = mapDescriptionGenerationCommand(draft, snapshots)

        assertEquals("draft-agent", command.draft.id)
        assertEquals("Agent Planner", command.draft.name)
        assertEquals("Plan tasks for users.", command.draft.systemPrompt)
        assertEquals("Existing summary", command.draft.description)
        assertEquals(1, command.capabilityContext.size)
        val server = command.capabilityContext.single()
        assertEquals("s1", server.serverId)
        assertEquals("Server One", server.serverName)
        assertEquals("search", server.tools.single().name)
        assertEquals(
            "query",
            server.tools
                .single()
                .arguments
                .single()
                .name,
        )
        assertEquals(
            true,
            server.tools
                .single()
                .arguments
                .single()
                .required,
        )
        assertEquals("plan", server.prompts.single().name)
        assertEquals(
            "goal",
            server.prompts
                .single()
                .arguments
                .single()
                .name,
        )
        assertEquals("res://tasks", server.resources.single().key)
        assertEquals(
            "id",
            server.resources
                .single()
                .arguments
                .single()
                .name,
        )
        assertEquals(
            false,
            server.resources
                .single()
                .arguments
                .single()
                .required,
        )
    }

    @Test
    fun mapDescriptionGenerationCommand_respectsPromptAndResourceConfigurationFlags() {
        val draft =
            UiAgentDraft(
                id = "agent-1",
                name = "Agent 1",
                systemPrompt = "Prompt",
                tools = emptyList(),
                prompts = listOf(UiPromptRef(serverId = "s1", promptName = "plan", enabled = true)),
                resources = listOf(UiResourceRef(serverId = "s1", resourceKey = "res://tasks", enabled = true)),
                promptsConfigured = false,
                resourcesConfigured = false,
            )

        val command = mapDescriptionGenerationCommand(draft, emptyList())

        assertEquals(null, command.draft.prompts)
        assertEquals(null, command.draft.resources)
    }
}
