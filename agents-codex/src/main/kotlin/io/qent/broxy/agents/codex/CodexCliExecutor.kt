package io.qent.broxy.agents.codex

import io.qent.broxy.agents.AgentCodexConfig
import io.qent.broxy.agents.AgentExecutionOperation
import io.qent.broxy.agents.AgentExecutionRequest
import io.qent.broxy.agents.AgentExecutionResult
import io.qent.broxy.agents.AgentExecutor
import io.qent.broxy.agents.AgentRunActionEntry
import io.qent.broxy.agents.AgentRunActionType
import io.qent.broxy.agents.AgentRunDialogueEntry
import io.qent.broxy.agents.AgentRunDialogueRole
import io.qent.broxy.agents.DEFAULT_AGENT_WORKSPACE_PATH
import io.qent.broxy.agents.codex.mcp.AgentRunMcpIsolator
import io.qent.broxy.agents.codex.runtime.AuthFileChangeWaitResult
import io.qent.broxy.agents.codex.runtime.AuthFileState
import io.qent.broxy.agents.codex.runtime.CODEX_MAX_AUTH_RETRY_ATTEMPTS
import io.qent.broxy.agents.codex.runtime.CodexAttemptResult
import io.qent.broxy.agents.codex.runtime.CodexAuthRetryPolicy
import io.qent.broxy.agents.codex.runtime.CodexAuthStateInspector
import io.qent.broxy.agents.codex.runtime.CodexCommandEnvironment
import io.qent.broxy.agents.codex.runtime.CodexCommandEnvironmentBuilder
import io.qent.broxy.agents.codex.runtime.CodexCommandEnvironmentRequest
import io.qent.broxy.agents.codex.runtime.CodexExecutionProcess
import io.qent.broxy.agents.codex.runtime.CodexJsonlEventMapper
import io.qent.broxy.agents.codex.runtime.CodexPreflightChecker
import io.qent.broxy.agents.codex.runtime.CodexPreflightException
import io.qent.broxy.agents.codex.runtime.CodexPreflightResult
import io.qent.broxy.agents.codex.runtime.CodexProcessRunner
import io.qent.broxy.agents.codex.runtime.CodexRefreshTokenReusedException
import io.qent.broxy.agents.codex.runtime.putAuthFileState
import io.qent.broxy.agents.codex.runtime.putAuthMetadata
import io.qent.broxy.agents.resolveClaudeFileSystemAccess
import io.qent.broxy.agents.resolveClaudePermissionModeWarning
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger
import io.qent.broxy.core.utils.errorJson
import io.qent.broxy.core.utils.infoJson
import io.qent.broxy.core.utils.warnJson
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class CodexCliExecutor(
    private val logger: Logger = ConsoleLogger,
    private val oauthStateStoreBaseDir: Path =
        Paths.get(System.getProperty("user.home"), ".config", "broxy"),
    private val isolator: AgentRunMcpIsolator =
        AgentRunMcpIsolator(
            logger = logger,
            oauthStateStoreBaseDir = oauthStateStoreBaseDir,
        ),
    private val defaultWorkspacePath: Path = Paths.get(DEFAULT_AGENT_WORKSPACE_PATH),
) : AgentExecutor {
    private val commandEnvironmentBuilder = CodexCommandEnvironmentBuilder(logger)
    private val processRunner = CodexProcessRunner()
    private val preflightChecker = CodexPreflightChecker(processRunner = processRunner)
    private val authStateInspector = CodexAuthStateInspector()
    private val retryPolicy = CodexAuthRetryPolicy()
    private val eventMapper = CodexJsonlEventMapper()
    private val executionProcess =
        CodexExecutionProcess(
            processRunner = processRunner,
            eventMapper = eventMapper,
        )

    @Suppress("LongMethod")
    override suspend fun execute(request: AgentExecutionRequest): Result<AgentExecutionResult> =
        runCatching {
            val permissionModeWarning = resolveClaudePermissionModeWarning(request.agent)
            if (permissionModeWarning != null) {
                emitClaudeCompatibilityWarning(request, permissionModeWarning)
            }
            val fsAccessResolution = resolveClaudeFileSystemAccess(request.agent, request.fileSystem.access)
            fsAccessResolution.warnings.forEach { warning ->
                emitClaudeCompatibilityWarning(request, warning)
            }
            val effectiveRequest =
                if (fsAccessResolution.access == request.fileSystem.access) {
                    request
                } else {
                    request.copy(
                        fileSystem =
                            request.fileSystem.copy(
                                access = fsAccessResolution.access,
                            ),
                    )
                }

            effectiveRequest.onOperation(AgentExecutionOperation.LoadingCapabilities)
            val codexConfig = effectiveRequest.codex ?: AgentCodexConfig()
            val portRange = effectiveRequest.providerSettings.codex
            val session =
                isolator.start(
                    request = effectiveRequest,
                    portRangeStart = portRange.portRangeStart,
                    portRangeEnd = portRange.portRangeEnd,
                )
            try {
                val context = prepareRunContext(effectiveRequest, codexConfig, session.endpointUrl)
                effectiveRequest.onTraceDialogue(
                    AgentRunDialogueEntry(
                        role = AgentRunDialogueRole.SYSTEM,
                        content = effectiveRequest.agent.systemPrompt,
                        timestampEpochMillis = System.currentTimeMillis(),
                    ),
                )
                effectiveRequest.onTraceDialogue(
                    AgentRunDialogueEntry(
                        role = AgentRunDialogueRole.USER,
                        content = effectiveRequest.prompt,
                        timestampEpochMillis = System.currentTimeMillis(),
                    ),
                )
                val preflight = runPreflightChecks(effectiveRequest, context)
                ensurePreflight(preflight)
                logExecutionStarted(effectiveRequest, context, codexConfig)
                runWithAuthRetry(effectiveRequest, context)
            } finally {
                runCatching { session.close() }
            }
        }.onFailure { failure ->
            logger.errorJson("agent.codex.exec.failed", failure) {
                put("agentId", JsonPrimitive(request.agent.id))
                put("runtime", JsonPrimitive(request.runtime.name))
                put("failureKind", JsonPrimitive(retryPolicy.classifyFailureKind(failure)))
                put("errorMessage", JsonPrimitive(failure.message ?: "Codex execution failed"))
            }
        }

    private fun emitClaudeCompatibilityWarning(
        request: AgentExecutionRequest,
        message: String,
    ) {
        logger.warnJson("agent.claude.compat.warning") {
            put("agentId", JsonPrimitive(request.agent.id))
            put("message", JsonPrimitive(message))
        }
        request.onTraceAction(
            AgentRunActionEntry(
                type = AgentRunActionType.RUNTIME_EVENT,
                message = message,
                timestampEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    private fun prepareRunContext(
        request: AgentExecutionRequest,
        codexConfig: AgentCodexConfig,
        endpointUrl: String,
    ): CodexRunContext {
        val workingDirectory = resolveWorkingDirectory(request.fileSystem.path)
        val commandEnvironment =
            commandEnvironmentBuilder.build(
                CodexCommandEnvironmentRequest(
                    configuredCommand = request.providerSettings.codex.command,
                    codex = codexConfig,
                    fileSystemAccess = request.fileSystem.access,
                    workingDirectory = workingDirectory,
                    endpointUrl = endpointUrl,
                    systemPrompt = request.agent.systemPrompt,
                    userPrompt = request.prompt,
                    openAiBaseUrl = request.providerSettings.openAi.baseUrl,
                ),
            )
        val authFile = File(commandEnvironment.codexHome, "auth.json")
        val authState = authStateInspector.readAuthFileState(authFile)
        return CodexRunContext(
            workingDirectory = workingDirectory,
            commandEnvironment = commandEnvironment,
            authFile = authFile,
            initialAuthState = authState,
            endpointUrl = endpointUrl,
        )
    }

    private fun runPreflightChecks(
        request: AgentExecutionRequest,
        context: CodexRunContext,
    ): CodexPreflightResult {
        val preflight =
            preflightChecker.run(
                command = context.commandEnvironment.resolvedCommand,
                environment = context.commandEnvironment.environment,
                workingDirectory = context.workingDirectory,
            )
        logger.infoJson("agent.codex.exec.preflight") {
            put("agentId", JsonPrimitive(request.agent.id))
            put("runtime", JsonPrimitive(request.runtime.name))
            put("command", JsonPrimitive(context.commandEnvironment.resolvedCommand))
            put("versionExitCode", JsonPrimitive(preflight.versionExitCode))
            put("versionOutput", JsonPrimitive(preflight.versionOutput))
            put("loginStatusExitCode", JsonPrimitive(preflight.loginStatusExitCode))
            put("loginStatusOutput", JsonPrimitive(preflight.loginStatusOutput))
            put("sessionReady", JsonPrimitive(preflight.sessionReady))
            put("hadInheritedOpenAiBaseUrl", JsonPrimitive(context.commandEnvironment.hadInheritedOpenAiBaseUrl))
            put("openAiBaseUrlOverrideApplied", JsonPrimitive(context.commandEnvironment.openAiBaseUrlOverride != null))
            putAuthFileState("auth", context.initialAuthState)
        }
        return preflight
    }

    private fun ensurePreflight(preflight: CodexPreflightResult) {
        if (preflight.versionExitCode != 0) {
            throw CodexPreflightException(
                "Codex preflight failed (`codex --version`). " +
                    "Check Codex CLI availability and retry. Details: ${preflight.versionOutput}",
            )
        }
        if (!preflight.sessionReady) {
            throw CodexPreflightException(
                "Codex user session is unavailable. " +
                    "Run `codex login` in your terminal, then retry. " +
                    "Details: ${preflight.loginStatusOutput}",
            )
        }
    }

    private fun logExecutionStarted(
        request: AgentExecutionRequest,
        context: CodexRunContext,
        codexConfig: AgentCodexConfig,
    ) {
        logger.infoJson("agent.codex.exec.started") {
            put("agentId", JsonPrimitive(request.agent.id))
            put("runtime", JsonPrimitive(request.runtime.name))
            put("model", JsonPrimitive(codexConfig.model))
            put("reasoningEffort", JsonPrimitive(codexConfig.reasoningEffort.name.lowercase()))
            put("workingDirectory", JsonPrimitive(context.workingDirectory.path))
            put("endpointUrl", JsonPrimitive(context.endpointUrl))
            put("command", JsonPrimitive(context.commandEnvironment.resolvedCommand))
            put("authHome", JsonPrimitive(context.commandEnvironment.codexHome))
            put("authFileSnapshot", JsonPrimitive(context.initialAuthState.snapshot.fingerprint()))
            put("hadInheritedOpenAiApiKey", JsonPrimitive(context.commandEnvironment.hadOpenAiApiKey))
            put("hadInheritedCodexApiKey", JsonPrimitive(context.commandEnvironment.hadCodexApiKey))
            put("hadInheritedOpenAiBaseUrl", JsonPrimitive(context.commandEnvironment.hadInheritedOpenAiBaseUrl))
            put("openAiBaseUrlOverrideApplied", JsonPrimitive(context.commandEnvironment.openAiBaseUrlOverride != null))
            put("maxAuthRetryAttempts", JsonPrimitive(CODEX_MAX_AUTH_RETRY_ATTEMPTS))
            putAuthMetadata("auth", context.initialAuthState.metadata)
        }
    }

    private suspend fun runWithAuthRetry(
        request: AgentExecutionRequest,
        context: CodexRunContext,
    ): AgentExecutionResult {
        var authRetryAttemptsUsed = 0
        while (true) {
            val authBeforeAttempt = authStateInspector.readAuthFileState(context.authFile)
            val attemptResult =
                runCatching {
                    executionProcess.runAttempt(
                        command = context.commandEnvironment.execCommand,
                        environment = context.commandEnvironment.environment,
                        workingDirectory = context.workingDirectory,
                        request = request,
                    )
                }
            val successAttempt = attemptResult.getOrNull()
            if (successAttempt != null) {
                logExecutionFinished(request, successAttempt, authRetryAttemptsUsed)
                return AgentExecutionResult(response = successAttempt.response)
            }

            val failure = checkNotNull(attemptResult.exceptionOrNull())
            authRetryAttemptsUsed =
                processRefreshTokenRetry(
                    request = request,
                    context = context,
                    authBeforeAttempt = authBeforeAttempt,
                    failure = failure,
                    authRetryAttemptsUsed = authRetryAttemptsUsed,
                )
        }
    }

    private fun logExecutionFinished(
        request: AgentExecutionRequest,
        attempt: CodexAttemptResult,
        authRetryAttemptsUsed: Int,
    ) {
        logger.infoJson("agent.codex.exec.finished") {
            put("agentId", JsonPrimitive(request.agent.id))
            put("responseLength", JsonPrimitive(attempt.response.length))
            put("stepCount", JsonPrimitive(attempt.stepCount))
            put("authRetryAttemptsUsed", JsonPrimitive(authRetryAttemptsUsed))
        }
    }

    private suspend fun processRefreshTokenRetry(
        request: AgentExecutionRequest,
        context: CodexRunContext,
        authBeforeAttempt: AuthFileState,
        failure: Throwable,
        authRetryAttemptsUsed: Int,
    ): Int {
        if (!retryPolicy.isRefreshTokenReusedFailure(failure)) {
            throw failure
        }

        val authAfterFailure = authStateInspector.readAuthFileState(context.authFile)
        val authWaitResult =
            if (authAfterFailure.snapshot == authBeforeAttempt.snapshot) {
                authStateInspector.waitForAuthFileChange(
                    authFile = context.authFile,
                    baseline = authAfterFailure.snapshot,
                )
            } else {
                AuthFileChangeWaitResult(
                    state = authAfterFailure,
                    changed = true,
                    waited = false,
                    waitedMillis = 0L,
                )
            }
        val finalAuthState = authWaitResult.state
        val authFileChanged = authBeforeAttempt.snapshot != finalAuthState.snapshot
        val retryPlanned = authFileChanged && authRetryAttemptsUsed < CODEX_MAX_AUTH_RETRY_ATTEMPTS

        logger.infoJson("agent.codex.exec.auth_retry") {
            put("agentId", JsonPrimitive(request.agent.id))
            put("attempt", JsonPrimitive(authRetryAttemptsUsed + 1))
            put("maxAuthRetryAttempts", JsonPrimitive(CODEX_MAX_AUTH_RETRY_ATTEMPTS))
            put("authFileSnapshotBefore", JsonPrimitive(authBeforeAttempt.snapshot.fingerprint()))
            put("authFileSnapshotAfterFailure", JsonPrimitive(authAfterFailure.snapshot.fingerprint()))
            put("authFileSnapshotAfterWait", JsonPrimitive(finalAuthState.snapshot.fingerprint()))
            put("authFileChanged", JsonPrimitive(authFileChanged))
            put("waitedForAuthFileChange", JsonPrimitive(authWaitResult.waited))
            put("waitDurationMillis", JsonPrimitive(authWaitResult.waitedMillis))
            put("retryPlanned", JsonPrimitive(retryPlanned))
            putAuthMetadata("authBefore", authBeforeAttempt.metadata)
            putAuthMetadata("authAfterFailure", authAfterFailure.metadata)
            putAuthMetadata("authAfterWait", finalAuthState.metadata)
            put(
                "errorMessage",
                JsonPrimitive(
                    failure.message?.trim()?.takeIf { it.isNotBlank() }
                        ?: "Codex execution failed",
                ),
            )
        }

        if (!retryPlanned) {
            throw CodexRefreshTokenReusedException(
                retryPolicy.buildRefreshTokenFailureMessage(authFileChanged),
                failure,
            )
        }
        return authRetryAttemptsUsed + 1
    }

    private fun resolveWorkingDirectory(rawPath: String): File {
        val workspacePath =
            runCatching { Paths.get(rawPath).toAbsolutePath().normalize() }
                .getOrElse { error("Invalid Codex workspace path: $rawPath") }
        val normalizedDefaultWorkspace = defaultWorkspacePath.toAbsolutePath().normalize()

        if (Files.exists(workspacePath)) {
            require(Files.isDirectory(workspacePath)) {
                "Codex workspace path is not a directory: ${workspacePath.toAbsolutePath()}"
            }
            return workspacePath.toFile()
        }

        if (workspacePath == normalizedDefaultWorkspace) {
            runCatching {
                Files.createDirectories(workspacePath)
            }.getOrElse { failure ->
                error(
                    "Failed to create Codex workspace directory: ${workspacePath.toAbsolutePath()}. " +
                        "${failure.message ?: "Unknown error"}",
                )
            }
            return workspacePath.toFile()
        }

        error("Codex workspace directory does not exist: ${workspacePath.toAbsolutePath()}")
    }
}

private data class CodexRunContext(
    val workingDirectory: File,
    val commandEnvironment: CodexCommandEnvironment,
    val authFile: File,
    val initialAuthState: AuthFileState,
    val endpointUrl: String,
)
