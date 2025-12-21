package io.qent.broxy.core.utils

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object UserPathResolver {
    private const val RESOLVE_TIMEOUT_SECONDS = 2L
    private val cachedPath = AtomicReference<String?>(null)
    private val resolvedOnce = AtomicBoolean(false)
    private val lock = Any()

    fun resolve(logger: Logger? = null): String? {
        if (resolvedOnce.get()) return cachedPath.get()
        synchronized(lock) {
            if (resolvedOnce.get()) return cachedPath.get()
            val resolved = resolveInternal(logger)
            cachedPath.set(resolved)
            resolvedOnce.set(true)
            return resolved
        }
    }

    fun resolvePathKey(env: Map<String, String>): String = env.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"

    private fun resolveInternal(logger: Logger?): String? {
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
        if (isWindows()) return null
        val shell = resolveShellExecutable() ?: return null
        val markerStart = "__BROXY_PATH_START__"
        val markerEnd = "__BROXY_PATH_END__"
        val command = "printf '%s' \"${markerStart}${'$'}{PATH}${markerEnd}\""
        val flag = if (isLogin) "-lc" else "-ic"
        val result = runCommand(listOf(shell, flag, command), RESOLVE_TIMEOUT_SECONDS)
        if (result.exitCode != 0) {
            val mode = if (isLogin) "login" else "interactive"
            logger?.warn("Failed to resolve $mode shell PATH (exit ${result.exitCode}).")
            return null
        }
        val marked = extractBetweenMarkers(result.output, markerStart, markerEnd)
        val candidate = (marked ?: fallbackPathLine(result.output))?.trim()
        if (candidate.isNullOrBlank()) return null
        if (candidate.contains(markerStart) || candidate.contains(markerEnd)) return null
        return candidate
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

    private fun isWindows(): Boolean = System.getProperty("os.name")?.lowercase(Locale.ROOT)?.contains("win") == true

    private fun isMac(): Boolean = System.getProperty("os.name")?.lowercase(Locale.ROOT)?.contains("mac") == true

    private fun mergePaths(paths: List<String?>): String? {
        val separator = File.pathSeparator
        val entries = LinkedHashSet<String>()
        paths.filterNotNull()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { path ->
                path.split(separator).map { it.trim().trim('"') }.filter { it.isNotBlank() }.forEach { entry ->
                    entries.add(entry)
                }
            }
        return if (entries.isEmpty()) null else entries.joinToString(separator)
    }

    private fun appendMacDefaults(path: String?): String? {
        val defaults = listOf("/opt/homebrew/bin", "/opt/homebrew/sbin", "/usr/local/bin", "/usr/local/sbin")
        val separator = File.pathSeparator
        val entries = LinkedHashSet<String>()
        path?.split(separator)?.map { it.trim().trim('"') }?.filter { it.isNotBlank() }?.forEach { entries.add(it) }
        defaults.forEach { entries.add(it) }
        return if (entries.isEmpty()) null else entries.joinToString(separator)
    }

    private fun extractBetweenMarkers(
        output: String,
        start: String,
        end: String,
    ): String? {
        val startIndex = output.indexOf(start)
        if (startIndex < 0) return null
        val endIndex = output.indexOf(end, startIndex + start.length)
        if (endIndex < 0) return null
        return output.substring(startIndex + start.length, endIndex)
    }

    private fun fallbackPathLine(output: String): String? {
        val separator = if (isWindows()) ';' else ':'
        val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        return lines.lastOrNull { it.contains(separator) } ?: lines.lastOrNull()
    }

    private fun runCommand(
        args: List<String>,
        timeoutSeconds: Long,
    ): CommandResult {
        return try {
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
        } catch (ex: Exception) {
            CommandResult(exitCode = -1, output = "")
        }
    }

    private data class CommandResult(
        val exitCode: Int,
        val output: String,
    )
}
