package io.qent.broxy.core.utils

internal fun extractBetweenMarkers(
    output: String,
    start: String,
    end: String,
): String? {
    val startIndex = output.indexOf(start)
    if (startIndex < 0) return null
    val endIndex = output.indexOf(end, startIndex + start.length)
    return if (endIndex < 0) {
        null
    } else {
        output.substring(startIndex + start.length, endIndex)
    }
}

internal fun fallbackPathLine(
    output: String,
    separator: Char,
): String? {
    val lines =
        output
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
    return lines.lastOrNull { it.contains(separator) } ?: lines.lastOrNull()
}
