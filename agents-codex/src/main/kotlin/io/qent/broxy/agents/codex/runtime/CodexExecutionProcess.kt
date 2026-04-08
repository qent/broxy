package io.qent.broxy.agents.codex.runtime

import io.qent.broxy.agents.AgentExecutionRequest
import java.io.File

internal class CodexExecutionProcess(
    private val processRunner: CodexProcessRunner,
    private val eventMapper: CodexJsonlEventMapper,
) {
    suspend fun runAttempt(
        command: List<String>,
        environment: Map<String, String>,
        workingDirectory: File,
        request: AgentExecutionRequest,
    ): CodexAttemptResult {
        val state = CodexEventStreamState()
        val processResult =
            processRunner.runExecJsonl(
                command = command,
                environment = environment,
                workingDirectory = workingDirectory,
                processNameSuffix = request.agent.id,
                onStdoutLine = { line ->
                    eventMapper.consumeLine(
                        line = line,
                        state = state,
                        onOperation = request.onOperation,
                        onTraceDialogue = request.onTraceDialogue,
                        onTraceAction = request.onTraceAction,
                    )
                },
            )

        if (processResult.exitCode != 0) {
            val details = processResult.stderrOutput.ifBlank { "exit code ${processResult.exitCode}" }
            error("Codex CLI failed: $details")
        }

        val response = state.finalResponse.ifBlank { "Done." }
        return CodexAttemptResult(
            response = response,
            stepCount = state.step,
        )
    }
}
