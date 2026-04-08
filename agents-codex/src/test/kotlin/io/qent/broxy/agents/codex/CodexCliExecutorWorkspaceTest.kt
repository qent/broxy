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
import io.qent.broxy.agents.LlmProvider
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.test.runTest
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val WORKSPACE_LOGIN_STATUS_OK = "Logged in using ChatGPT"

class CodexCliExecutorWorkspaceTest {
    @Test
    fun execute_missingWorkspaceAtConfiguredDefaultPath_createsDirectoryAndRuns() =
        runTest {
            val tempDir = Files.createTempDirectory("codex-cli-executor-default-workspace")
            val defaultWorkspace = tempDir.resolve("default-workspace")
            val script = tempDir.resolve("fake-codex.sh")
            val portRange = findFreePortRange(size = 20)
            try {
                Files.writeString(
                    script,
                    """
                    #!/bin/sh
                    if [ "${'$'}1" = "--version" ]; then
                      echo "codex-cli 0.111.0"
                      exit 0
                    fi
                    if [ "${'$'}1" = "login" ] && [ "${'$'}2" = "status" ]; then
                      echo "${WORKSPACE_LOGIN_STATUS_OK}"
                      exit 0
                    fi
                    echo '{"type":"item.completed","item":{"type":"agent_message","text":"workspace-ok"}}'
                    """.trimIndent(),
                )
                script.toFile().setExecutable(true)

                val executor =
                    CodexCliExecutor(
                        logger = WorkspaceTestLogger,
                        defaultWorkspacePath = defaultWorkspace,
                    )
                val request =
                    buildWorkspaceRequest(
                        command = script.toAbsolutePath().toString(),
                        workspacePath = defaultWorkspace.toAbsolutePath().toString(),
                        portRange = portRange,
                        access = AgentFileSystemAccess.NONE,
                    )

                assertFalse(Files.exists(defaultWorkspace))
                val result = executor.execute(request)
                assertTrue(result.isSuccess)
                assertTrue(Files.isDirectory(defaultWorkspace))
                assertEquals("workspace-ok", result.getOrThrow().response)
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

    @Test
    fun execute_missingWorkspaceOutsideDefault_failsFastWithWorkspaceError() =
        runTest {
            val tempDir = Files.createTempDirectory("codex-cli-executor-missing-workspace")
            val configuredDefaultWorkspace = tempDir.resolve("default-workspace")
            val missingWorkspace = tempDir.resolve("missing-workspace")
            val portRange = findFreePortRange(size = 20)
            try {
                val executor =
                    CodexCliExecutor(
                        logger = WorkspaceTestLogger,
                        defaultWorkspacePath = configuredDefaultWorkspace,
                    )
                val request =
                    buildWorkspaceRequest(
                        command = "codex",
                        workspacePath = missingWorkspace.toAbsolutePath().toString(),
                        portRange = portRange,
                    )

                val result = executor.execute(request)
                assertTrue(result.isFailure)
                assertTrue(
                    result
                        .exceptionOrNull()
                        ?.message
                        .orEmpty()
                        .contains("Codex workspace directory does not exist"),
                )
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

    private fun buildWorkspaceRequest(
        command: String,
        workspacePath: String,
        portRange: IntRange,
        access: AgentFileSystemAccess = AgentFileSystemAccess.READ_WRITE,
    ): AgentExecutionRequest =
        AgentExecutionRequest(
            agent =
                AgentDefinition(
                    id = "agent-workspace-test",
                    name = "Agent Workspace Test",
                    systemPrompt = "You are helpful",
                ),
            runtime = AgentRuntime.CODEX_CLI,
            llm =
                AgentLlmConfig(
                    provider = LlmProvider.OPENAI,
                    model = "gpt-4o-mini",
                    temperature = 0.2,
                ),
            codex = AgentCodexConfig(model = "gpt-5-codex", webSearch = false),
            prompt = "run prompt",
            fileSystem =
                AgentFileSystemSettings(
                    path = workspacePath,
                    access = access,
                ),
            providerSettings =
                AgentProviderSettings(
                    enableCodexProvider = true,
                    codex =
                        AgentCodexGlobalSettings(
                            command = command,
                            portRangeStart = portRange.first,
                            portRangeEnd = portRange.last,
                        ),
                ),
            mcpConfig = McpServersConfig(),
            apiKey = null,
        )
}

private fun findFreePortRange(size: Int): IntRange {
    require(size > 0)
    for (start in 50_000..65_000 - size) {
        val end = start + size - 1
        if ((start..end).all { port -> isPortFree(port) }) {
            return start..end
        }
    }
    error("Failed to locate free port range")
}

private fun isPortFree(port: Int): Boolean =
    runCatching {
        ServerSocket(port).use { socket ->
            socket.reuseAddress = true
        }
    }.isSuccess

private object WorkspaceTestLogger : Logger {
    override fun debug(message: String) = Unit

    override fun info(message: String) = Unit

    override fun warn(
        message: String,
        throwable: Throwable?,
    ) = Unit

    override fun error(
        message: String,
        throwable: Throwable?,
    ) = Unit
}
