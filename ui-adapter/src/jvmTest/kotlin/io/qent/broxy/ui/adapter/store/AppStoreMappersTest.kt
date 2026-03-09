package io.qent.broxy.ui.adapter.store

import io.qent.broxy.ui.adapter.models.UiPresetCore
import io.qent.broxy.ui.adapter.models.UiPromptRef
import io.qent.broxy.ui.adapter.models.UiResourceRef
import io.qent.broxy.ui.adapter.models.UiToolRef
import kotlin.test.Test
import kotlin.test.assertEquals

class AppStoreMappersTest {
    @Test
    fun toUiPresetSummary_countsOnlyEnabledCapabilities_andTracksOnlyEnabledServerIds() {
        val preset =
            UiPresetCore(
                id = "dev",
                name = "Dev",
                tools =
                    listOf(
                        UiToolRef(serverId = "s1", toolName = "tool-1", enabled = true),
                        UiToolRef(serverId = "s2", toolName = "tool-2", enabled = true),
                        UiToolRef(serverId = "s3", toolName = "tool-3", enabled = false),
                    ),
                prompts =
                    listOf(
                        UiPromptRef(serverId = "s2", promptName = "prompt-1", enabled = true),
                        UiPromptRef(serverId = "s4", promptName = "prompt-2", enabled = false),
                    ),
                resources =
                    listOf(
                        UiResourceRef(serverId = "s5", resourceKey = "res-1", enabled = true),
                        UiResourceRef(serverId = "s5", resourceKey = "res-2", enabled = true),
                        UiResourceRef(serverId = "s6", resourceKey = "res-3", enabled = false),
                    ),
            )

        val summary = preset.toUiPresetSummary()

        assertEquals(2, summary.toolsCount)
        assertEquals(1, summary.promptsCount)
        assertEquals(2, summary.resourcesCount)
        assertEquals(setOf("s1", "s2"), summary.toolsServerIds)
        assertEquals(setOf("s2"), summary.promptsServerIds)
        assertEquals(setOf("s5"), summary.resourcesServerIds)
    }

    @Test
    fun toUiPresetSummary_usesEmptySets_whenPromptsAndResourcesAreNotConfigured() {
        val preset =
            UiPresetCore(
                id = "dev",
                name = "Dev",
                tools = listOf(UiToolRef(serverId = "s1", toolName = "tool-1", enabled = true)),
                prompts = null,
                resources = null,
            )

        val summary = preset.toUiPresetSummary()

        assertEquals(setOf("s1"), summary.toolsServerIds)
        assertEquals(emptySet(), summary.promptsServerIds)
        assertEquals(emptySet(), summary.resourcesServerIds)
    }
}
