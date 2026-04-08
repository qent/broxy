package io.qent.broxy.ui.screens

import io.qent.broxy.ui.adapter.models.UiAgentAiFeaturesSettings
import io.qent.broxy.ui.adapter.models.UiAgentCodexConfig
import io.qent.broxy.ui.adapter.models.UiAgentCodexReasoningEffort
import io.qent.broxy.ui.adapter.models.UiAgentGenerationStage
import io.qent.broxy.ui.adapter.models.UiAgentLlmConfig
import io.qent.broxy.ui.adapter.models.UiAgentProviderSettings
import io.qent.broxy.ui.adapter.models.UiAgentRuntime
import io.qent.broxy.ui.adapter.models.UiLlmProvider
import io.qent.broxy.ui.adapter.store.AGENT_GENERATION_ERROR_ALREADY_RUNNING
import io.qent.broxy.ui.adapter.store.AGENT_GENERATION_ERROR_BLANK_REQUEST
import io.qent.broxy.ui.adapter.store.AgentGenerationState
import io.qent.broxy.ui.strings.EnglishStrings
import io.qent.broxy.ui.viewmodels.AgentEditorState
import io.qent.broxy.ui.viewmodels.AppState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentGenerateScreenStateTest {
    @Test
    fun resolveState_idleDisablesGenerateUntilRequestFilled() {
        val state =
            resolveAgentGenerateScreenState(
                state = AgentGenerationState(request = "   ", isRunning = false),
                strings = EnglishStrings,
            )

        assertTrue(state.requestEnabled)
        assertFalse(state.generateEnabled)
        assertEquals(EnglishStrings.generateAction, state.generateButtonLabel)
        assertEquals(null, state.errorMessage)
    }

    @Test
    fun resolveState_runningLocksControlsAndShowsStageInButton() {
        val state =
            resolveAgentGenerateScreenState(
                state =
                    AgentGenerationState(
                        request = "Build an agent",
                        isRunning = true,
                        stage = UiAgentGenerationStage.SELECTING_CAPABILITIES,
                    ),
                strings = EnglishStrings,
            )

        assertFalse(state.requestEnabled)
        assertFalse(state.generateEnabled)
        assertTrue(state.generateButtonLabel.contains(EnglishStrings.agentGenerateStageSelectingCapabilities))
    }

    @Test
    fun resolveState_mapsKnownErrorCodesToLocalizedMessages() {
        val blankRequest =
            resolveAgentGenerateScreenState(
                state = AgentGenerationState(errorMessage = AGENT_GENERATION_ERROR_BLANK_REQUEST),
                strings = EnglishStrings,
            )
        val alreadyRunning =
            resolveAgentGenerateScreenState(
                state = AgentGenerationState(errorMessage = AGENT_GENERATION_ERROR_ALREADY_RUNNING),
                strings = EnglishStrings,
            )

        assertEquals(EnglishStrings.agentGenerateErrorBlankRequest, blankRequest.errorMessage)
        assertEquals(EnglishStrings.agentGenerateErrorAlreadyRunning, alreadyRunning.errorMessage)
    }

    @Test
    fun resolveState_completedKeepsGenerateAvailableWhenRequestPresent() {
        val state =
            resolveAgentGenerateScreenState(
                state =
                    AgentGenerationState(
                        request = "Generate something",
                        isRunning = false,
                        generatedAgentId = "agent-1",
                    ),
                strings = EnglishStrings,
            )

        assertTrue(state.requestEnabled)
        assertTrue(state.generateEnabled)
    }

    @Test
    fun resolveState_invalidGenerationConfigDisablesGenerateWhenRequestPresent() {
        val state =
            resolveAgentGenerateScreenState(
                state = AgentGenerationState(request = "Generate something", isRunning = false),
                strings = EnglishStrings,
                isGenerationConfigValid = false,
            )

        assertTrue(state.requestEnabled)
        assertFalse(state.generateEnabled)
    }

    @Test
    fun generationRuntimeOptions_excludesDisabledRuntime() {
        val options = generationRuntimeOptions()

        assertFalse(options.contains(AiFeaturesRuntimeSelection.DISABLED))
        assertTrue(options.contains(AiFeaturesRuntimeSelection.LANGCHAIN))
        assertTrue(options.contains(AiFeaturesRuntimeSelection.CODEX_CLI))
    }

    @Test
    fun resolveInitialGenerateAiConfig_seedsValuesFromProviderSettingsAiFeatures() {
        val settings =
            UiAgentProviderSettings(
                aiFeatures =
                    UiAgentAiFeaturesSettings(
                        enabled = false,
                        runtime = UiAgentRuntime.CODEX_CLI,
                        llm =
                            UiAgentLlmConfig(
                                provider = UiLlmProvider.ANTHROPIC,
                                model = "claude-3-7-sonnet",
                                temperature = 0.6,
                            ),
                        codex =
                            UiAgentCodexConfig(
                                model = "gpt-5.1-codex-mini",
                                reasoningEffort = UiAgentCodexReasoningEffort.MEDIUM,
                                webSearch = true,
                            ),
                    ),
            )

        val seed = resolveInitialGenerateAiConfig(settings)

        assertEquals(AiFeaturesRuntimeSelection.CODEX_CLI, seed.runtimeSelection)
        assertEquals(UiLlmProvider.ANTHROPIC, seed.llmProvider)
        assertEquals("claude-3-7-sonnet", seed.llmModel)
        assertEquals("0.6", seed.llmTemperatureInput)
        assertEquals("gpt-5.1-codex-mini", seed.codexModel)
        assertEquals(UiAgentCodexReasoningEffort.MEDIUM, seed.codexReasoningEffort)
    }

    @Test
    fun applyGeneratedAgentCompletionNavigation_opensEditAndClosesGenerateMode() {
        val appState = AppState()
        appState.agentGenerateMode.value = true
        appState.agentLaunchId.value = "launch-agent"
        appState.agentDetailsId.value = "details-agent"

        applyGeneratedAgentCompletionNavigation(appState, "generated-agent")

        assertFalse(appState.agentGenerateMode.value)
        assertEquals(null, appState.agentLaunchId.value)
        assertEquals(null, appState.agentDetailsId.value)
        assertEquals(AgentEditorState.Edit("generated-agent"), appState.agentEditor.value)
    }

    @Test
    fun applyGenerateSkipNavigation_opensCreateAndClosesGenerateMode() {
        val appState = AppState()
        appState.agentGenerateMode.value = true
        appState.agentLaunchId.value = "launch-agent"
        appState.agentDetailsId.value = "details-agent"
        appState.agentEditor.value = AgentEditorState.Edit("existing-agent")

        applyGenerateSkipNavigation(appState)

        assertFalse(appState.agentGenerateMode.value)
        assertEquals(null, appState.agentLaunchId.value)
        assertEquals(null, appState.agentDetailsId.value)
        assertEquals(AgentEditorState.Create, appState.agentEditor.value)
    }
}
