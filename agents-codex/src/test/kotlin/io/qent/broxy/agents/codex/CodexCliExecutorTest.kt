@file:Suppress("LongMethod")

package io.qent.broxy.agents.codex

import io.qent.broxy.agents.AgentCodexConfig
import io.qent.broxy.agents.AgentCodexGlobalSettings
import io.qent.broxy.agents.AgentCodexReasoningEffort
import io.qent.broxy.agents.AgentDefinition
import io.qent.broxy.agents.AgentExecutionOperation
import io.qent.broxy.agents.AgentExecutionRequest
import io.qent.broxy.agents.AgentFileSystemAccess
import io.qent.broxy.agents.AgentFileSystemSettings
import io.qent.broxy.agents.AgentLlmConfig
import io.qent.broxy.agents.AgentProviderConfig
import io.qent.broxy.agents.AgentProviderSettings
import io.qent.broxy.agents.AgentRunActionEntry
import io.qent.broxy.agents.AgentRunActionType
import io.qent.broxy.agents.AgentRuntime
import io.qent.broxy.agents.DEFAULT_OPENAI_BASE_URL
import io.qent.broxy.agents.LlmProvider
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val LOGIN_STATUS_OK = "Logged in using ChatGPT"
private const val REFRESH_TOKEN_REUSED_MESSAGE =
    "Your access token could not be refreshed because your refresh token was already used. " +
        "Please log out and sign in again."

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass")
class CodexCliExecutorTest {
    @Test
    fun execute_toolResult_transitionsBackToThinkingBeforeAssistantResponse() =
        runTest {
            val tempDir = Files.createTempDirectory("codex-cli-executor-tool-thinking-transition")
            val workspace = Files.createDirectories(tempDir.resolve("workspace"))
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
                      echo "${LOGIN_STATUS_OK}"
                      exit 0
                    fi
                    cat > /dev/null
                    echo '{"type":"item.completed","item":{"type":"mcp_tool_call","id":"call-1","server":"google-workplace","tool":"get_gmail_messages_content_batch","result":{"ok":true}}}'
                    echo '{"type":"item.completed","item":{"type":"agent_message","text":"done"}}'
                    """.trimIndent(),
                )
                script.toFile().setExecutable(true)

                val operations = mutableListOf<AgentExecutionOperation>()
                val executor = CodexCliExecutor(logger = NoopTestLogger)
                val request =
                    buildRequest(
                        command = script.toAbsolutePath().toString(),
                        workspacePath = workspace.toAbsolutePath().toString(),
                        portRange = portRange,
                    ).copy(onOperation = { operation -> operations += operation })

                val result = executor.execute(request)
                assertTrue(result.isSuccess)
                assertEquals("done", result.getOrThrow().response)

                val progressOperations =
                    operations.filter {
                        it is AgentExecutionOperation.ToolExecution ||
                            it is AgentExecutionOperation.LlmThinking ||
                            it is AgentExecutionOperation.LlmResponseGeneration
                    }
                assertEquals(3, progressOperations.size)
                val tool = progressOperations[0] as AgentExecutionOperation.ToolExecution
                val thinking = progressOperations[1] as AgentExecutionOperation.LlmThinking
                val response = progressOperations[2] as AgentExecutionOperation.LlmResponseGeneration
                assertEquals("google-workplace", tool.serverId)
                assertEquals("get_gmail_messages_content_batch", tool.toolName)
                assertEquals(tool.step, thinking.step)
                assertEquals(tool.step, response.step)
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

    @Test
    fun execute_mcpToolCallWithObjectError_doesNotCrashAndCapturesToolResultError() =
        runTest {
            val tempDir = Files.createTempDirectory("codex-cli-executor-tool-error-object")
            val workspace = Files.createDirectories(tempDir.resolve("workspace"))
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
                      echo "${LOGIN_STATUS_OK}"
                      exit 0
                    fi
                    cat > /dev/null
                    echo '{"type":"item.completed","item":{"type":"mcp_tool_call","id":"call-1","server":"mcp","tool":"list_mcp_resources","status":"failed","arguments":{"server":"broxy"},"error":{"message":"Tool `list_mcp_resources` is not available","code":"tool_not_found"}}}'
                    echo '{"type":"item.completed","item":{"type":"agent_message","text":"fallback answer"}}'
                    """.trimIndent(),
                )
                script.toFile().setExecutable(true)

