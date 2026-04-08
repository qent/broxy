package io.qent.broxy.agents.codex.runtime

import java.io.File

internal class CodexPreflightChecker(
    private val processRunner: CodexProcessRunner,
) {
    fun run(
        command: String,
        environment: Map<String, String>,
        workingDirectory: File,
    ): CodexPreflightResult {
        val versionProbe =
            processRunner.runShortCommand(
                command = listOf(command, "--version"),
                workingDirectory = workingDirectory,
                environment = environment,
            )
        if (versionProbe.exitCode != 0) {
            return CodexPreflightResult(
                versionExitCode = versionProbe.exitCode,
                versionOutput = versionProbe.combinedOutput().abbreviate(),
                loginStatusExitCode = -1,
                loginStatusOutput = "Skipped: version preflight failed",
                sessionReady = false,
            )
        }

        val loginProbe =
            processRunner.runShortCommand(
                command = listOf(command, "login", "status"),
                workingDirectory = workingDirectory,
                environment = environment,
            )
        val loginCombinedOutput = loginProbe.combinedOutput()
        val loggedIn = loginCombinedOutput.contains(CODEX_LOGIN_STATUS_MARKER, ignoreCase = true)
        return CodexPreflightResult(
            versionExitCode = versionProbe.exitCode,
            versionOutput = versionProbe.combinedOutput().abbreviate(),
            loginStatusExitCode = loginProbe.exitCode,
            loginStatusOutput = loginCombinedOutput.abbreviate(),
            sessionReady = loginProbe.exitCode == 0 && loggedIn,
        )
    }
}
