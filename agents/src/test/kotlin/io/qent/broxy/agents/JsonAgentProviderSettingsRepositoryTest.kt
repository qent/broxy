package io.qent.broxy.agents

import io.qent.broxy.agents.infrastructure.persistence.JsonAgentProviderSettingsRepository
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonAgentProviderSettingsRepositoryTest {
    @Test
    fun saveAndLoad_preservesAiFeaturesSettings() {
        val tempDir = Files.createTempDirectory("broxy-agent-settings")
        try {
            val repository =
                JsonAgentProviderSettingsRepository(
                    baseDir = tempDir,
                    json =
                        Json {
                            ignoreUnknownKeys = true
                            prettyPrint = true
                        },
                )
            val settings =
                AgentProviderSettings(
                    enableCodexProvider = true,
                    openAi = AgentProviderConfig(baseUrl = "https://api.openai.com/v1"),
                    codex = AgentCodexGlobalSettings(command = "codex-custom"),
                    aiFeatures =
                        AgentAiFeaturesSettings(
                            enabled = true,
                            runtime = AgentRuntime.CODEX_CLI,
                            llm =
                                AgentLlmConfig(
                                    provider = LlmProvider.ANTHROPIC,
                                    model = "claude-test",
                                    temperature = 0.7,
                                ),
                            codex =
                                AgentCodexConfig(
                                    model = "gpt-5.1-codex-pro",
                                    reasoningEffort = AgentCodexReasoningEffort.MEDIUM,
                                    webSearch = true,
                                ),
                        ),
                )

            repository.saveSettings(settings)
            val loaded = repository.loadSettings()

            assertEquals(true, loaded.enableCodexProvider)
            assertEquals("https://api.openai.com/v1", loaded.openAi.baseUrl)
            assertEquals("codex-custom", loaded.codex.command)
            assertEquals(true, loaded.aiFeatures.enabled)
            assertEquals(AgentRuntime.CODEX_CLI, loaded.aiFeatures.runtime)
            assertEquals(LlmProvider.ANTHROPIC, loaded.aiFeatures.llm.provider)
            assertEquals("claude-test", loaded.aiFeatures.llm.model)
            assertEquals(0.7, loaded.aiFeatures.llm.temperature)
            assertEquals("gpt-5.1-codex-pro", loaded.aiFeatures.codex.model)
            assertEquals(AgentCodexReasoningEffort.MEDIUM, loaded.aiFeatures.codex.reasoningEffort)
            assertEquals(true, loaded.aiFeatures.codex.webSearch)
        } finally {
            deleteTempDir(tempDir)
        }
    }

    @Test
    fun loadLegacySettingsWithoutAiFeatures_usesDefaults() {
        val tempDir = Files.createTempDirectory("broxy-agent-settings-legacy")
        try {
            val agentsDir = tempDir.resolve("agents")
            Files.createDirectories(agentsDir)
            Files.writeString(
                agentsDir.resolve("agents_settings.json"),
                """
                {
                  "enableCodexProvider": false,
                  "openAi": {
                    "baseUrl": "https://api.openai.com/v1"
                  }
                }
                """.trimIndent(),
            )
            val repository =
                JsonAgentProviderSettingsRepository(
                    baseDir = tempDir,
                    json =
                        Json {
                            ignoreUnknownKeys = true
                            prettyPrint = true
                        },
                )

            val loaded = repository.loadSettings()

            assertEquals(false, loaded.aiFeatures.enabled)
            assertEquals(AgentRuntime.LANGCHAIN, loaded.aiFeatures.runtime)
            assertEquals(defaultAgentLlmConfig(), loaded.aiFeatures.llm)
            assertEquals(AgentCodexConfig(), loaded.aiFeatures.codex)
        } finally {
            deleteTempDir(tempDir)
        }
    }

    private fun deleteTempDir(path: java.nio.file.Path) {
        path.toFile().walkBottomUp().forEach { file ->
            if (file.exists()) {
                file.delete()
            }
        }
        path.deleteIfExists()
    }
}