                val operations = mutableListOf<AgentExecutionOperation>()
                val traceActions = mutableListOf<AgentRunActionEntry>()
                val executor = CodexCliExecutor(logger = NoopTestLogger)
                val request =
                    buildRequest(
                        command = script.toAbsolutePath().toString(),
                        workspacePath = workspace.toAbsolutePath().toString(),
                        portRange = portRange,
                    ).copy(
                        onOperation = { operation -> operations += operation },
                        onTraceAction = { action -> traceActions += action },
                    )

                val result = executor.execute(request)
                assertTrue(result.isSuccess)
                assertEquals("fallback answer", result.getOrThrow().response)

                assertTrue(
                    operations.any { operation ->
                        operation is AgentExecutionOperation.ToolExecution &&
                            operation.serverId == "mcp" &&
                            operation.toolName == "list_mcp_resources"
                    },
                )

                val toolResult =
                    traceActions.lastOrNull { action ->
                        action.type == AgentRunActionType.TOOL_RESULT &&
                            action.serverId == "mcp" &&
                            action.toolName == "list_mcp_resources"
                    }
                assertTrue(toolResult != null, "TOOL_RESULT trace action should be emitted")
                val existingToolResult = checkNotNull(toolResult)
                assertEquals("Tool `list_mcp_resources` is not available", existingToolResult.errorMessage)
                assertTrue(existingToolResult.responsePayload?.contains("\"tool_not_found\"") == true)
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

    @Test
    fun execute_malformedJsonlShapes_areIgnoredWithoutCrash() =
        runTest {
            val tempDir = Files.createTempDirectory("codex-cli-executor-malformed-jsonl")
            val workspace = Files.createDirectories(tempDir.resolve("workspace"))
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
                      echo "${LOGIN_STATUS_OK}"
                      exit 0
                    fi
                    cat > /dev/null
                    echo '{"type":{"unexpected":"object"},"item":{"type":"agent_message","text":"ignored"}}'
                    echo '{"type":"item.completed","item":{"type":{"unexpected":"object"},"server":"mcp","tool":"broken"}}'
                    echo '{"type":"item.completed","item":"not-an-object"}'
                    echo '{"type":"item.completed","item":{"type":"agent_message","text":"safe-final-response"}}'
                    """.trimIndent(),
                )
                script.toFile().setExecutable(true)

                val operations = mutableListOf<AgentExecutionOperation>()
                val executor = CodexCliExecutor(logger = NoopTestLogger)
                val request =
                    buildRequest(
                        command = script.toAbsolutePath().toString(),
                        workspacePath = workspace.toAbsolutePath().toString(),
                        portRange = portRange,
                    ).copy(onOperation = { operation -> operations += operation })

                val result = executor.execute(request)
                assertTrue(result.isSuccess)
                assertEquals("safe-final-response", result.getOrThrow().response)
                assertFalse(operations.any { it is AgentExecutionOperation.ToolExecution })
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

    @Test
    fun execute_runsFakeCodexParsesJsonlAndBuildsExpectedArgs() =
        runTest {
            val tempDir = Files.createTempDirectory("codex-cli-executor-test")
            val workspace = Files.createDirectories(tempDir.resolve("workspace"))
            val argsFile = tempDir.resolve("args.txt")
            val promptArgFile = tempDir.resolve("prompt-arg.txt")
            val envFile = tempDir.resolve("env.txt")
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
                      echo "${LOGIN_STATUS_OK}"
                      exit 0
                    fi
                    printf '%s\n' "${'$'}@" > "${argsFile.toAbsolutePath()}"
                    eval "last_arg=\${'$'}{${'$'}#}"
                    printf '%s' "${'$'}last_arg" > "${promptArgFile.toAbsolutePath()}"
                    printf 'HOME=%s\nCODEX_HOME=%s\nCODEX_API_KEY=%s\nOPENAI_API_KEY=%s\nOPENAI_BASE_URL=%s\n' \
                      "${'$'}{HOME:-}" "${'$'}{CODEX_HOME:-}" "${'$'}{CODEX_API_KEY:-}" "${'$'}{OPENAI_API_KEY:-}" "${'$'}{OPENAI_BASE_URL:-}" > "${envFile.toAbsolutePath()}"
                    echo 'not-json'
                    echo '{"type":"item.completed","item":{"type":"mcp_tool_call","id":"call-1","server":"server-a","tool":"tool-x","unknown":"field"}}'
                    echo '{"type":"item.completed","item":{"type":"agent_message","text":"hello from codex","unknown":"field"}}'
                    """.trimIndent(),
                )
                script.toFile().setExecutable(true)

