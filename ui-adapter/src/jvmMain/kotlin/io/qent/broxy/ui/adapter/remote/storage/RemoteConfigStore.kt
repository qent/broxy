package io.qent.broxy.ui.adapter.remote.storage

import io.qent.broxy.ui.adapter.models.UiRemoteConnectionState
import io.qent.broxy.ui.adapter.models.UiRemoteStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

@Serializable
data class PersistedRemoteConfig(
    val serverIdentifier: String,
    val email: String? = null,
    val accessTokenExpiresAt: String? = null,
    val wsTokenExpiresAt: String? = null,
)

data class LoadedRemoteConfig(
    val serverIdentifier: String,
    val email: String?,
    val accessToken: String?,
    val accessTokenExpiresAt: Instant?,
    val wsToken: String?,
    val wsTokenExpiresAt: Instant?,
) {
    private fun hasCredentials(): Boolean = !accessToken.isNullOrBlank() || !wsToken.isNullOrBlank()

    fun toUi(): UiRemoteConnectionState =
        UiRemoteConnectionState(
            serverIdentifier = serverIdentifier,
            email = email,
            hasCredentials = hasCredentials(),
            status = if (hasCredentials()) UiRemoteStatus.Registered else UiRemoteStatus.NotAuthorized,
            message = null,
        )
}

class RemoteConfigStore(
    private val file: Path,
    private val secureStore: SecureStore,
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        },
) {
    companion object {
        private const val ACCESS_TOKEN_KEY = "remote.access_token"
        private const val WS_TOKEN_KEY = "remote.ws_token"
    }

    fun load(): LoadedRemoteConfig? {
        if (!Files.exists(file)) return null
        val data =
            runCatching { json.decodeFromString<PersistedRemoteConfig>(Files.readString(file)) }.getOrNull()
                ?: return null
        val accessToken = secureStore.read(ACCESS_TOKEN_KEY)
        val wsToken = secureStore.read(WS_TOKEN_KEY)
        return LoadedRemoteConfig(
            serverIdentifier = data.serverIdentifier,
            email = data.email,
            accessToken = accessToken,
            accessTokenExpiresAt = data.accessTokenExpiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
            wsToken = wsToken,
            wsTokenExpiresAt = data.wsTokenExpiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
        )
    }

    fun save(
        serverIdentifier: String,
        email: String?,
        accessToken: String?,
        accessTokenExpiresAt: Instant?,
        wsToken: String?,
        wsTokenExpiresAt: Instant?,
    ) {
        runCatching { Files.createDirectories(file.parent) }
        val payload =
            PersistedRemoteConfig(
                serverIdentifier = serverIdentifier,
                email = email,
                accessTokenExpiresAt = accessTokenExpiresAt?.toString(),
                wsTokenExpiresAt = wsTokenExpiresAt?.toString(),
            )
        runCatching { Files.writeString(file, json.encodeToString(payload)) }
        if (!accessToken.isNullOrBlank()) {
            secureStore.write(ACCESS_TOKEN_KEY, accessToken)
        }
        if (!wsToken.isNullOrBlank()) {
            secureStore.write(WS_TOKEN_KEY, wsToken)
        }
    }

    fun clear() {
        secureStore.clear(ACCESS_TOKEN_KEY)
        secureStore.clear(WS_TOKEN_KEY)
        runCatching { Files.deleteIfExists(file) }
    }
}
