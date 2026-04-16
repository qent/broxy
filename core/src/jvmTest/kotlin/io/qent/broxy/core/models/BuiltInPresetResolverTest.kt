package io.qent.broxy.core.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BuiltInPresetResolverTest {
    @Test
    fun resolves_all_built_in_presets_including_management() {
        assertNotNull(BuiltInPresetResolver.resolve(Preset.EMPTY_PRESET_ID))
        assertNotNull(BuiltInPresetResolver.resolve(Preset.ALL_ENABLED_PRESET_ID))
        assertNotNull(BuiltInPresetResolver.resolve(Preset.PRESET_MANAGEMENT_ID))
        assertTrue(BuiltInPresetResolver.isBuiltIn(Preset.PRESET_MANAGEMENT_ID))
    }

    @Test
    fun built_in_list_order_matches_ui_expectation() {
        val ids = BuiltInPresetResolver.listBuiltIns().map { it.id }
        assertEquals(
            listOf(Preset.EMPTY_PRESET_ID, Preset.ALL_ENABLED_PRESET_ID, Preset.PRESET_MANAGEMENT_ID),
            ids,
        )
    }
}
