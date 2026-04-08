package io.qent.broxy.cli.commands

import com.github.ajalt.clikt.core.CliktError
import io.qent.broxy.agents.AgentCodexReasoningEffort
import io.qent.broxy.agents.AgentFileSystemAccess
import io.qent.broxy.agents.AgentRuntime
import io.qent.broxy.agents.LlmProvider
import java.nio.file.Files
import kotlin.io.path.pathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentRunCommandParsingTest {
    @Test
    fun `required mcp and agent files are parsed`() {
        withTempConfigFiles { mcpFile, agentFile ->
            val options = parseOptions("--mcp-config", mcpFile.pathString, "--agent-config", agentFile.pathString)

            assertEquals(mcpFile.toFile(), options.mcpConfigFile)
            assertEquals(agentFile.toFile(), options.agentConfigFile)
            assertEquals(AgentOutputFormat.TEXT, options.output)
            assertEquals(DEFAULT_AGENT_RUN_TIMEOUT_SECONDS, options.timeoutSeconds)
            assertEquals(LogLevelOption.INFO, options.logLevel)
        }
    }

    @Test
    fun `all optional overrides are parsed`() {
        withTempConfigFiles { mcpFile, agentFile ->
            val settings = Files.createTempFile("broxy-agent-settings", ".json")
            val secrets = Files.createTempFile("broxy-agent-secrets", ".json")
            val stateDir = Files.createTempDirectory("broxy-agent-state")
            try {
                val options = parseAllOverrides(mcpFile, agentFile, settings, secrets, stateDir)
                assertAllOverrides(options, settings, secrets, stateDir)
            } finally {
                settings.toFile().delete()
                secrets.toFile().delete()
                stateDir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `invalid runtime value fails parsing`() {
        withTempConfigFiles { mcpFile, agentFile ->
            assertFailsWith<CliktError> {
                parseOptions(
                    "--mcp-config",
                    mcpFile.pathString,
                    "--agent-config",
                    agentFile.pathString,
                    "--runtime",
                    "unknown",
                )
            }
        }
    }

    @Test
    fun `timeout must be at least one second`() {
        withTempConfigFiles { mcpFile, agentFile ->
            assertFailsWith<CliktError> {
                parseOptions(
                    "--mcp-config",
                    mcpFile.pathString,
                    "--agent-config",
                    agentFile.pathString,
                    "--timeout-seconds",
                    "0",
                )
            }
        }
    }

    private fun parseOptions(vararg args: String): AgentRunCliOptions {
        val runner = CapturingRunner()
        val command = AgentRunCommand(runner)
        command.parse(args.toList())
        return requireNotNull(runner.captured)
    }

    private fun parseAllOverrides(
        mcpFile: java.nio.file.Path,
        agentFile: java.nio.file.Path,
        settings: java.nio.file.Path,
        secrets: java.nio.file.Path,
        stateDir: java.nio.file.Path,
    ): AgentRunCliOptions =
        parseOptions(
            "--mcp-config",
            mcpFile.pathString,
            "--agent-config",
            agentFile.pathString,
            "--agent-settings",
            settings.pathString,
            "--agents-secrets",
            secrets.pathString,
            "--state-dir",
            stateDir.pathString,
            "--prompt",
            "hello",
            "--runtime",
            "codex",
            "--provider",
            "anthropic",
            "--model",
            "claude",
            "--temperature",
            "0.4",
            "--workspace",
            "/tmp/work",
            "--fs-access",
            "read-write",
            "--codex-model",
            "gpt-5.1-codex-mini",
            "--codex-reasoning",
            "high",
            "--codex-web-search",
            "true",
            "--output",
            "json",
            "--timeout-seconds",
            "90",
            "--log-level",
            "warn",
        )

    private fun assertAllOverrides(
        options: AgentRunCliOptions,
        settings: java.nio.file.Path,
        secrets: java.nio.file.Path,
        stateDir: java.nio.file.Path,
    ) {
        assertEquals(settings.toFile(), options.agentSettingsFile)
        assertEquals(secrets.toFile(), options.agentsSecretsFile)
        assertEquals(stateDir.toFile(), options.stateDir)
        assertEquals("hello", options.prompt)
        assertEquals(AgentRuntime.CODEX_CLI, options.runtime)
        assertEquals(LlmProvider.ANTHROPIC, options.provider)
        assertEquals("claude", options.model)
        assertEquals(0.4, options.temperature)
        assertEquals("/tmp/work", options.workspace)
        assertEquals(AgentFileSystemAccess.READ_WRITE, options.fileSystemAccess)
        assertEquals("gpt-5.1-codex-mini", options.codexModel)
        assertEquals(AgentCodexReasoningEffort.HIGH, options.codexReasoningEffort)
        assertEquals(true, options.codexWebSearch)
        assertEquals(AgentOutputFormat.JSON, options.output)
        assertEquals(90L, options.timeoutSeconds)
        assertEquals(LogLevelOption.WARN, options.logLevel)
    }

    private fun withTempConfigFiles(block: (java.nio.file.Path, java.nio.file.Path) -> Unit) {
        val mcpFile = Files.createTempFile("broxy-agent-mcp", ".json")
        val agentFile = Files.createTempFile("broxy-agent-config", ".md")
        try {
            block(mcpFile, agentFile)
        } finally {
            mcpFile.toFile().delete()
            agentFile.toFile().delete()
        }
    }

    private class CapturingRunner : AgentRunCommandRunner() {
        var captured: AgentRunCliOptions? = null

        override fun run(options: AgentRunCliOptions): AgentRunCommandResult {
            captured = options
            return AgentRunCommandResult(exitCode = 0)
        }
    }
}
