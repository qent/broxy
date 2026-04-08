package io.qent.broxy.agents.codex

import io.qent.broxy.agents.AgentCodexConfig
import io.qent.broxy.agents.AgentCodexGlobalSettings
import io.qent.broxy.agents.AgentDefinition
import io.qent.broxy.agents.AgentExecutionRequest
import io.qent.broxy.agents.AgentFileSystemAccess
import io.qent.broxy.agents.AgentFileSystemSettings
import io.qent.broxy.agents.AgentLlmConfig
import io.qent.broxy.agents.AgentProviderSettings
import io.qent.broxy.agents.AgentRuntime
import io.qent.broxy.agents.DEFAULT_CODEX_MODEL
import io.qent.broxy.agents.LlmProvider
import io.qent.broxy.core.models.McpServersConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

private const val LIVE_TEST_ENABLED_PROPERTY = "broxy.codex.live"
private const val LIVE_TEST_ENABLED_ENV = "BROXY_CODEX_LIVE"
private const val LIVE_TEST_COMMAND_PROPERTY = "broxy.codex.command"
private const val LIVE_TEST_COMMAND_ENV = "BROXY_CODEX_COMMAND"
private const val LIVE_TEST_PROMPT_PROPERTY = "broxy.codex.live.prompt"
private const val LIVE_TEST_PROMPT_ENV = "BROXY_CODEX_LIVE_PROMPT"
private const val LIVE_TEST_EXPECT_PROPERTY = "broxy.codex.live.expect"
private const val LIVE_TEST_EXPECT_ENV = "BROXY_CODEX_LIVE_EXPECT"
private const val LIVE_DEFAULT_PROMPT = "Reply with exactly LIVE_OK and nothing else."
private const val LIVE_DEFAULT_EXPECTED = "LIVE_OK"
private const val LIVE_TIMEOUT_MILLIS = 180_000L

class CodexCliExecutorLiveIntegrationTest {
    @Test
    fun execute_withRealCodexCli_usesUserAuthorizationAndReturnsResponse() =
        runBlocking {
            if (!isLiveEnabled()) {
                return@runBlocking
            }
            val codexCommand =
                readOverride(LIVE_TEST_COMMAND_PROPERTY, LIVE_TEST_COMMAND_ENV)
                    .ifBlank { "codex" }
            assertTrue(
                canExecute(codexCommand, "--version"),
                "Codex command '$codexCommand' is unavailable on this machine",
            )
            val prompt =
                readOverride(LIVE_TEST_PROMPT_PROPERTY, LIVE_TEST_PROMPT_ENV)
                    .ifBlank { LIVE_DEFAULT_PROMPT }
            val expectedText =
                readOverride(LIVE_TEST_EXPECT_PROPERTY, LIVE_TEST_EXPECT_ENV)
                    .ifBlank { LIVE_DEFAULT_EXPECTED }
            val workspace = Files.createTempDirectory("codex-live-integration")
            try {
                val request = buildLiveRequest(codexCommand, prompt, workspace.toAbsolutePath().toString())
                val executor = CodexCliExecutor()
                val result = withTimeout(LIVE_TIMEOUT_MILLIS) { executor.execute(request) }

                assertTrue(
                    result.isSuccess,
                    "Live Codex execution failed: ${result.exceptionOrNull()?.message}",
                )
                val response = result.getOrThrow().response
                assertTrue(
                    response.contains(expectedText),
                    "Unexpected live response. Expected to contain '$expectedText', got: $response",
                )
            } finally {
                workspace.toFile().deleteRecursively()
            }
        }
}

private fun isLiveEnabled(): Boolean =
    System.getProperty(LIVE_TEST_ENABLED_PROPERTY).toBoolean() ||
        System.getenv(LIVE_TEST_ENABLED_ENV).toBoolean()

private fun buildLiveRequest(
    codexCommand: String,
    prompt: String,
    workspacePath: String,
): AgentExecutionRequest =
    AgentExecutionRequest(
        agent =
            AgentDefinition(
                id = "codex-live-test",
                name = "Codex Live Test",
                systemPrompt = "You are a strict responder.",
            ),
        runtime = AgentRuntime.CODEX_CLI,
        llm =
            AgentLlmConfig(
                provider = LlmProvider.OPENAI,
                model = "gpt-4o-mini",
                temperature = 0.0,
            ),
        codex =
            AgentCodexConfig(
                model = DEFAULT_CODEX_MODEL,
                webSearch = false,
            ),
        prompt = prompt,
        fileSystem =
            AgentFileSystemSettings(
                path = workspacePath,
                access = AgentFileSystemAccess.NONE,
            ),
        providerSettings =
            AgentProviderSettings(
                enableCodexProvider = true,
                codex = AgentCodexGlobalSettings(command = codexCommand),
            ),
        mcpConfig = McpServersConfig(),
        apiKey = null,
    )

private fun canExecute(
    command: String,
    vararg args: String,
): Boolean =
    runCatching {
        val process =
            ProcessBuilder(listOf(command) + args.toList())
                .redirectErrorStream(true)
                .start()
        val finished = process.waitFor(15, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            false
        } else {
            process.exitValue() == 0
        }
    }.getOrDefault(false)

private fun readOverride(
    propertyName: String,
    envName: String,
): String {
    val propertyValue = System.getProperty(propertyName)?.trim().orEmpty()
    if (propertyValue.isNotBlank()) {
        return propertyValue
    }
    return System.getenv(envName)?.trim().orEmpty()
}
