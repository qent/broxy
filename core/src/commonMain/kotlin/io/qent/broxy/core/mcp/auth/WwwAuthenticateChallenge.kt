package io.qent.broxy.core.mcp.auth

data class WwwAuthenticateChallenge(
    val scheme: String,
    val params: Map<String, String>,
)

fun parseWwwAuthenticateHeader(value: String): List<WwwAuthenticateChallenge> {
    val segments = splitOutsideQuotes(value, ',')
    for (segment in segments) {
        val trimmed = segment.trim()
        if (trimmed.isEmpty()) continue
        val spaceIndex = trimmed.indexOf(' ')
        if (spaceIndex <= 0) continue
        val scheme = trimmed.substring(0, spaceIndex).trim()
        if (!scheme.equals("Bearer", ignoreCase = true)) continue
        val params =
            parseParams(trimmed.substring(spaceIndex + 1))
                .filterKeys { it.isNotBlank() }
        return listOf(WwwAuthenticateChallenge(scheme, params))
    }
    return emptyList()
}

private fun parseParams(value: String): Map<String, String> {
    val params = mutableMapOf<String, String>()
    val parts = splitOutsideQuotes(value, ',')
    for (part in parts) {
        val trimmed = part.trim()
        if (trimmed.isEmpty()) continue
        val eqIndex = trimmed.indexOf('=')
        if (eqIndex <= 0) continue
        val key = trimmed.substring(0, eqIndex).trim()
        val rawValue = trimmed.substring(eqIndex + 1).trim()
        params[key] = unquote(rawValue)
    }
    return params
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
            continue
        }
        if (c == '\\' && inQuotes) {
            escape = true
            sb.append(c)
            continue
        }
        if (c == '"') {
            inQuotes = !inQuotes
            sb.append(c)
            continue
        }
        if (c == delimiter && !inQuotes) {
            parts.add(sb.toString())
            sb.clear()
            continue
        }
        sb.append(c)
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
