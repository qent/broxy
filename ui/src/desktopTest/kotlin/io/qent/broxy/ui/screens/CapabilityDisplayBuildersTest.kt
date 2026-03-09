package io.qent.broxy.ui.screens

import io.qent.broxy.ui.adapter.models.UiPromptRef
import io.qent.broxy.ui.adapter.models.UiPromptSummary
import io.qent.broxy.ui.adapter.models.UiResourceRef
import io.qent.broxy.ui.adapter.models.UiResourceSummary
import io.qent.broxy.ui.adapter.models.UiServerCapsSnapshot
import io.qent.broxy.ui.adapter.models.UiToolRef
import io.qent.broxy.ui.adapter.models.UiToolSummary
import io.qent.broxy.ui.strings.EnglishStrings
import kotlin.test.Test
import kotlin.test.assertEquals

class CapabilityDisplayBuildersTest {
    @Test
    fun `tool items keep disabled marker from server map`() {
        val items =
            buildToolCapabilityItems(
                tools =
                    listOf(
                        UiToolRef(serverId = "enabled", toolName = "tool_enabled"),
                        UiToolRef(serverId = "disabled", toolName = "tool_disabled"),
                        UiToolRef(serverId = "missing", toolName = "tool_missing"),
                    ),
                context = context,
                strings = EnglishStrings,
            )

        assertEquals(listOf(true, false, false), items.map { it.isServerEnabled })
    }

    @Test
    fun `prompt items keep disabled marker from server map`() {
        val items =
            buildPromptCapabilityItems(
                prompts =
                    listOf(
                        UiPromptRef(serverId = "enabled", promptName = "prompt_enabled"),
                        UiPromptRef(serverId = "disabled", promptName = "prompt_disabled"),
                        UiPromptRef(serverId = "missing", promptName = "prompt_missing"),
                    ),
                context = context,
                strings = EnglishStrings,
            )

        assertEquals(listOf(true, false, false), items.map { it.isServerEnabled })
    }

    @Test
    fun `resource items keep disabled marker from server map`() {
        val items =
            buildResourceCapabilityItems(
                resources =
                    listOf(
                        UiResourceRef(serverId = "enabled", resourceKey = "resource_enabled"),
                        UiResourceRef(serverId = "disabled", resourceKey = "resource_disabled"),
                        UiResourceRef(serverId = "missing", resourceKey = "resource_missing"),
                    ),
                context = context,
            )

        assertEquals(listOf(true, false, false), items.map { it.isServerEnabled })
    }

    private companion object {
        val serverNames: Map<String, String> =
            mapOf(
                "enabled" to "Enabled Server",
                "disabled" to "Disabled Server",
            )

        val serverEnabledById: Map<String, Boolean> =
            mapOf(
                "enabled" to true,
                "disabled" to false,
            )

        val serverCapsById: Map<String, UiServerCapsSnapshot> =
            mapOf(
                "enabled" to
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
                    ),
                "disabled" to
                    UiServerCapsSnapshot(
                        serverId = "disabled",
                        name = "Disabled Server",
                        tools = listOf(UiToolSummary(name = "tool_disabled", description = "Disabled tool")),
                        prompts = listOf(UiPromptSummary(name = "prompt_disabled", description = "Disabled prompt")),
                        resources =
                            listOf(
                                UiResourceSummary(
                                    key = "resource_disabled",
                                    name = "resource_disabled",
                                    description = "Disabled resource",
                                ),
                            ),
                    ),
            )

        val context =
            CapabilityDisplayContext(
                serverNames = serverNames,
                serverCapsById = serverCapsById,
                serverEnabledById = serverEnabledById,
                searchQuery = "",
            )
    }
}
