package io.qent.broxy.ui.screens

import io.qent.broxy.ui.adapter.models.UiServerCapsSnapshot
import io.qent.broxy.ui.adapter.models.UiToolSummary
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerCapabilitiesScreenLogicTest {
    @Test
    fun `shouldShowCapabilitiesSearchField returns false when snapshot has no capabilities`() {
        assertEquals(
            false,
            shouldShowCapabilitiesSearchField(
                UiServerCapsSnapshot(
                    serverId = "test",
                    name = "Test",
                    tools = emptyList(),
                    prompts = emptyList(),
                    resources = emptyList(),
                ),
            ),
        )
    }

    @Test
    fun `shouldShowCapabilitiesSearchField returns true when snapshot has at least one capability`() {
        assertEquals(
            true,
            shouldShowCapabilitiesSearchField(
                UiServerCapsSnapshot(
                    serverId = "test",
                    name = "Test",
                    tools = listOf(UiToolSummary(name = "search", description = "Search tool")),
                    prompts = emptyList(),
                    resources = emptyList(),
                ),
            ),
        )
    }
}
