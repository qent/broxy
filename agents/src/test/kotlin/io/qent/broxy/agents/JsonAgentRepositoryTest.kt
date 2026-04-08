package io.qent.broxy.agents

import io.qent.broxy.agents.infrastructure.persistence.JsonAgentRepository
import io.qent.broxy.core.models.ToolReference
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonAgentRepositoryTest {
    @Test
    fun saveLoadListDeleteAgent_withMarkdownAndSidecar() {
        val tempDir = Files.createTempDirectory("broxy-agents-md-repo")
        try {
            val repository = createRepository(tempDir)
            val first = testAgent(id = "a1", orderIndex = 0)
            val second = testAgent(id = "a2", orderIndex = 1)

            repository.saveAgent(second)
            repository.saveAgent(first)

            val listed = repository.listAgents()
            assertEquals(listOf("a1", "a2"), listed.map { it.id })

            val loaded = repository.loadAgent("a1")
            assertEquals("Agent a1", loaded.name)
            assertEquals("System prompt a1", loaded.systemPrompt)
            assertEquals("Description for a1", loaded.description)
            assertEquals("0 * * * *", loaded.schedule?.cron)
            assertEquals(AgentFileSystemAccess.READ_WRITE, loaded.schedule?.fileSystem?.access)
            assertEquals("repeat this", loaded.manualLaunchDefaults?.prompt)
            assertEquals("gpt-4o-mini", loaded.manualLaunchDefaults?.llm?.model)
            assertEquals(AgentFileSystemAccess.READ_ONLY, loaded.manualLaunchDefaults?.fileSystem?.access)

            assertTrue(tempDir.resolve("agents").resolve("a1.md").exists())
            assertTrue(
                tempDir
                    .resolve("agents")
                    .resolve("metadata")
                    .resolve("agent_a1.json")
                    .exists(),
            )

            repository.deleteAgent("a1")
            assertFalse(tempDir.resolve("agents").resolve("a1.md").exists())
            assertFalse(
                tempDir
                    .resolve("agents")
                    .resolve("metadata")
                    .resolve("agent_a1.json")
                    .exists(),
            )
        } finally {
            deleteTempDir(tempDir)
        }
    }

    @Test
    fun listAgents_ignoresLegacyJsonFiles_withoutFallback() {
        val tempDir = Files.createTempDirectory("broxy-agents-md-legacy")
        try {
            val agentsDir = tempDir.resolve("agents")
            Files.createDirectories(agentsDir)
            Files.writeString(
                agentsDir.resolve("agent_legacy.json"),
                """{"id":"legacy","name":"Legacy","systemPrompt":"legacy"}""",
            )
            Files.writeString(
                agentsDir.resolve("modern.md"),
                """
                ---
                name: Modern
                description: Modern agent description.
                ---
                Modern prompt
                """.trimIndent(),
            )

            val repository = createRepository(tempDir)
            val listed = repository.listAgents()
            assertEquals(listOf("modern"), listed.map { it.id })
        } finally {
            deleteTempDir(tempDir)
        }
    }

    @Test
    fun saveAgent_usesConfiguredAgentsDirectoryPath_forMarkdownOnly() {
        val tempDir = Files.createTempDirectory("broxy-agents-md-custom-dir")
        try {
            val externalDefinitions = tempDir.resolve("external-claude-agents")
            Files.createDirectories(tempDir.resolve("agents"))
            Files.writeString(
                tempDir.resolve("agents").resolve("agents_settings.json"),
                """
                {
                  "agentsDirectoryPath": "${externalDefinitions.toAbsolutePath()}"
                }
                """.trimIndent(),
            )

            val repository = createRepository(tempDir)
            repository.saveAgent(testAgent(id = "custom-dir", orderIndex = 0))

            assertTrue(externalDefinitions.resolve("custom-dir.md").exists())
            assertTrue(
                tempDir
                    .resolve("agents")
                    .resolve("metadata")
                    .resolve("agent_custom-dir.json")
                    .exists(),
            )

            val loaded = repository.loadAgent("custom-dir")
            assertEquals("custom-dir", loaded.id)
            assertEquals("Agent custom-dir", loaded.name)
        } finally {
            deleteTempDir(tempDir)
        }
    }

    @Test
    fun saveAgent_preservesUnknownFrontmatterFields() {
        val tempDir = Files.createTempDirectory("broxy-agents-md-frontmatter")
        try {
            val agentsDir = tempDir.resolve("agents")
            Files.createDirectories(agentsDir)
            Files.writeString(
                agentsDir.resolve("unknown.md"),
                """
                ---
                name: Unknown Agent
                description: Unknown field test.
                customField: true
                hooks:
                  PostToolUse:
                    - command: echo done
                ---
                Original prompt
                """.trimIndent(),
            )

            val repository = createRepository(tempDir)
            val loaded = repository.loadAgent("unknown")
            repository.saveAgent(loaded.copy(systemPrompt = "Updated prompt"))

            val savedText = agentsDir.resolve("unknown.md").readText()
            assertTrue(savedText.contains("customField"))
            assertTrue(savedText.contains("hooks"))
            assertTrue(savedText.contains("Updated prompt"))
        } finally {
            deleteTempDir(tempDir)
        }
    }

    private fun createRepository(baseDir: Path): JsonAgentRepository =
        JsonAgentRepository(
            baseDir = baseDir,
            json =
                Json {
                    prettyPrint = true
                    ignoreUnknownKeys = true
                },
        )

    private fun deleteTempDir(path: Path) {
        path.toFile().walkBottomUp().forEach { file ->
            if (file.exists()) {
                file.delete()
            }
        }
    }

    private fun openAiLlm(): AgentLlmConfig =
        AgentLlmConfig(
            provider = LlmProvider.OPENAI,
            model = "gpt-4o-mini",
            temperature = 0.2,
        )

    private fun testAgent(
        id: String,
        orderIndex: Int,
    ): AgentDefinition =
        AgentDefinition(
            id = id,
            name = "Agent $id",
            systemPrompt = "System prompt $id",
            description = "Description for $id",
            tools = listOf(ToolReference(serverId = "s1", toolName = "search", enabled = true)),
            orderIndex = orderIndex,
            schedule =
                AgentSchedule(
                    cron = "0 * * * *",
                    prompt = "hourly",
                    timezoneId = "UTC",
                    runtime = AgentRuntime.LANGCHAIN,
                    llm = openAiLlm(),
                    fileSystem =
                        AgentFileSystemSettings(
                            path = DEFAULT_AGENT_WORKSPACE_PATH,
                            access = AgentFileSystemAccess.READ_WRITE,
                        ),
                ),
            manualLaunchDefaults =
                AgentManualLaunchDefaults(
                    prompt = "repeat this",
                    runtime = AgentRuntime.LANGCHAIN,
                    llm = openAiLlm(),
                    fileSystem =
                        AgentFileSystemSettings(
                            path = DEFAULT_AGENT_WORKSPACE_PATH,
                            access = AgentFileSystemAccess.READ_ONLY,
                        ),
                ),
        )
}
