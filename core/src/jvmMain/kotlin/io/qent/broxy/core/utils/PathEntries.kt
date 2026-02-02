package io.qent.broxy.core.utils

internal fun parsePathEntries(
    path: String?,
    separator: Char,
): List<String> {
    if (path.isNullOrBlank()) return emptyList()
    return path
        .split(separator)
        .map { it.trim().trim('"') }
        .filter { it.isNotBlank() }
}