                val operations = mutableListOf<AgentExecutionOperation>()
                val executor = CodexCliExecutor(logger = NoopTestLogger)
                val request =
                    buildRequest(
                        command = script.toAbsolutePath().toString(),
                        workspacePath = workspace.toAbsolutePath().toString(),
                        portRange = portRange,
                        access = AgentFileSystemAccess.READ_WRITE,
                        codex =
                            AgentCodexConfig(
                                model = "gpt-5-codex",
                                reasoningEffort = AgentCodexReasoningEffort.MEDIUM,
                                webSearch = true,
                            ),
                        openAiBaseUrl = DEFAULT_OPENAI_BASE_URL,
                    ).copy(onOperation = { operation -> operations += operation })

                val result = executor.execute(request)
                assertTrue(result.isSuccess)
                assertEquals("hello from codex", result.getOrThrow().response)

                val args = Files.readAllLines(argsFile)
                assertTrue(args.contains("exec"))
                assertTrue(args.contains("--json"))
                assertTrue(args.contains("--model"))
                assertTrue(args.contains("gpt-5-codex"))
                assertTrue(args.contains("--sandbox"))
                assertTrue(args.contains("workspace-write"))
                assertTrue(args.contains("--cd"))
                assertTrue(args.contains(workspace.toAbsolutePath().toString()))
                assertTrue(args.contains("--skip-git-repo-check"))
                assertFalse(args.contains("--add-dir"))
                assertTrue(args.contains("approval_policy=\"never\""))
                assertTrue(args.contains("model_reasoning_effort=\"medium\""))
                assertTrue(args.contains("plan_mode_reasoning_effort=\"medium\""))
                assertTrue(args.contains("web_search=\"live\""))
                assertTrue(args.any { it.startsWith("mcp_servers.broxy.url=") })
                assertTrue(args.contains("sandbox_workspace_write.network_access=false"))

                val promptArg = Files.readString(promptArgFile)
                assertEquals(
                    "System prompt:\nYou are helpful\n\nUser prompt:\nrun prompt",
                    promptArg,
                )

                val env = Files.readString(envFile)
                val expectedHome = System.getProperty("user.home")
                val expectedCodexHome = File(expectedHome, ".codex").absolutePath
                assertTrue(env.lines().any { it == "HOME=$expectedHome" })
                assertTrue(env.lines().any { it == "CODEX_HOME=$expectedCodexHome" })
                assertTrue(env.lines().any { it == "CODEX_API_KEY=" })
                assertTrue(env.lines().any { it == "OPENAI_API_KEY=" })
                assertTrue(env.lines().any { it == "OPENAI_BASE_URL=" })
                assertTrue(!env.contains("CODEX_API_KEY=test-api-key"))
                assertTrue(!env.contains("OPENAI_API_KEY=test-api-key"))
                assertTrue(!env.contains("OPENAI_BASE_URL=$DEFAULT_OPENAI_BASE_URL"))

                assertTrue(operations.any { it == AgentExecutionOperation.LoadingCapabilities })
                assertTrue(
                    operations.any { operation ->
                        operation is AgentExecutionOperation.ToolExecution &&
                            operation.serverId == "server-a" &&
                            operation.toolName == "tool-x"
                    },
                )
                assertTrue(operations.any { it is AgentExecutionOperation.LlmResponseGeneration })
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

