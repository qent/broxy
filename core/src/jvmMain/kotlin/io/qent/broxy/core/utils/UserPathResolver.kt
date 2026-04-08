package io.qent.broxy.core.utils

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object UserPathResolver {
    private const val PATH = "PATH"
    private val cachedPath = AtomicReference<String?>(null)
    private val resolvedOnce = AtomicBoolean(false)
    private val lock = Any()

    fun resolve(logger: Logger? = null): String? {
        if (!resolvedOnce.get()) {
            synchronized(lock) {
                if (!resolvedOnce.get()) {
                    val resolved = resolveInternal(logger)
                    cachedPath.set(resolved)
                    resolvedOnce.set(true)
                }
            }
        }
        return cachedPath.get()
    }

    fun resolvePathKey(env: Map<String, String>): String = env.keys.firstOrNull { it.equals(PATH, true) } ?: PATH

    private fun resolveInternal(logger: Logger?): String? = resolveInternalPath(logger)
}

private const val RESOLVE_TIMEOUT_SECONDS = 2L

private fun resolveInternalPath(logger: Logger?): String? {
    val systemPath = readSystemPath()
    val loginShellPath = resolveShellPath(logger, isLogin = true)
    val interactiveShellPath = resolveShellPath(logger, isLogin = false)
    val merged = mergePaths(listOf(loginShellPath, interactiveShellPath, systemPath))
    val withDefaults = if (isMac()) appendMacDefaults(merged) else merged
    return withDefaults
}

private fun readSystemPath(): String? = System.getenv("PATH") ?: System.getenv("Path")

private fun resolveShellPath(
    logger: Logger?,
    isLogin: Boolean,
): String? {
    val shell = if (isWindows()) null else resolveShellExecutable()
    if (shell == null) {
        return null
    }
    val markerStart = "__BROXY_PATH_START__"
    val markerEnd = "__BROXY_PATH_END__"
    val command = "printf '%s' \"${markerStart}${'$'}{PATH}${markerEnd}\""
    val flag = if (isLogin) "-lc" else "-ic"
    val result = runCommand(listOf(shell, flag, command), RESOLVE_TIMEOUT_SECONDS)
    val separator = if (isWindows()) ';' else ':'
    val candidate =
        if (result.exitCode == 0) {
            val marked = extractBetweenMarkers(result.output, markerStart, markerEnd)
            (marked ?: fallbackPathLine(result.output, separator))?.trim()
        } else {
            val mode = if (isLogin) "login" else "interactive"
            logger?.warn("Failed to resolve $mode shell PATH (exit ${result.exitCode}).")
            null
        }
    val sanitized =
        if (candidate.isNullOrBlank() || candidate.contains(markerStart) || candidate.contains(markerEnd)) {
            null
        } else {
            candidate
        }
    return sanitized
}

private fun resolveShellExecutable(): String? {
    val envShell = System.getenv("SHELL")?.takeIf { it.isNotBlank() }
    val shell = envShell ?: defaultShell()
    return shell?.takeIf { Files.isExecutable(Paths.get(it)) }
}

private fun defaultShell(): String? {
    val name = System.getProperty("os.name")?.lowercase(Locale.ROOT) ?: return null
    return if (name.contains("mac")) "/bin/zsh" else "/bin/bash"
}

private fun mergePaths(paths: List<String?>): String? {
    val separator = File.pathSeparatorChar
    val entries = LinkedHashSet<String>()
    paths.filterNotNull().forEach { path ->
        parsePathEntries(path, separator).forEach { entry ->
            entries.add(entry)
        }
    }
    return if (entries.isEmpty()) null else entries.joinToString(separator.toString())
}

private fun appendMacDefaults(path: String?): String? {
    val defaults = listOf("/opt/homebrew/bin", "/opt/homebrew/sbin", "/usr/local/bin", "/usr/local/sbin")
    val separator = File.pathSeparatorChar
    val entries = LinkedHashSet<String>()
    parsePathEntries(path, separator).forEach { entries.add(it) }
    defaults.forEach { entries.add(it) }
    return if (entries.isEmpty()) null else entries.joinToString(separator.toString())
}

private fun runCommand(
    args: List<String>,
    timeoutSeconds: Long,
): CommandResult =
    runCatching {
        val process =
            ProcessBuilder(args)
                .redirectErrorStream(true)
                .start()
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
        }
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        val exitCode = if (finished) process.exitValue() else -1
        CommandResult(exitCode = exitCode, output = output)
    }.getOrElse { ex ->
        if (ex is InterruptedException) {
            Thread.currentThread().interrupt()
        }
        CommandResult(exitCode = -1, output = ex.message.orEmpty())
    }

private data class CommandResult(
    val exitCode: Int,
    val output: String,
)
