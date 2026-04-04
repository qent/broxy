package io.qent.broxy.core.config

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal class EnvFileLoader(
    private val errors: ConfigErrorHandler,
) {
    fun load(
        serverId: String,
        envFileValue: String,
        mcpFileDirectory: Path,
    ): Map<String, String> {
        val resolved = resolveEnvFilePath(envFileValue, mcpFileDirectory)
        val normalized = resolved.normalize()
        if (!Files.exists(normalized)) {
            errors.fail("Server '$serverId': envFile was not found at ${normalized.toAbsolutePath()}")
        }
        if (!Files.isRegularFile(normalized) || !Files.isReadable(normalized)) {
            errors.fail("Server '$serverId': envFile is not readable at ${normalized.toAbsolutePath()}")
        }
        val content =
            try {
                Files.readString(normalized)
            } catch (e: IOException) {
                errors.fail(
                    "Server '$serverId': failed to read envFile '${normalized.toAbsolutePath()}': ${e.message}",
                    e,
                )
            }
        return parse(content, serverId, normalized)
    }

    private fun resolveEnvFilePath(
        envFileValue: String,
        mcpFileDirectory: Path,
    ): Path {
        val expanded = expandHomePath(envFileValue.trim())
        val path = Paths.get(expanded)
        return if (path.isAbsolute) path else mcpFileDirectory.resolve(path)
    }

    private fun parse(
        content: String,
        serverId: String,
        envFile: Path,
    ): Map<String, String> {
        val result = linkedMapOf<String, String>()
        content.lines().forEachIndexed { idx, rawLine ->
            val lineNumber = idx + 1
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachIndexed

            val assignment = trimmed.removePrefix("export ").trimStart()
            val equalIndex = assignment.indexOf('=')
            if (equalIndex <= 0) {
                errors.fail(
                    "Server '$serverId': invalid envFile entry at ${envFile.toAbsolutePath()}:$lineNumber",
                )
            }

            val key = assignment.substring(0, equalIndex).trim()
            if (key.isEmpty()) {
                errors.fail(
                    "Server '$serverId': invalid envFile key at ${envFile.toAbsolutePath()}:$lineNumber",
                )
            }
            val rawValue = assignment.substring(equalIndex + 1).trim()
            result[key] = unquote(rawValue)
        }
        return result
    }

    private fun unquote(value: String): String {
        if (value.length < 2) {
            return value
        }
        return when {
            isWrappedBy(value, '"') || isWrappedBy(value, '\'') -> value.substring(1, value.length - 1)
            else -> value
        }
    }

    private fun isWrappedBy(
        value: String,
        quote: Char,
    ): Boolean = value.first() == quote && value.last() == quote

    private fun expandHomePath(path: String): String {
        val home = System.getProperty("user.home") ?: return path
        return when {
            path == "~" -> home
            path.startsWith("~/") -> Paths.get(home).resolve(path.removePrefix("~/")).toString()
            path.startsWith("~\\") -> Paths.get(home).resolve(path.removePrefix("~\\")).toString()
            else -> path
        }
    }
}
