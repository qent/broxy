package io.qent.broxy.ui.presets

import io.qent.broxy.ui.adapter.models.UiPreset
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PresetCapabilityStatusTest {
    @Test
    fun `marks deleted server references as unavailable`() {
        val preset =
            UiPreset(
                id = "p1",
                name = "Preset",
                toolsCount = 1,
                toolsServerIds = setOf("deleted-server"),
            )

        val status = resolvePresetCapabilityStatus(preset, enabledServerIds = setOf("alive-server"))

        assertTrue(status.hasUnavailableTools)
        assertTrue(status.hasNoAvailableCapabilities)
        assertTrue(status.hasCapabilityWarning)
    }

    @Test
    fun `marks partially available capabilities when at least one referenced server remains enabled`() {
        val preset =
            UiPreset(
                id = "p1",
                name = "Preset",
                toolsCount = 2,
                toolsServerIds = setOf("alive-server", "deleted-server"),
            )

        val status = resolvePresetCapabilityStatus(preset, enabledServerIds = setOf("alive-server"))

        assertTrue(status.hasUnavailableTools)
        assertFalse(status.hasNoAvailableCapabilities)
        assertTrue(status.hasCapabilityWarning)
    }

    @Test
    fun `keeps preset healthy when all referenced servers are enabled`() {
        val preset =
            UiPreset(
                id = "p1",
                name = "Preset",
                toolsCount = 1,
                promptsCount = 1,
                resourcesCount = 1,
                toolsServerIds = setOf("server-a"),
                promptsServerIds = setOf("server-b"),
                resourcesServerIds = setOf("server-c"),
            )

        val status =
            resolvePresetCapabilityStatus(
                preset = preset,
                enabledServerIds = setOf("server-a", "server-b", "server-c"),
            )

        assertFalse(status.hasUnavailableTools)
        assertFalse(status.hasUnavailablePrompts)
        assertFalse(status.hasUnavailableResources)
        assertFalse(status.hasNoAvailableCapabilities)
        assertFalse(status.hasCapabilityWarning)
    }
}
