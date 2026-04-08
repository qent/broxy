package io.qent.broxy.ui.screens

import io.qent.broxy.ui.adapter.models.UiAgentCodexReasoningEffort
import io.qent.broxy.ui.adapter.models.UiAgentRuntime
import io.qent.broxy.ui.adapter.models.UiLlmProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentSettingsScreenStateTest {
    @Test
    fun `runtime selection maps disabled when features are off`() {
        val selection =
            aiFeaturesRuntimeSelection(
                enabled = false,
                runtime = UiAgentRuntime.CODEX_CLI,
            )

        assertEquals(AiFeaturesRuntimeSelection.DISABLED, selection)
    }

    @Test
    fun `apply disabled selection keeps runtime and disables features`() {
        val state =
            applyAiFeaturesRuntimeSelection(
                currentRuntime = UiAgentRuntime.CODEX_CLI,
                selection = AiFeaturesRuntimeSelection.DISABLED,
            )

        assertFalse(state.enabled)
        assertEquals(UiAgentRuntime.CODEX_CLI, state.runtime)
    }

    @Test
    fun `apply codex selection enables features and sets codex runtime`() {
        val state =
            applyAiFeaturesRuntimeSelection(
                currentRuntime = UiAgentRuntime.LANGCHAIN,
                selection = AiFeaturesRuntimeSelection.CODEX_CLI,
            )

        assertTrue(state.enabled)
        assertEquals(UiAgentRuntime.CODEX_CLI, state.runtime)
    }

    @Test
    fun `build ai features payload always disables codex web search`() {
        val payload =
            buildAiFeaturesSettingsForSave(
                enabled = true,
                runtime = UiAgentRuntime.CODEX_CLI,
                llmProvider = UiLlmProvider.OPENAI,
                llmModel = " gpt-5-nano ",
                llmTemperature = 0.7,
                codexModel = " gpt-5.1-codex-mini ",
                codexReasoningEffort = UiAgentCodexReasoningEffort.HIGH,
            )

        assertTrue(payload.enabled)
        assertEquals(UiAgentRuntime.CODEX_CLI, payload.runtime)
        assertEquals("gpt-5-nano", payload.llm.model)
        assertEquals("gpt-5.1-codex-mini", payload.codex.model)
        assertFalse(payload.codex.webSearch)
    }
}
