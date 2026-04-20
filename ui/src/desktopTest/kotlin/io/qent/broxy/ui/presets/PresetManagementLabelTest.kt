package io.qent.broxy.ui.presets

import io.qent.broxy.ui.strings.EnglishStrings
import kotlin.test.Test
import kotlin.test.assertEquals

class PresetManagementLabelTest {
    @Test
    fun `returns AI preset management when agentic mode disabled`() {
        val label =
            resolvePresetManagementLabel(
                strings = EnglishStrings,
                agenticModeEnabled = false,
            )

        assertEquals("AI Preset management", label)
    }

    @Test
    fun `returns agentic mode when agentic mode enabled`() {
        val label =
            resolvePresetManagementLabel(
                strings = EnglishStrings,
                agenticModeEnabled = true,
            )

        assertEquals("Agentic Mode", label)
    }
}
