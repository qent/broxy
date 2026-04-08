package io.qent.broxy.agents.codex.runtime

import kotlinx.serialization.json.JsonObject

internal data class ParsedCodexEvent(
    val type: String,
    val item: JsonObject?,
    val errorMessage: String?,
)

internal data class CodexAttemptResult(
    val response: String,
    val stepCount: Int,
)

internal data class CodexPreflightResult(
    val versionExitCode: Int,
    val versionOutput: String,
    val loginStatusExitCode: Int,
    val loginStatusOutput: String,
    val sessionReady: Boolean,
)

internal data class CommandProbeResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    fun combinedOutput(): String =
        listOf(stdout.trim(), stderr.trim())
            .filter { it.isNotBlank() }
            .joinToString(separator = "\n")
            .ifBlank { "(no output)" }
}

internal data class AuthFileState(
    val snapshot: AuthFileSnapshot,
    val metadata: AuthMetadata,
)

internal data class AuthFileSnapshot(
    val exists: Boolean,
    val sizeBytes: Long?,
    val lastModifiedEpochMillis: Long?,
    val sha256: String?,
) {
    fun fingerprint(): String =
        if (!exists) {
            "missing"
        } else {
            "size=${sizeBytes ?: -1},mtime=${lastModifiedEpochMillis ?: -1},sha256=${sha256 ?: "unknown"}"
        }
}

internal data class AuthMetadata(
    val lastRefresh: String? = null,
    val accessTokenExpEpochSeconds: Long? = null,
    val accessTokenExpIso: String? = null,
    val accessTokenExpired: Boolean? = null,
)

internal data class AuthFileChangeWaitResult(
    val state: AuthFileState,
    val changed: Boolean,
    val waited: Boolean,
    val waitedMillis: Long,
)

internal class CodexPreflightException(
    message: String,
) : IllegalStateException(message)

internal class CodexRefreshTokenReusedException(
    message: String,
    cause: Throwable,
) : IllegalStateException(message, cause)

internal fun String.abbreviate(maxLength: Int = 320): String {
    val normalized = trim().replace('\n', ' ')
    return if (normalized.length <= maxLength) normalized else normalized.take(maxLength - ELLIPSIS_SIZE) + "..."
}

private const val ELLIPSIS_SIZE = 3
