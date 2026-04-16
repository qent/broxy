package io.qent.broxy.core.config

import io.qent.broxy.core.utils.ConfigurationException
import io.qent.broxy.core.utils.Logger
import java.nio.file.FileSystems
import java.nio.file.Path

private const val GROUP_ENV = 1
private const val GROUP_DOLLAR = 2
private const val GROUP_BRACES = 3
private const val DEFAULT_MARKER = ":-"
private val PLACEHOLDER_PATTERN = Regex("\\$\\{env:([^}]+)\\}|\\$\\{([^}]+)\\}|\\{([^}]+)\\}")
private val UNRESOLVED_CURSOR_PLACEHOLDERS =
    setOf(
        "cwd",
        "file",
        "relativeFile",
        "relativeFileDirname",
        "fileBasename",
        "fileBasenameNoExtension",
        "fileDirname",
        "fileExtname",
        "lineNumber",
        "selectedText",
        "execPath",
    )

class EnvironmentVariableResolver(
    private val envProvider: () -> Map<String, String> = { System.getenv() },
    private val logger: Logger? = null,
) {
    data class ResolutionContext(
        val workspaceFolder: Path? = null,
        val userHome: String? = System.getProperty("user.home"),
        val pathSeparator: String = FileSystems.getDefault().separator,
    )

    fun resolveString(
        value: String,
        context: ResolutionContext = ResolutionContext(),
    ): String {
        val env = envProvider()
        return PLACEHOLDER_PATTERN.replace(value) { match ->
            val resolved = resolvePlaceholderMatch(match, value, env, context)
            resolved ?: match.value
        }
    }

    fun resolveMap(
        values: Map<String, String>,
        context: ResolutionContext = ResolutionContext(),
    ): Map<String, String> = values.mapValues { (_, v) -> resolveString(v, context) }

    fun hasPlaceholders(value: String): Boolean = PLACEHOLDER_PATTERN.containsMatchIn(value)

    fun missingVars(value: String): List<String> {
        val env = envProvider()
        return PLACEHOLDER_PATTERN
            .findAll(value)
            .mapNotNull { match -> missingPlaceholderMatch(match, env) }
            .distinct()
            .toList()
    }

    fun sanitizeForLogging(values: Map<String, String>): Map<String, String> =
        values.mapValues { (key, resolvedValue) ->
            if (shouldRedactKey(key)) {
                "***"
            } else {
                resolvedValue
            }
        }

    fun logResolvedEnv(
        prefix: String,
        env: Map<String, String>,
    ) {
        logger?.info("$prefix env: ${sanitizeForLogging(env)}")
    }
}

@Suppress("ReturnCount", "ThrowsCount")
private fun resolvePlaceholderMatch(
    match: MatchResult,
    rawValue: String,
    env: Map<String, String>,
    context: EnvironmentVariableResolver.ResolutionContext,
): String? {
    val envToken = match.groups[GROUP_ENV]?.value?.trim()
    if (envToken != null) {
        if (envToken.isBlank()) {
            throw ConfigurationException("Missing env var placeholder name")
        }
        return env[envToken] ?: throw ConfigurationException("Missing env var: $envToken")
    }

    val dollarToken = match.groups[GROUP_DOLLAR]?.value?.trim()
    if (dollarToken != null) {
        return resolveDollarValue(dollarToken, env, context)
    }

    val braceToken = match.groups[GROUP_BRACES]?.value?.trim()
    if (braceToken != null) {
        if (braceToken.isBlank() || braceToken.contains(':') || braceToken in UNRESOLVED_CURSOR_PLACEHOLDERS) {
            return null
        }
        return env[braceToken] ?: throw ConfigurationException("Missing env var: $braceToken")
    }

    throw ConfigurationException("Missing env var placeholder in '$rawValue'")
}