    fun execute_withNonWritableFs_usesReadOnlySandboxAndNoNetworkConfig() =
        runTest {
            val tempDir = Files.createTempDirectory("codex-cli-executor-readonly")
            val workspace = Files.createDirectories(tempDir.resolve("workspace"))
            val argsFile = tempDir.resolve("args.txt")
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
                      echo "${LOGIN_STATUS_OK}"
                      exit 0
                    fi
                    printf '%s\n' "${'$'}@" > "${argsFile.toAbsolutePath()}"
                    cat > /dev/null
                    echo '{"type":"item.completed","item":{"type":"agent_message","text":"ok"}}'
                    """.trimIndent(),
                )
                script.toFile().setExecutable(true)

                val executor = CodexCliExecutor(logger = NoopTestLogger)
                val request =
                    buildRequest(
                        command = script.toAbsolutePath().toString(),
                        workspacePath = workspace.toAbsolutePath().toString(),
                        portRange = portRange,
                        access = AgentFileSystemAccess.NONE,
                    )

                val result = executor.execute(request)
                assertTrue(result.isSuccess)
                assertEquals("ok", result.getOrThrow().response)

                val args = Files.readAllLines(argsFile)
                assertTrue(args.contains("read-only"))
                assertTrue(args.contains("--skip-git-repo-check"))
                assertFalse(args.any { it.startsWith("sandbox_workspace_write.network_access=") })
                assertTrue(args.contains("approval_policy=\"never\""))
                assertTrue(args.contains("web_search=\"disabled\""))
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

    @Test
    fun execute_refreshTokenReused_retriesAndSucceeds() =
        runTest {
            val tempDir = Files.createTempDirectory("codex-cli-executor-retry-success")
            val tempHome = Files.createDirectories(tempDir.resolve("home"))
            val workspace = Files.createDirectories(tempDir.resolve("workspace"))
            val attemptsFile = tempDir.resolve("attempts.txt")
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
                      echo "${LOGIN_STATUS_OK}"
                      exit 0
                    fi
                    if [ "${'$'}1" = "exec" ]; then
                      count=0
                      if [ -f "${attemptsFile.toAbsolutePath()}" ]; then
                        count=$(cat "${attemptsFile.toAbsolutePath()}")
                      fi
                      count=$((count + 1))
                      echo "${'$'}count" > "${attemptsFile.toAbsolutePath()}"
                      cat > /dev/null
                      if [ "${'$'}count" -eq 1 ]; then
                        echo "${REFRESH_TOKEN_REUSED_MESSAGE}" >&2
                        exit 1
                      fi
                      echo '{"type":"item.completed","item":{"type":"agent_message","text":"retry-ok"}}'
                      exit 0
                    fi
                    echo "unexpected command" >&2
                    exit 1
                    """.trimIndent(),
                )
                script.toFile().setExecutable(true)

                val authFile = writeAuthFile(tempHome, lastRefresh = "2026-03-07T13:20:58.521285Z")
                val result =
                    withUserHome(tempHome) {
                        backgroundScope.launch {
                            delay(100L)
                            writeAuthFile(
                                tempHome,
                                lastRefresh = "2026-03-07T13:21:58.521285Z",
                                accessTokenExpEpochSeconds = 4_102_444_801L,
                            )
                        }
                        val executor = CodexCliExecutor(logger = NoopTestLogger)
                        val request =
                            buildRequest(
                                command = script.toAbsolutePath().toString(),
                                workspacePath = workspace.toAbsolutePath().toString(),
                                portRange = portRange,
                            )
                        executor.execute(request)
                    }

                assertTrue(result.isSuccess)
                assertEquals("retry-ok", result.getOrThrow().response)
                assertEquals("2", Files.readString(attemptsFile).trim())
                assertTrue(Files.readString(authFile).contains("2026-03-07T13:21:58.521285Z"))
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

