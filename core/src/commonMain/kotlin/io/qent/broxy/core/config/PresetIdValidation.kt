package io.qent.broxy.core.config

import io.qent.broxy.core.utils.ConfigurationException

fun isPathSafePresetId(id: String): Boolean {
    val hasValidText = id.isNotBlank() && id == id.trim()
    val isReservedPath = id == "." || id == ".."
    val hasTraversal = id.contains("..")
    val hasPathSeparators = id.contains('/') || id.contains('\\')
    val hasNull = id.contains('\u0000')
    return hasValidText && !isReservedPath && !hasTraversal && !hasPathSeparators && !hasNull
}

fun requirePathSafePresetId(id: String) {
    require(isPathSafePresetId(id)) {
        "Preset id '$id' is not path-safe"
    }
}

fun validatePathSafePresetId(id: String): Result<Unit> =
    runCatching {
        if (!isPathSafePresetId(id)) {
            throw ConfigurationException("Preset id '$id' is not path-safe")
        }
    }
