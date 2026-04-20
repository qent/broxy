package io.qent.broxy.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlobalHeaderLogicTest {
    @Test
    fun `preset management label keeps AI highlight when agentic mode disabled`() {
        val label = "AI Preset management"

        val styled = presetManagementLabelText(label = label, agenticModeEnabled = false)

        assertEquals(label, styled.text)
        assertEquals(1, styled.spanStyles.size)
        val span = styled.spanStyles.single()
        assertEquals(0, span.start)
        assertEquals(2, span.end)
        assertEquals(Color(0xFF2563EB), span.item.color)
        assertEquals(FontWeight.Bold, span.item.fontWeight)
    }

    @Test
    fun `agentic mode label highlights first letter when AI token is absent`() {
        val label = "Agentic Mode"

        val styled = presetManagementLabelText(label = label, agenticModeEnabled = true)

        assertEquals(label, styled.text)
        assertEquals(1, styled.spanStyles.size)
        val span = styled.spanStyles.single()
        assertEquals(0, span.start)
        assertEquals(1, span.end)
        assertEquals(Color(0xFF2563EB), span.item.color)
        assertEquals(FontWeight.Bold, span.item.fontWeight)
    }

    @Test
    fun `label stays unstyled when not agentic and AI token is absent`() {
        val label = "Preset management"

        val styled = presetManagementLabelText(label = label, agenticModeEnabled = false)

        assertEquals(label, styled.text)
        assertTrue(styled.spanStyles.isEmpty())
    }
}
