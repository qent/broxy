package io.qent.broxy.ui.components

import io.qent.broxy.ui.adapter.models.UiPromptRef
import io.qent.broxy.ui.adapter.models.UiPromptSummary
import io.qent.broxy.ui.adapter.models.UiResourceRef
import io.qent.broxy.ui.adapter.models.UiResourceSummary
import io.qent.broxy.ui.adapter.models.UiServerCapsSnapshot
import io.qent.broxy.ui.adapter.models.UiToolRef
import io.qent.broxy.ui.adapter.models.UiToolSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PresetSelectorLogicTest {
    @Test
    fun `toggle visible capability selection adds only visible names when checked`() {
        val updated =
            toggleVisibleCapabilitySelection(
                currentSelection = setOf("hidden_kept"),
                visibleNames = setOf("visible_a", "visible_b"),
                checked = true,
            )

        assertEquals(setOf("hidden_kept", "visible_a", "visible_b"), updated)
    }

    @Test
    fun `toggle visible capability selection removes only visible names when unchecked`() {
        val updated =
            toggleVisibleCapabilitySelection(
                currentSelection = setOf("hidden_kept", "visible_a", "visible_b"),
                visibleNames = setOf("visible_a", "visible_b"),
                checked = false,
            )

        assertEquals(setOf("hidden_kept"), updated)
    }

    @Test
    fun `initialize selection keeps enabled refs for missing servers`() {
        val selection =
            initializePresetSelectorSelection(
                PresetSelectorInitializationInput(
                    initialToolRefs =
                        listOf(
                            UiToolRef(serverId = "enabled", toolName = "tool_enabled", enabled = true),
                            UiToolRef(serverId = "missing", toolName = "tool_missing", enabled = true),
                            UiToolRef(serverId = "enabled", toolName = "tool_disabled", enabled = false),
                        ),
                    initialPromptRefs =
                        listOf(
                            UiPromptRef(serverId = "enabled", promptName = "prompt_enabled", enabled = true),
                            UiPromptRef(serverId = "missing", promptName = "prompt_missing", enabled = true),
                            UiPromptRef(serverId = "enabled", promptName = "prompt_disabled", enabled = false),
                        ),
                    initialResourceRefs =
                        listOf(
                            UiResourceRef(serverId = "enabled", resourceKey = "resource_enabled", enabled = true),
                            UiResourceRef(serverId = "missing", resourceKey = "resource_missing", enabled = true),
                            UiResourceRef(serverId = "enabled", resourceKey = "resource_disabled", enabled = false),
                        ),
                    serverCapsSnapshots = listOf(enabledServerSnapshot),
                    promptsConfigured = true,
                    resourcesConfigured = true,
                ),
            )

        assertEquals(
            mapOf(
                "enabled" to setOf("tool_enabled"),
                "missing" to setOf("tool_missing"),
            ),
            selection.toolsByServer,
        )
        assertEquals(
            mapOf(
                "enabled" to setOf("prompt_enabled"),
                "missing" to setOf("prompt_missing"),
            ),
            selection.promptsByServer,
        )
        assertEquals(
            mapOf(
                "enabled" to setOf("resource_enabled"),
                "missing" to setOf("resource_missing"),
            ),
            selection.resourcesByServer,
        )

        val refs = buildPresetSelectorRefs(selection)
        assertTrue(refs.tools.contains(UiToolRef(serverId = "missing", toolName = "tool_missing", enabled = true)))
        assertTrue(refs.prompts.contains(UiPromptRef(serverId = "missing", promptName = "prompt_missing", enabled = true)))
        assertTrue(refs.resources.contains(UiResourceRef(serverId = "missing", resourceKey = "resource_missing", enabled = true)))
    }

    private companion object {
        val enabledServerSnapshot =
            UiServerCapsSnapshot(
                serverId = "enabled",
                name = "Enabled Server",
                tools = listOf(UiToolSummary(name = "tool_enabled", description = "Enabled tool")),
                prompts = listOf(UiPromptSummary(name = "prompt_enabled", description = "Enabled prompt")),
                resources =
                    listOf(
                        UiResourceSummary(
                            key = "resource_enabled",
                            name = "resource_enabled",
                            description = "Enabled resource",
                        ),
                    ),
            )
    }
}