@Suppress("CyclomaticComplexMethod", "ThrowsCount")
private fun resolveDollarValue(
    key: String,
    env: Map<String, String>,
    context: EnvironmentVariableResolver.ResolutionContext,
): String? {
    val trimmed = key.trim()
    val defaultMarkerIndex = trimmed.indexOf(DEFAULT_MARKER)
    val defaultKey = if (defaultMarkerIndex > 0) trimmed.substring(0, defaultMarkerIndex).trim() else ""
    val hasDefault = defaultMarkerIndex > 0 && defaultKey.isNotBlank()
    val defaultValue = if (hasDefault) trimmed.substring(defaultMarkerIndex + DEFAULT_MARKER.length) else ""

    return when {
        trimmed.isBlank() -> null
        trimmed == "workspaceFolder" -> context.workspaceFolder?.toString()
        trimmed == "workspaceFolderBasename" -> context.workspaceFolder?.fileName?.toString()
        trimmed == "userHome" -> context.userHome
        trimmed == "pathSeparator" || trimmed == "/" -> context.pathSeparator
        trimmed.startsWith("input:") -> {
            val inputName = trimmed.removePrefix("input:").trim()
            if (inputName.isBlank()) {
                throw ConfigurationException("Missing input placeholder name in '\${input:...}'")
            }
            val fallback = inputName.replace('-', '_').uppercase()
            env[inputName] ?: env[fallback]
                ?: throw ConfigurationException("Missing env var for input placeholder: $inputName")
        }

        trimmed in UNRESOLVED_CURSOR_PLACEHOLDERS -> null
        hasDefault -> env[defaultKey]?.takeIf { it.isNotEmpty() } ?: defaultValue
        trimmed.contains(':') -> null
        else -> env[trimmed] ?: throw ConfigurationException("Missing env var: $trimmed")
    }
}

@Suppress("ReturnCount")
private fun missingPlaceholderMatch(
    match: MatchResult,
    env: Map<String, String>,
): String? {
    val envToken = match.groups[GROUP_ENV]?.value?.trim()
    if (envToken != null) {
        if (envToken.isBlank()) {
            return null
        }
        return if (env[envToken] == null) envToken else null
    }

    val dollarToken = match.groups[GROUP_DOLLAR]?.value?.trim()
    if (dollarToken != null) {
        return missingDollarKey(dollarToken, env)
    }

    val braceToken = match.groups[GROUP_BRACES]?.value?.trim()
    if (braceToken != null) {
        if (braceToken.isBlank() || braceToken.contains(':') || braceToken in UNRESOLVED_CURSOR_PLACEHOLDERS) {
            return null
        }
        return if (env[braceToken] == null) braceToken else null
    }

    return null
}

@Suppress("CyclomaticComplexMethod")
private fun missingDollarKey(
    key: String,
    env: Map<String, String>,
): String? {
    val trimmed = key.trim()
    val defaultMarkerIndex = trimmed.indexOf(DEFAULT_MARKER)
    val defaultKey = if (defaultMarkerIndex > 0) trimmed.substring(0, defaultMarkerIndex).trim() else ""
    val hasDefault = defaultMarkerIndex > 0 && defaultKey.isNotBlank()

    return when {
        trimmed.isBlank() -> null
        trimmed == "workspaceFolder" || trimmed == "workspaceFolderBasename" -> null
        trimmed == "userHome" || trimmed == "pathSeparator" || trimmed == "/" -> null
        trimmed in UNRESOLVED_CURSOR_PLACEHOLDERS -> null
        trimmed.startsWith("input:") -> {
            val inputName = trimmed.removePrefix("input:").trim()
            if (inputName.isBlank()) {
                null
            } else {
                val fallback = inputName.replace('-', '_').uppercase()
                if (env[inputName] == null && env[fallback] == null) inputName else null
            }
        }

        hasDefault -> null
        trimmed.contains(':') -> null
        else -> if (env[trimmed] == null) trimmed else null
    }
}

private fun shouldRedactKey(key: String): Boolean {
    val upper = key.uppercase()
    return upper.contains("TOKEN") ||
        upper.contains("SECRET") ||
        upper.contains("PASSWORD") ||
        upper.contains("KEY")
}
