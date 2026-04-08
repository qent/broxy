package io.qent.broxy.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals

class PresetsScreenLogicTest {
    @Test
    fun `presetConnectionUrl builds preset-specific streamable endpoint`() {
        assertEquals(
            "http://localhost:3335/mcp/dev",
            presetConnectionUrl(inboundHttpPort = 3335, presetId = "dev"),
        )
    }
}
