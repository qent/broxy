package io.qent.broxy.ui.screens

import io.qent.broxy.ui.adapter.models.UiPromptRef
import io.qent.broxy.ui.adapter.models.UiPromptSummary
import io.qent.broxy.ui.adapter.models.UiResourceRef
import io.qent.broxy.ui.adapter.models.UiResourceSummary
import io.qent.broxy.ui.adapter.models.UiServerCapsSnapshot
import io.qent.broxy.ui.adapter.models.UiToolRef
import io.qent.broxy.ui.adapter.models.UiToolSummary
import io.qent.broxy.ui.components.PresetSelectorInitializationInput
import io.qent.broxy.ui.components.buildPresetSelectorRefs
import io.qent.broxy.ui.components.initializePresetSelectorSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PresetEditorSelectionLogicTest {
    @Test
    fun `merge keeps existing disabled refs`() {
        val mergedTools =
            mergePresetEditorToolRefs(
                existing =
                    listOf(
                        UiToolRef(serverId = "s1", toolName = "tool_enabled", enabled = true),
                        UiToolRef(serverId = "s1", toolName = "tool_disabled", enabled = false),
                    ),
                selectedEnabled =
                    listOf(
                        UiToolRef(serverId = "s1", toolName = "tool_enabled", enabled = true),
                    ),
            )
        val mergedPrompts =
            mergePresetEditorPromptRefs(
                existing =
                    listOf(
                        UiPromptRef(serverId = "s1", promptName = "prompt_enabled", enabled = true),
                        UiPromptRef(serverId = "s1", promptName = "prompt_disabled", enabled = false),
                    ),
                selectedEnabled =
                    listOf(
                        UiPromptRef(serverId = "s1", promptName = "prompt_enabled", enabled = true),
                    ),
            )
        val mergedResources =
            mergePresetEditorResourceRefs(
                existing =
                    listOf(
                        UiResourceRef(serverId = "s1", resourceKey = "resource_enabled", enabled = true),
                        UiResourceRef(serverId = "s1", resourceKey = "resource_disabled", enabled = false),
                    ),
                selectedEnabled =
                    listOf(
                        UiResourceRef(serverId = "s1", resourceKey = "resource_enabled", enabled = true),
                    ),
            )

        assertTrue(mergedTools.contains(UiToolRef(serverId = "s1", toolName = "tool_disabled", enabled = false)))
        assertTrue(mergedPrompts.contains(UiPromptRef(serverId = "s1", promptName = "prompt_disabled", enabled = false)))
        assertTrue(mergedResources.contains(UiResourceRef(serverId = "s1", resourceKey = "resource_disabled", enabled = false)))
    }

    @Test
    fun `open and save without edits keeps mixed refs including missing server and disabled entries`() {
        val initialTools =
            listOf(
                UiToolRef(serverId = "enabled", toolName = "tool_enabled", enabled = true),
                UiToolRef(serverId = "missing", toolName = "tool_missing", enabled = true),
                UiToolRef(serverId = "enabled", toolName = "tool_disabled", enabled = false),
            )
        val initialPrompts =
            listOf(
                UiPromptRef(serverId = "enabled", promptName = "prompt_enabled", enabled = true),
                UiPromptRef(serverId = "missing", promptName = "prompt_missing", enabled = true),
                UiPromptRef(serverId = "enabled", promptName = "prompt_disabled", enabled = false),
            )
        val initialResources =
            listOf(
                UiResourceRef(serverId = "enabled", resourceKey = "resource_enabled", enabled = true),
                UiResourceRef(serverId = "missing", resourceKey = "resource_missing", enabled = true),
                UiResourceRef(serverId = "enabled", resourceKey = "resource_disabled", enabled = false),
            )
        val selectorSelection =
            initializePresetSelectorSelection(
                PresetSelectorInitializationInput(
                    initialToolRefs = initialTools,
                    initialPromptRefs = initialPrompts,
                    initialResourceRefs = initialResources,
                    serverCapsSnapshots = listOf(enabledServerSnapshot),
                    promptsConfigured = true,
                    resourcesConfigured = true,
                ),
            )
        val selectorRefs = buildPresetSelectorRefs(selectorSelection)

        val mergedTools = mergePresetEditorToolRefs(existing = initialTools, selectedEnabled = selectorRefs.tools)
        val mergedPrompts = mergePresetEditorPromptRefs(existing = initialPrompts, selectedEnabled = selectorRefs.prompts)
        val mergedResources = mergePresetEditorResourceRefs(existing = initialResources, selectedEnabled = selectorRefs.resources)

        assertEquals(initialTools.toSet(), mergedTools.toSet())
        assertEquals(initialPrompts.toSet(), mergedPrompts.toSet())
        assertEquals(initialResources.toSet(), mergedResources.toSet())
    }

    @Test
    fun `auto-fill preset name from server only for create and blank name`() {
        assertEquals(
            "Server A",
            autoFillPresetNameFromServerSelection(
                currentName = "",
                selectedServerName = "Server A",
                isCreateMode = true,
                selectedWholeServer = true,
            ),
        )
        assertEquals(
            "manual",
            autoFillPresetNameFromServerSelection(
                currentName = "manual",
                selectedServerName = "Server A",
                isCreateMode = true,
                selectedWholeServer = true,
            ),
        )
        assertEquals(
            "",
            autoFillPresetNameFromServerSelection(
                currentName = "",
                selectedServerName = "Server A",
                isCreateMode = false,
                selectedWholeServer = true,
            ),
        )
        assertEquals(
            "",
            autoFillPresetNameFromServerSelection(
                currentName = "",
                selectedServerName = "Server A",
                isCreateMode = true,
                selectedWholeServer = false,
            ),
        )
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
