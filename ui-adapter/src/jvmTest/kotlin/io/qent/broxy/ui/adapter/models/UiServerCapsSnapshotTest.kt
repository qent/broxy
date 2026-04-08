package io.qent.broxy.ui.adapter.models

import io.qent.broxy.ui.adapter.capabilities.CapabilityArgument
import io.qent.broxy.ui.adapter.capabilities.PromptSummary
import io.qent.broxy.ui.adapter.capabilities.ResourceSummary
import io.qent.broxy.ui.adapter.capabilities.ServerCapsSnapshot
import io.qent.broxy.ui.adapter.capabilities.ToolSummary
import kotlin.test.Test
import kotlin.test.assertEquals

class UiServerCapsSnapshotTest {
    @Test
    fun toUiModel_maps_tools_prompts_resources_and_arguments() {
        val snapshot =
            ServerCapsSnapshot(
                serverId = "s1",
                name = "Server 1",
                tools =
                    listOf(
                        ToolSummary(
                            name = "search",
                            description = "Search docs",
                            arguments =
                                listOf(
                                    CapabilityArgument(name = "query", type = "string", required = true),
                                ),
                        ),
                    ),
                prompts =
                    listOf(
                        PromptSummary(
                            name = "summarize",
                            description = "Summarize",
                            arguments =
                                listOf(
                                    CapabilityArgument(name = "text", type = "string", required = true),
                                ),
                        ),
                    ),
                resources =
                    listOf(
                        ResourceSummary(
                            key = "doc://id",
                            name = "Doc",
                            description = "Documentation",
                            arguments =
                                listOf(
                                    CapabilityArgument(name = "id", type = "string", required = true),
                                ),
                        ),
                    ),
            )

        val ui = snapshot.toUiModel()

        assertEquals("s1", ui.serverId)
        assertEquals("Server 1", ui.name)
        assertEquals("search", ui.tools.single().name)
        assertEquals(
            "query",
            ui.tools
                .single()
                .arguments
                .single()
                .name,
        )
        assertEquals("summarize", ui.prompts.single().name)
        assertEquals(
            "text",
            ui.prompts
                .single()
                .arguments
                .single()
                .name,
        )
        assertEquals("doc://id", ui.resources.single().key)
        assertEquals(
            "id",
            ui.resources
                .single()
                .arguments
                .single()
                .name,
        )
    }
}
