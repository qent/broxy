package io.qent.broxy.agents

import java.nio.file.Path
import java.nio.file.Paths

fun AgentProviderSettings.resolveAgentsDirectory(defaultPath: Path): Path {
    val configured = agentsDirectoryPath?.trim().orEmpty()
    val normalizedDefault = defaultPath.toAbsolutePath().normalize()
    val userHome = System.getProperty("user.home").orEmpty().trim()
    val expanded =
        when {
            configured.isBlank() -> null
            configured == "~" && userHome.isNotBlank() -> userHome
            configured.startsWith("~/") && userHome.isNotBlank() -> userHome + configured.removePrefix("~")
            else -> configured.takeIf { it.isNotBlank() }
        }
    val parsed =
        expanded?.let { value ->
            runCatching { Paths.get(value) }.getOrNull()
        }
    val resolved =
        if (parsed == null) {
            normalizedDefault
        } else if (parsed.isAbsolute) {
            parsed
        } else {
            normalizedDefault.parent?.resolve(parsed)
                ?: parsed.toAbsolutePath()
        }
    return resolved.normalize()
}
