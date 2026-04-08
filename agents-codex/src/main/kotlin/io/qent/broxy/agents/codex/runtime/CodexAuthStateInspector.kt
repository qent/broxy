package io.qent.broxy.agents.codex.runtime

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

internal class CodexAuthStateInspector(
    private val json: Json = Json,
) {
    suspend fun waitForAuthFileChange(
        authFile: File,
        baseline: AuthFileSnapshot,
    ): AuthFileChangeWaitResult {
        var waitedMillis = 0L
        while (waitedMillis < CODEX_AUTH_FILE_CHANGE_WAIT_TIMEOUT_MILLIS) {
            delay(CODEX_AUTH_FILE_CHANGE_POLL_INTERVAL_MILLIS)
            waitedMillis += CODEX_AUTH_FILE_CHANGE_POLL_INTERVAL_MILLIS
            val current = readAuthFileState(authFile)
            if (current.snapshot != baseline) {
                return AuthFileChangeWaitResult(
                    state = current,
                    changed = true,
                    waited = true,
                    waitedMillis = waitedMillis,
                )
            }
        }
        return AuthFileChangeWaitResult(
            state = readAuthFileState(authFile),
            changed = false,
            waited = true,
            waitedMillis = waitedMillis,
        )
    }

    fun readAuthFileState(authFile: File): AuthFileState {
        if (!authFile.isFile) {
            return AuthFileState(
                snapshot =
                    AuthFileSnapshot(
                        exists = false,
                        sizeBytes = null,
                        lastModifiedEpochMillis = null,
                        sha256 = null,
                    ),
                metadata = AuthMetadata(),
            )
        }

        val bytes = runCatching { authFile.readBytes() }.getOrNull()
        val sha256 = bytes?.let(::sha256)
        val metadata = bytes?.let(::parseAuthMetadata) ?: AuthMetadata()
        return AuthFileState(
            snapshot =
                AuthFileSnapshot(
                    exists = true,
                    sizeBytes = authFile.length(),
                    lastModifiedEpochMillis = authFile.lastModified(),
                    sha256 = sha256,
                ),
            metadata = metadata,
        )
    }

    private fun parseAuthMetadata(bytes: ByteArray): AuthMetadata {
        val payload =
            runCatching { json.parseToJsonElement(bytes.decodeToString()).jsonObject }.getOrNull()
                ?: return AuthMetadata()
        val lastRefresh = payload["last_refresh"]?.jsonPrimitive?.contentOrNull
        val accessToken =
            payload["tokens"]
                ?.jsonObject
                ?.get("access_token")
                ?.jsonPrimitive
                ?.contentOrNull
        val expEpochSeconds = decodeJwtExpiration(accessToken)
        return AuthMetadata(
            lastRefresh = lastRefresh,
            accessTokenExpEpochSeconds = expEpochSeconds,
            accessTokenExpIso = expEpochSeconds?.let { Instant.ofEpochSecond(it).toString() },
            accessTokenExpired = expEpochSeconds?.let { it <= Instant.now().epochSecond },
        )
    }

    private fun decodeJwtExpiration(token: String?): Long? =
        token
            ?.split('.')
            ?.getOrNull(1)
            ?.let { payloadSegment ->
                runCatching { Base64.getUrlDecoder().decode(payloadSegment).decodeToString() }.getOrNull()
            }?.let { decoded ->
                runCatching {
                    val payload = json.parseToJsonElement(decoded).jsonObject
                    payload["exp"]?.jsonPrimitive?.longOrNull
                }.getOrNull()
            }

    private fun sha256(content: ByteArray): String? =
        runCatching {
            val digest = MessageDigest.getInstance("SHA-256").digest(content)
            digest.joinToString(separator = "") { chunk -> "%02x".format(chunk) }
        }.getOrNull()
}
