package io.qent.broxy.core.config

import io.qent.broxy.core.utils.ConfigurationException
import io.qent.broxy.core.utils.Logger

class EnvironmentVariableResolver(
    private val envProvider: () -> Map<String, String> = { System.getenv() },
    private val logger: Logger? = null,
) {
    private val pattern = Regex("\\$\\{([^}]+)\\}|\\{([^}]+)\\}")

    fun resolveString(value: String): String =
        pattern.replace(value) { m ->
            val key =
                m.groups[1]?.value ?: m.groups[2]?.value
                    ?: throw ConfigurationException("Missing env var placeholder in '$value'")
            envProvider()[key]
                ?: throw ConfigurationException("Missing env var: $key")
        }

    fun resolveMap(values: Map<String, String>): Map<String, String> = values.mapValues { (_, v) -> resolveString(v) }

    fun hasPlaceholders(value: String): Boolean = pattern.containsMatchIn(value)

    fun missingVars(value: String): List<String> =
        pattern
            .findAll(value)
            .mapNotNull { match -> match.groups[1]?.value ?: match.groups[2]?.value }
            .filter { envProvider()[it] == null }
            .distinct()
            .toList()

    fun sanitizeForLogging(values: Map<String, String>): Map<String, String> =
        values.mapValues { (k, v) ->
            if (shouldRedactKey(k)) {
                "***"
            } else {
                v
            }
        }

    private fun shouldRedactKey(key: String): Boolean {
        val upper = key.uppercase()
        return upper.contains("TOKEN") ||
            upper.contains("SECRET") ||
            upper.contains("PASSWORD") ||
            upper.contains("KEY")
    }

    fun logResolvedEnv(
        prefix: String,
        env: Map<String, String>,
    ) {
        logger?.info("$prefix env: ${sanitizeForLogging(env)}")
    }
}
