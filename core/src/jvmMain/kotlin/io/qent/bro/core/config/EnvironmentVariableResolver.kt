package io.qent.bro.core.config

import io.qent.bro.core.utils.Logger
import io.qent.bro.core.utils.ConfigurationException

class EnvironmentVariableResolver(
    private val envProvider: () -> Map<String, String> = { System.getenv() },
    private val logger: Logger? = null
){
    private val pattern = Regex("\\$\\{([^}]+)\\}")

    fun resolveString(value: String): String {
        return pattern.replace(value) { m ->
            val key = m.groupValues[1]
            envProvider()[key]
                ?: throw ConfigurationException("Missing env var: $key")
        }
    }

    fun resolveMap(values: Map<String, String>): Map<String, String> = values.mapValues { (_, v) -> resolveString(v) }

    fun hasPlaceholders(value: String): Boolean = pattern.containsMatchIn(value)

    fun missingVars(value: String): List<String> = pattern.findAll(value).map { it.groupValues[1] }.filter { envProvider()[it] == null }.distinct().toList()

    fun sanitizeForLogging(values: Map<String, String>): Map<String, String> = values.mapValues { (k, v) ->
        if (k.contains("TOKEN", ignoreCase = true) || k.contains("SECRET", ignoreCase = true) || k.contains("PASSWORD", ignoreCase = true) || k.contains("KEY", ignoreCase = true)) "***" else v
    }

    fun logResolvedEnv(prefix: String, env: Map<String, String>) {
        logger?.info("$prefix env: ${sanitizeForLogging(env)}")
    }
}
