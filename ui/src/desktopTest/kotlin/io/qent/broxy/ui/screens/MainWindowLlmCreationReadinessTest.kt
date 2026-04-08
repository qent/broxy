package io.qent.broxy.ui.screens

import io.qent.broxy.ui.adapter.models.UiAgentAiFeaturesSettings
import io.qent.broxy.ui.adapter.models.UiAgentCodexGlobalSettings
import io.qent.broxy.ui.adapter.models.UiAgentLlmConfig
import io.qent.broxy.ui.adapter.models.UiAgentProviderConfig
import io.qent.broxy.ui.adapter.models.UiAgentProviderSettings
import io.qent.broxy.ui.adapter.models.UiAgentRuntime
import io.qent.broxy.ui.adapter.models.UiLlmProvider
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainWindowLlmCreationReadinessTest {
    @Test
    fun isLlmAgentCreationReady_returnsFalseWhenAiFeaturesDisabled() {
        val settings =
            baseProviderSettings().copy(
                aiFeatures =
                    UiAgentAiFeaturesSettings(
                        enabled = false,
                        runtime = UiAgentRuntime.LANGCHAIN,
                        llm = UiAgentLlmConfig(provider = UiLlmProvider.OPENAI, model = "gpt-5"),
                    ),
            )

        assertFalse(isLlmAgentCreationReady(settings))
    }

    @Test
    fun isLlmAgentCreationReady_langchainOpenAiWithoutApiKeyReturnsFalse() {
        val settings =
            baseProviderSettings().copy(
                aiFeatures =
                    UiAgentAiFeaturesSettings(
                        enabled = true,
                        runtime = UiAgentRuntime.LANGCHAIN,
                        llm = UiAgentLlmConfig(provider = UiLlmProvider.OPENAI, model = "gpt-5"),
                    ),
                openAi = UiAgentProviderConfig(hasSavedApiKey = false),
            )

        assertFalse(isLlmAgentCreationReady(settings))
    }

    @Test
    fun isLlmAgentCreationReady_langchainOpenAiWithApiKeyReturnsTrue() {
        val settings =
            baseProviderSettings().copy(
                aiFeatures =
                    UiAgentAiFeaturesSettings(
                        enabled = true,
                        runtime = UiAgentRuntime.LANGCHAIN,
                        llm = UiAgentLlmConfig(provider = UiLlmProvider.OPENAI, model = "gpt-5"),
                    ),
                openAi = UiAgentProviderConfig(hasSavedApiKey = true),
            )

        assertTrue(isLlmAgentCreationReady(settings))
    }

    @Test
    fun isLlmAgentCreationReady_langchainLmStudioReturnsTrueWithoutApiKey() {
        val settings =
            baseProviderSettings().copy(
                aiFeatures =
                    UiAgentAiFeaturesSettings(
                        enabled = true,
                        runtime = UiAgentRuntime.LANGCHAIN,
                        llm = UiAgentLlmConfig(provider = UiLlmProvider.LM_STUDIO, model = "local-model"),
                    ),
            )

        assertTrue(isLlmAgentCreationReady(settings))
    }

    @Test
    fun isLlmAgentCreationReady_codexRuntimeWithoutCodexProviderReturnsFalse() {
        val settings =
            baseProviderSettings().copy(
                enableCodexProvider = false,
                aiFeatures =
                    UiAgentAiFeaturesSettings(
                        enabled = true,
                        runtime = UiAgentRuntime.CODEX_CLI,
                    ),
                codex = UiAgentCodexGlobalSettings(command = "codex"),
            )

        assertFalse(isLlmAgentCreationReady(settings))
    }

    @Test
    fun isLlmAgentCreationReady_codexRuntimeWithProviderAndCommandReturnsTrue() {
        val settings =
            baseProviderSettings().copy(
                enableCodexProvider = true,
                aiFeatures =
                    UiAgentAiFeaturesSettings(
                        enabled = true,
                        runtime = UiAgentRuntime.CODEX_CLI,
                    ),
                codex = UiAgentCodexGlobalSettings(command = "codex"),
            )

        assertTrue(isLlmAgentCreationReady(settings))
    }

    @Test
    fun isLlmAgentCreationReady_codexRuntimeWithBlankCommandReturnsFalse() {
        val settings =
            baseProviderSettings().copy(
                enableCodexProvider = true,
                aiFeatures =
                    UiAgentAiFeaturesSettings(
                        enabled = true,
                        runtime = UiAgentRuntime.CODEX_CLI,
                    ),
                codex = UiAgentCodexGlobalSettings(command = "   "),
            )

        assertFalse(isLlmAgentCreationReady(settings))
    }

    @Test
    fun isLlmAgentCreationReady_returnsFalseForMissingSettings() {
        assertFalse(isLlmAgentCreationReady(null))
    }
}

private fun baseProviderSettings(): UiAgentProviderSettings =
    UiAgentProviderSettings(
        openAi = UiAgentProviderConfig(hasSavedApiKey = false),
        anthropic = UiAgentProviderConfig(hasSavedApiKey = false),
        aiFeatures = UiAgentAiFeaturesSettings(),
        codex = UiAgentCodexGlobalSettings(command = ""),
    )