    @Test
    fun execute_refreshTokenReusedWithoutAuthUpdate_returnsNormalizedMessageWithoutRetry() =
        runTest {
            val tempDir = Files.createTempDirectory("codex-cli-executor-retry-failure")
            val tempHome = Files.createDirectories(tempDir.resolve("home"))
            val workspace = Files.createDirectories(tempDir.resolve("workspace"))
            val attemptsFile = tempDir.resolve("attempts.txt")
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
                      echo "${LOGIN_STATUS_OK}"
                      exit 0
                    fi
                    if [ "${'$'}1" = "exec" ]; then
                      count=0
                      if [ -f "${attemptsFile.toAbsolutePath()}" ]; then
                        count=$(cat "${attemptsFile.toAbsolutePath()}")
                      fi
                      count=$((count + 1))
                      echo "${'$'}count" > "${attemptsFile.toAbsolutePath()}"
                      cat > /dev/null
                      echo "${REFRESH_TOKEN_REUSED_MESSAGE}" >&2
                      exit 1
                    fi
                    echo "unexpected command" >&2
                    exit 1
                    """.trimIndent(),
                )
                script.toFile().setExecutable(true)

                writeAuthFile(tempHome, lastRefresh = "2026-03-07T13:20:58.521285Z")
                val result =
                    withUserHome(tempHome) {
                        val executor = CodexCliExecutor(logger = NoopTestLogger)
                        val request =
                            buildRequest(
                                command = script.toAbsolutePath().toString(),
                                workspacePath = workspace.toAbsolutePath().toString(),
                                portRange = portRange,
                            )
                        executor.execute(request)
                    }

                assertTrue(result.isFailure)
                val errorMessage = result.exceptionOrNull()?.message.orEmpty()
                assertTrue(errorMessage.contains("Run `codex login`"))
                assertTrue(errorMessage.contains("did not observe an updated ~/.codex/auth.json"))
                assertEquals("1", Files.readString(attemptsFile).trim())
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

    @Test
    fun execute_nonRefreshError_doesNotRetry() =
        runTest {
            val tempDir = Files.createTempDirectory("codex-cli-executor-no-retry")
            val workspace = Files.createDirectories(tempDir.resolve("workspace"))
            val attemptsFile = tempDir.resolve("attempts.txt")
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
                      echo "${LOGIN_STATUS_OK}"
                      exit 0
                    fi
                    if [ "${'$'}1" = "exec" ]; then
                      count=0
                      if [ -f "${attemptsFile.toAbsolutePath()}" ]; then
                        count=$(cat "${attemptsFile.toAbsolutePath()}")
                      fi
                      count=$((count + 1))
                      echo "${'$'}count" > "${attemptsFile.toAbsolutePath()}"
                      cat > /dev/null
                      echo "fatal execution error" >&2
                      exit 1
                    fi
                    echo "unexpected command" >&2
                    exit 1
                    """.trimIndent(),
                )
                script.toFile().setExecutable(true)

                val executor = CodexCliExecutor(logger = NoopTestLogger)
                val request =
                    buildRequest(
                        command = script.toAbsolutePath().toString(),
                        workspacePath = workspace.toAbsolutePath().toString(),
                        portRange = portRange,
                    )

                val result = executor.execute(request)
                assertTrue(result.isFailure)
                assertEquals("1", Files.readString(attemptsFile).trim())
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

    @Test
    fun execute_stdoutFailure_stopsChildProcess() =
        runTest {
            val tempDir = Files.createTempDirectory("codex-cli-executor-lifecycle")
            val workspace = Files.createDirectories(tempDir.resolve("workspace"))
            val pidFile = tempDir.resolve("pid.txt")
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
                      echo "${LOGIN_STATUS_OK}"
                      exit 0
                    fi
                    if [ "${'$'}1" = "exec" ]; then
                      echo "${'$'}${'$'}" > "${pidFile.toAbsolutePath()}"
                      cat > /dev/null
                      echo '{"type":"item.completed","item":{"type":"error","message":"boom"}}'
                      sleep 30
                      exit 0
                    fi
                    echo "unexpected command" >&2
                    exit 1
                    """.trimIndent(),
                )
                script.toFile().setExecutable(true)

                val executor = CodexCliExecutor(logger = NoopTestLogger)
                val request =
                    buildRequest(
                        command = script.toAbsolutePath().toString(),
                        workspacePath = workspace.toAbsolutePath().toString(),
                        portRange = portRange,
                    )

                val result = withTimeout(8_000L) { executor.execute(request) }
                assertTrue(result.isFailure)
                assertTrue(
                    result
                        .exceptionOrNull()
                        ?.message
                        .orEmpty()
                        .contains("boom"),
                )

                val pid = Files.readString(pidFile).trim().toLong()
                delay(300L)
                val alive = ProcessHandle.of(pid).map { handle -> handle.isAlive }.orElse(false)
                assertFalse(alive, "Codex child process should be terminated after stdout failure")
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

    @Test
    fun execute_loginStatusNotLoggedIn_failsFastBeforeExec() =
        runTest {
            val tempDir = Files.createTempDirectory("codex-cli-executor-preflight")
            val workspace = Files.createDirectories(tempDir.resolve("workspace"))
            val execCountFile = tempDir.resolve("exec-count.txt")
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
                      echo "Logged out"
                      exit 0
                    fi
                    if [ "${'$'}1" = "exec" ]; then
                      count=0
                      if [ -f "${execCountFile.toAbsolutePath()}" ]; then
                        count=$(cat "${execCountFile.toAbsolutePath()}")
                      fi
                      count=$((count + 1))
                      echo "${'$'}count" > "${execCountFile.toAbsolutePath()}"
                      cat > /dev/null
                      echo '{"type":"item.completed","item":{"type":"agent_message","text":"should-not-run"}}'
                      exit 0
                    fi
                    echo "unexpected command" >&2
                    exit 1
                    """.trimIndent(),
                )
                script.toFile().setExecutable(true)

