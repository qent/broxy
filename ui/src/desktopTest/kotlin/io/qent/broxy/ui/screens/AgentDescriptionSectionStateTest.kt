package io.qent.broxy.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals

class AgentDescriptionSectionStateTest {
    @Test
    fun resolveState_withoutDescriptionAndAiDisabled_showsPlaceholderAndDisabledGenerate() {
        val state =
            resolveAgentDescriptionSectionState(
                description = "",
                aiFeaturesEnabled = false,
                isInputValid = true,
                isGenerating = false,
            )

        assertEquals(true, state.showPlaceholder)
        assertEquals(false, state.generateEnabled)
        assertEquals(true, state.showEnableHint)
        assertEquals(null, state.text)
    }

    @Test
    fun resolveState_withoutDescriptionAndAiEnabled_enablesGenerate() {
        val state =
            resolveAgentDescriptionSectionState(
                description = "   ",
                aiFeaturesEnabled = true,
                isInputValid = true,
                isGenerating = false,
            )

        assertEquals(true, state.showPlaceholder)
        assertEquals(true, state.generateEnabled)
        assertEquals(false, state.showEnableHint)
        assertEquals(null, state.text)
    }

    @Test
    fun resolveState_withDescription_showsTextAndRespectsGeneratingFlag() {
        val state =
            resolveAgentDescriptionSectionState(
                description = "A concise description for this agent.",
                aiFeaturesEnabled = true,
                isInputValid = true,
                isGenerating = true,
            )

        assertEquals(false, state.showPlaceholder)
        assertEquals(false, state.generateEnabled)
        assertEquals(false, state.showEnableHint)
        assertEquals("A concise description for this agent.", state.text)
    }
}
