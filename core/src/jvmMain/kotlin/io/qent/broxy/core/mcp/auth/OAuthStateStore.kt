package io.qent.broxy.core.mcp.auth

import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest

class OAuthStateStore private constructor(
    baseDir: Path,
    private val logger: Logger,
    private val json: Json,
    private val secureStorage: SecureStorage,
) {
    constructor(
        baseDir: Path = Paths.get(System.getProperty("user.home"), ".config", "broxy"),
        logger: Logger = ConsoleLogger,
        json: Json =
            Json {
                ignoreUnknownKeys = true
            },
    ) : this(
        baseDir = baseDir,
        logger = logger,
        json = json,
        secureStorage = SecureStorageFactory.create(buildServiceName(baseDir), logger),
    )

    private val lock = Any()
    private val cached = mutableMapOf<String, OAuthStateSnapshot>()
    private val missing = mutableSetOf<String>()
    private val legacyFile: Path = baseDir.resolve("oauth_cache.json")

    init {
        if (Files.exists(legacyFile)) {
            logger.warn(
                "Legacy OAuth cache file found at ${legacyFile.toAbsolutePath()}. " +
                    "Broxy now stores OAuth data in the system secure storage.",
            )
        }
    }

    fun load(
        serverId: String,
        resourceUrl: String?,
    ): OAuthStateSnapshot? =
        synchronized(lock) {
            val snapshot = loadSnapshot(serverId) ?: return null
            if (!matchesResource(snapshot, resourceUrl)) return null
            snapshot
        }

    fun save(
        serverId: String,
        snapshot: OAuthStateSnapshot,
    ) = synchronized(lock) {
        if (!hasUsefulData(snapshot)) {
            remove(serverId)
            return@synchronized
        }
        val existing = cached[serverId] ?: loadSnapshot(serverId)
        if (existing == snapshot) return@synchronized
        val serialized = json.encodeToString(OAuthStateSnapshot.serializer(), snapshot)
        secureStorage.write(keyFor(serverId), serialized)
        cached[serverId] = snapshot
        missing.remove(serverId)
    }

    fun remove(serverId: String) {
        synchronized(lock) {
            secureStorage.delete(keyFor(serverId))
            cached.remove(serverId)
            missing.remove(serverId)
        }
    }

    private fun loadSnapshot(serverId: String): OAuthStateSnapshot? {
        var snapshot = cached[serverId]
        if (snapshot == null && !missing.contains(serverId)) {
            val serialized = secureStorage.read(keyFor(serverId))
            if (serialized == null) {
                missing.add(serverId)
            } else {
                snapshot =
                    runCatching { json.decodeFromString(OAuthStateSnapshot.serializer(), serialized) }
                        .onFailure {
                            logger.warn(
                                "Failed to decode OAuth cache entry for '$serverId' " +
                                    "(${it::class.simpleName}). Clearing cached entry.",
                            )
                            secureStorage.delete(keyFor(serverId))
                            missing.add(serverId)
                        }.getOrNull()
                if (snapshot != null) {
                    cached[serverId] = snapshot
                    missing.remove(serverId)
                }
            }
        }
        return snapshot
    }

    private fun matchesResource(
        snapshot: OAuthStateSnapshot,
        resourceUrl: String?,
    ): Boolean {
        val cachedResource = snapshot.resourceUrl?.takeIf { it.isNotBlank() }
        val current = resourceUrl?.takeIf { it.isNotBlank() }
        return if (cachedResource == null) {
            true
        } else {
            cachedResource == current
        }
    }

    private fun hasUsefulData(snapshot: OAuthStateSnapshot): Boolean =
        snapshot.token != null ||
            snapshot.registration != null ||
            snapshot.resourceMetadata != null ||
            snapshot.authorizationMetadata != null ||
            !snapshot.registeredRedirectUri.isNullOrBlank() ||
            !snapshot.resourceMetadataUrl.isNullOrBlank() ||
            !snapshot.authorizationServer.isNullOrBlank() ||
            !snapshot.lastRequestedScope.isNullOrBlank()

    private fun keyFor(serverId: String): String = serverId
}

private fun buildServiceName(baseDir: Path): String {
    val normalized = baseDir.toAbsolutePath().normalize().toString()
    val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(StandardCharsets.UTF_8))
    val hex = digest.joinToString(separator = "") { "%02x".format(it) }
    return "broxy.oauth.$hex"
}