                val executor = CodexCliExecutor(logger = NoopTestLogger)
                val request =
                    buildRequest(
                        command = script.toAbsolutePath().toString(),
                        workspacePath = workspace.toAbsolutePath().toString(),
                        portRange = portRange,
                    )

                val result = executor.execute(request)
                assertTrue(result.isFailure)
                val message = result.exceptionOrNull()?.message.orEmpty()
                assertTrue(message.contains("Run `codex login`"))
                assertFalse(Files.exists(execCountFile), "Exec should not start when preflight fails")
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

    @Suppress("LongParameterList")
    private fun buildRequest(
        command: String,
        workspacePath: String,
        portRange: IntRange,
        access: AgentFileSystemAccess = AgentFileSystemAccess.READ_WRITE,
        codex: AgentCodexConfig = AgentCodexConfig(model = "gpt-5-codex", webSearch = false),
        openAiBaseUrl: String? = null,
    ): AgentExecutionRequest =
        AgentExecutionRequest(
            agent =
                AgentDefinition(
                    id = "agent-1",
                    name = "Agent 1",
                    systemPrompt = "You are helpful",
                ),
            runtime = AgentRuntime.CODEX_CLI,
            llm =
                AgentLlmConfig(
                    provider = LlmProvider.OPENAI,
                    model = "gpt-4o-mini",
                    temperature = 0.2,
                ),
            codex = codex,
            prompt = "run prompt",
            fileSystem =
                AgentFileSystemSettings(
                    path = workspacePath,
                    access = access,
                ),
            providerSettings =
                AgentProviderSettings(
                    enableCodexProvider = true,
                    openAi = AgentProviderConfig(baseUrl = openAiBaseUrl),
                    codex =
                        AgentCodexGlobalSettings(
                            command = command,
                            portRangeStart = portRange.first,
                            portRangeEnd = portRange.last,
                        ),
                ),
            mcpConfig = McpServersConfig(),
            apiKey = "test-api-key",
        )

    private fun findFreePortRange(size: Int): IntRange {
        require(size > 0)
        for (start in 46_000..65_000 - size) {
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

    private fun writeAuthFile(
        homeDir: Path,
        lastRefresh: String,
        accessTokenExpEpochSeconds: Long = 4_102_444_800L,
    ): Path {
        val codexHome = Files.createDirectories(homeDir.resolve(".codex"))
        val authFile = codexHome.resolve("auth.json")
        Files.writeString(
            authFile,
            """
            {
              "tokens": {
                "id_token": "${fakeJwt(accessTokenExpEpochSeconds + 60)}",
                "access_token": "${fakeJwt(accessTokenExpEpochSeconds)}",
                "refresh_token": "refresh-token",
                "account_id": "account-id"
              },
              "last_refresh": "$lastRefresh"
            }
            """.trimIndent(),
        )
        return authFile
    }

    private fun fakeJwt(expEpochSeconds: Long): String {
        val header = base64Url("""{"alg":"none","typ":"JWT"}""")
        val payload = base64Url("""{"exp":$expEpochSeconds}""")
        return "$header.$payload.signature"
    }

    private fun base64Url(value: String): String =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(value.toByteArray(Charsets.UTF_8))

    private suspend fun <T> withUserHome(
        homeDir: Path,
        block: suspend () -> T,
    ): T {
        val original = System.getProperty("user.home")
        System.setProperty("user.home", homeDir.toString())
        return try {
            block()
        } finally {
            if (original == null) {
                System.clearProperty("user.home")
            } else {
                System.setProperty("user.home", original)
            }
        }
    }
}

private object NoopTestLogger : Logger {
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
