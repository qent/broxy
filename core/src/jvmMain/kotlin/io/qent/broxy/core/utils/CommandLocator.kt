package io.qent.broxy.core.utils

import java.nio.file.Files
import java.nio.file.Paths
import java.util.Locale

object CommandLocator {
    fun resolveCommand(
        command: String,
        pathOverride: String? = null,
        logger: Logger? = null,
    ): String? {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return null
        val isWindows = isWindows()
        resolveExplicitPath(trimmed, isWindows)?.let { return it }

        val resolvedPath =
            pathOverride?.takeIf { it.isNotBlank() }
                ?: UserPathResolver.resolve(logger)
                ?: readSystemPath()
        if (resolvedPath.isNullOrBlank()) return null

        val separator = if (isWindows) ';' else ':'
        val entries = resolvedPath.split(separator).map { it.trim().trim('"') }.filter { it.isNotBlank() }
        return resolveFromPath(trimmed, entries, isWindows)
    }

    private fun resolveExplicitPath(
        command: String,
        isWindows: Boolean,
    ): String? {
        if (!looksLikePath(command)) return null
        val candidate = Paths.get(command)
        if (isUsable(candidate, isWindows)) {
            return candidate.toAbsolutePath().toString()
        }
        if (isWindows && !hasExtension(command)) {
            val exts = parsePathExt(System.getenv("PATHEXT"))
            for (ext in exts) {
                val withExt = Paths.get(command + ext)
                if (isUsable(withExt, isWindows)) {
                    return withExt.toAbsolutePath().toString()
                }
            }
        }
        return null
    }

    private fun resolveFromPath(
        command: String,
        entries: List<String>,
        isWindows: Boolean,
    ): String? {
        val exts = if (isWindows) parsePathExt(System.getenv("PATHEXT")) else emptyList()
        val hasExt = isWindows && hasExtension(command)
        for (entry in entries) {
            val candidate = Paths.get(entry, command)
            if (isUsable(candidate, isWindows)) {
                return candidate.toAbsolutePath().toString()
            }
            if (isWindows && !hasExt) {
                for (ext in exts) {
                    val withExt = Paths.get(entry, command + ext)
                    if (isUsable(withExt, isWindows)) {
                        return withExt.toAbsolutePath().toString()
                    }
                }
            }
        }
        return null
    }

    private fun isUsable(
        path: java.nio.file.Path,
        isWindows: Boolean,
    ): Boolean {
        return Files.isRegularFile(path) && (isWindows || Files.isExecutable(path))
    }

    private fun looksLikePath(command: String): Boolean = command.contains('/') || command.contains('\\')

    private fun hasExtension(command: String): Boolean {
        val base = command.substringAfterLast('/').substringAfterLast('\\')
        return base.contains('.')
    }

    private fun parsePathExt(value: String?): List<String> {
        val defaults = listOf(".EXE", ".BAT", ".CMD", ".COM")
        val parsed =
            value?.split(';')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.map { if (it.startsWith(".")) it else ".$it" }
        return parsed?.takeIf { it.isNotEmpty() } ?: defaults
    }

    private fun readSystemPath(): String? = System.getenv("PATH") ?: System.getenv("Path")

    private fun isWindows(): Boolean = System.getProperty("os.name")?.lowercase(Locale.ROOT)?.contains("win") == true
}
