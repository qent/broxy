package io.qent.broxy.agents.codex.runtime

import kotlinx.coroutines.Job
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.coroutineContext

internal data class CodexExecProcessResult(
    val exitCode: Int,
    val stderrOutput: String,
)

internal class CodexProcessRunner {
    suspend fun runExecJsonl(
        command: List<String>,
        environment: Map<String, String>,
        workingDirectory: File,
        processNameSuffix: String,
        onStdoutLine: (String) -> Unit,
    ): CodexExecProcessResult {
        val processBuilder = ProcessBuilder(command)
        processBuilder.directory(workingDirectory)
        processBuilder.applyEnvironment(environment)
        val process = processBuilder.start()

        val cancellationHook =
            coroutineContext[Job]
                ?.invokeOnCompletion {
                    runCatching { process.destroyForcibly() }
                }

        val stderr = StringBuilder()
        val stderrReader =
            thread(start = true, isDaemon = true, name = "codex-stderr-$processNameSuffix") {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(stderr) {
                            if (stderr.isNotEmpty()) {
                                stderr.append('\n')
                            }
                            stderr.append(line)
                        }
                    }
                }
            }

        val readResult =
            runCatching {
                process.outputStream.close()
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        onStdoutLine(line)
                    }
                }
            }
        runCatching { process.outputStream.close() }
        val finished = process.waitFor(CODEX_PROCESS_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            runCatching { process.destroyForcibly() }
            runCatching { process.waitFor(CODEX_PROCESS_FORCE_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        }
        stderrReader.join(CODEX_IO_THREAD_JOIN_TIMEOUT_MILLIS)
        cancellationHook?.dispose()

        readResult.exceptionOrNull()?.let { throw it }

        val exitCode = runCatching { process.exitValue() }.getOrDefault(-1)
        return CodexExecProcessResult(
            exitCode = exitCode,
            stderrOutput = synchronized(stderr) { stderr.toString().trim() },
        )
    }

    fun runShortCommand(
        command: List<String>,
        workingDirectory: File,
        environment: Map<String, String>,
    ): CommandProbeResult {
        val processBuilder = ProcessBuilder(command)
        processBuilder.directory(workingDirectory)
        processBuilder.applyEnvironment(environment)
        val process = processBuilder.start()

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutReader =
            thread(start = true, isDaemon = true, name = "codex-short-stdout") {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(stdout) {
                            if (stdout.isNotEmpty()) {
                                stdout.append('\n')
                            }
                            stdout.append(line)
                        }
                    }
                }
            }
        val stderrReader =
            thread(start = true, isDaemon = true, name = "codex-short-stderr") {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(stderr) {
                            if (stderr.isNotEmpty()) {
                                stderr.append('\n')
                            }
                            stderr.append(line)
                        }
                    }
                }
            }

        val finished = process.waitFor(CODEX_SHORT_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            runCatching { process.destroyForcibly() }
            runCatching { process.waitFor(CODEX_PROCESS_FORCE_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        }
        stdoutReader.join(CODEX_IO_THREAD_JOIN_TIMEOUT_MILLIS)
        stderrReader.join(CODEX_IO_THREAD_JOIN_TIMEOUT_MILLIS)
        val exitCode = runCatching { process.exitValue() }.getOrDefault(-1)

        return CommandProbeResult(
            exitCode = exitCode,
            stdout = synchronized(stdout) { stdout.toString() },
            stderr = synchronized(stderr) { stderr.toString() },
        )
    }
}

private fun ProcessBuilder.applyEnvironment(environment: Map<String, String>) {
    val target = environment()
    target.clear()
    target.putAll(environment)
}
