package io.qent.broxy.ui.screens

import io.qent.broxy.ui.adapter.models.UiPresetCore
import io.qent.broxy.ui.strings.EnglishStrings
import kotlin.test.Test
import kotlin.test.assertEquals

class PresetsScreenLogicTest {
    @Test
    fun `shouldShowPresetsSearchField returns false for empty preset list`() {
        assertEquals(false, shouldShowPresetsSearchField(presetCount = 0))
    }

    @Test
    fun `shouldShowPresetsSearchField returns true for non-empty preset list`() {
        assertEquals(true, shouldShowPresetsSearchField(presetCount = 1))
    }

    @Test
    fun `presetConnectionUrl builds preset-specific streamable endpoint`() {
        assertEquals(
            "http://localhost:3335/mcp/dev",
            presetConnectionUrl(inboundHttpPort = 3335, presetId = "dev"),
        )
    }

    @Test
    fun `resolvePresetsEmptyState returns management message for preset management mode`() {
        val result =
            resolvePresetsEmptyState(
                strings = EnglishStrings,
                activeProxyPresetId = UiPresetCore.PRESET_MANAGEMENT_ID,
            )

        assertEquals("No presets yet", result.title)
        assertEquals(
            "Active mode: AI Preset management. Create presets with a connected AI agent.",
            result.subtitle,
        )
    }

    @Test
    fun `resolvePresetsEmptyState returns default message for non-management mode`() {
        val result =
            resolvePresetsEmptyState(
                strings = EnglishStrings,
                activeProxyPresetId = "dev",
            )

        assertEquals("No presets yet", result.title)
        assertEquals("Use the + button to add your first preset", result.subtitle)
    }
}
