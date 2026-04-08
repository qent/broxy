package io.qent.broxy.agents.codex.runtime

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive

internal fun JsonObjectBuilder.putAuthFileState(
    prefix: String,
    state: AuthFileState,
) {
    put("${prefix}FileSnapshot", JsonPrimitive(state.snapshot.fingerprint()))
    putAuthMetadata(prefix, state.metadata)
}

internal fun JsonObjectBuilder.putAuthMetadata(
    prefix: String,
    metadata: AuthMetadata,
) {
    put("${prefix}LastRefresh", JsonPrimitive(metadata.lastRefresh))
    metadata.accessTokenExpEpochSeconds?.let { put("${prefix}AccessTokenExpEpochSeconds", JsonPrimitive(it)) }
        ?: put("${prefix}AccessTokenExpEpochSeconds", JsonNull)
    put("${prefix}AccessTokenExpIso", JsonPrimitive(metadata.accessTokenExpIso))
    metadata.accessTokenExpired?.let { put("${prefix}AccessTokenExpired", JsonPrimitive(it)) }
        ?: put("${prefix}AccessTokenExpired", JsonNull)
}
