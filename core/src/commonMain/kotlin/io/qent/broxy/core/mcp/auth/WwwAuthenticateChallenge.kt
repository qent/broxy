package io.qent.broxy.core.mcp.auth

data class WwwAuthenticateChallenge(
    val scheme: String,
    val params: Map<String, String>,
)

fun parseWwwAuthenticateHeader(value: String): List<WwwAuthenticateChallenge> {
    val segments = splitOutsideQuotes(value, ',')
    val challenge =
        segments
            .asSequence()
            .mapNotNull { segment ->
                val trimmed = segment.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val spaceIndex = trimmed.indexOf(' ')
                if (spaceIndex <= 0) return@mapNotNull null
                val scheme = trimmed.substring(0, spaceIndex).trim()
                if (!scheme.equals("Bearer", ignoreCase = true)) return@mapNotNull null
                val params =
                    parseParams(trimmed.substring(spaceIndex + 1))
                        .filterKeys { it.isNotBlank() }
                WwwAuthenticateChallenge(scheme, params)
            }.firstOrNull()
    return challenge?.let { listOf(it) } ?: emptyList()
}

private fun parseParams(value: String): Map<String, String> {
    return splitOutsideQuotes(value, ',')
        .asSequence()
        .mapNotNull { part ->
            val trimmed = part.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val eqIndex = trimmed.indexOf('=')
            if (eqIndex <= 0) return@mapNotNull null
            val key = trimmed.substring(0, eqIndex).trim()
            val rawValue = trimmed.substring(eqIndex + 1).trim()
            key to unquote(rawValue)
        }.toMap()
}

private fun splitOutsideQuotes(
    value: String,
    delimiter: Char,
): List<String> {
    if (value.isEmpty()) return emptyList()
    val parts = mutableListOf<String>()
    val sb = StringBuilder()
    var inQuotes = false
    var escape = false
    for (c in value) {
        if (escape) {
            sb.append(c)
            escape = false
        } else if (c == '\\' && inQuotes) {
            escape = true
            sb.append(c)
        } else if (c == '"') {
            inQuotes = !inQuotes
            sb.append(c)
        } else if (c == delimiter && !inQuotes) {
            parts.add(sb.toString())
            sb.clear()
        } else {
            sb.append(c)
        }
    }
    parts.add(sb.toString())
    return parts
}

private fun unquote(value: String): String {
    val trimmed = value.trim()
    if (trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
        return trimmed.substring(1, trimmed.length - 1).replace("\\\"", "\"")
    }
    return trimmed
}
