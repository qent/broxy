package io.qent.broxy.core.utils

import java.nio.file.Files
import java.nio.file.Paths

object CommandLocator {
    fun resolveCommand(
        command: String,
        pathOverride: String? = null,
        logger: Logger? = null,
    ): String? {
        val trimmed = command.trim()
        if (trimmed.isNotBlank()) {
            val isWindows = isWindows()
            val explicit = resolveExplicitPath(trimmed, isWindows)
            val entries =
                if (explicit == null) {
                    resolvePathEntries(pathOverride, logger, isWindows)
                } else {
                    null
                }
            return explicit ?: entries?.let { resolveFromPath(trimmed, entries, isWindows) }
        }
        return null
    }

    private fun resolveExplicitPath(
        command: String,
        isWindows: Boolean,
    ): String? {
        if (!looksLikePath(command)) return null
        val candidate = Paths.get(command)
        var resolved: String? = null
        if (isUsable(candidate, isWindows)) {
            resolved = candidate.toAbsolutePath().toString()
        } else if (isWindows && !hasExtension(command)) {
            val exts = parsePathExt(System.getenv("PATHEXT"))
            val withExt =
                exts
                    .asSequence()
                    .map { ext -> Paths.get(command + ext) }
                    .firstOrNull { isUsable(it, isWindows) }
            resolved = withExt?.toAbsolutePath()?.toString()
        }
        return resolved
    }

    private fun resolveFromPath(
        command: String,
        entries: List<String>,
        isWindows: Boolean,
    ): String? {
        val exts = if (isWindows) parsePathExt(System.getenv("PATHEXT")) else emptyList()
        val hasExt = isWindows && hasExtension(command)
        val candidates =
            if (isWindows && !hasExt) {
                entries.flatMap { entry -> buildWindowsCandidates(entry, command, exts) }
            } else {
                entries.map { entry -> Paths.get(entry, command) }
            }
        return candidates.firstOrNull { isUsable(it, isWindows) }?.toAbsolutePath()?.toString()
    }

    private fun isUsable(
        path: java.nio.file.Path,
        isWindows: Boolean,
    ): Boolean = Files.isRegularFile(path) && (isWindows || Files.isExecutable(path))

    private fun looksLikePath(command: String): Boolean = command.contains('/') || command.contains('\\')

    private fun hasExtension(command: String): Boolean {
        val base = command.substringAfterLast('/').substringAfterLast('\\')
        return base.contains('.')
    }

    private fun parsePathExt(value: String?): List<String> {
        val defaults = listOf(".EXE", ".BAT", ".CMD", ".COM")
        val parsed =
            value
                ?.split(';')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.map { if (it.startsWith(".")) it else ".$it" }
        return parsed?.takeIf { it.isNotEmpty() } ?: defaults
    }

    private fun readSystemPath(): String? = System.getenv("PATH") ?: System.getenv("Path")

    private fun buildWindowsCandidates(
        entry: String,
        command: String,
        exts: List<String>,
    ): List<java.nio.file.Path> =
        buildList {
            add(Paths.get(entry, command))
            exts.forEach { ext -> add(Paths.get(entry, command + ext)) }
        }

    private fun resolvePathEntries(
        pathOverride: String?,
        logger: Logger?,
        isWindows: Boolean,
    ): List<String>? {
        val resolvedPath =
            pathOverride?.takeIf { it.isNotBlank() }
                ?: UserPathResolver.resolve(logger)
                ?: readSystemPath()
        if (resolvedPath.isNullOrBlank()) {
            return null
        }
        val separator = if (isWindows) ';' else ':'
        return parsePathEntries(resolvedPath, separator)
    }
}
