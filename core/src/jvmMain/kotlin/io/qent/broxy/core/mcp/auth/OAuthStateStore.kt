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
                prettyPrint = true
            },
    ) : this(
        baseDir = baseDir,
        logger = logger,
        json = json,
        secureStorage = SecureStorageFactory.create(buildServiceName(baseDir), logger),
    )

    companion object {
        internal fun forTesting(
            baseDir: Path = Paths.get(System.getProperty("user.home"), ".config", "broxy"),
            logger: Logger = ConsoleLogger,
            json: Json =
                Json {
                    ignoreUnknownKeys = true
                    prettyPrint = true
                },
            secureStorage: SecureStorage,
        ): OAuthStateStore =
            OAuthStateStore(
                baseDir = baseDir,
                logger = logger,
                json = json,
                secureStorage = secureStorage,
            )
    }

    private val lock = Any()
    private val cached = mutableMapOf<String, OAuthStateSnapshot>()
    private val missing = mutableSetOf<String>()
    private val legacyFile: Path = baseDir.resolve("oauth_cache.json")

    init {
        if (Files.exists(legacyFile)) {
            logger.warn(
                "Legacy OAuth cache file found at ${legacyFile.toAbsolutePath()}. " +
                    "broxy now stores OAuth data in the system secure storage.",
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
        cached[serverId]?.let { return it }
        if (missing.contains(serverId)) return null
        val serialized =
            secureStorage.read(keyFor(serverId)) ?: run {
                missing.add(serverId)
                return null
            }
        val snapshot =
            try {
                json.decodeFromString(OAuthStateSnapshot.serializer(), serialized)
            } catch (ex: Exception) {
                logger.warn("Failed to decode OAuth cache entry for '$serverId': ${ex.message}", ex)
                missing.add(serverId)
                return null
            }
        cached[serverId] = snapshot
        missing.remove(serverId)
        return snapshot
    }

    private fun matchesResource(
        snapshot: OAuthStateSnapshot,
        resourceUrl: String?,
    ): Boolean {
        val cachedResource = snapshot.resourceUrl?.takeIf { it.isNotBlank() } ?: return true
        val current = resourceUrl?.takeIf { it.isNotBlank() } ?: return false
        return cachedResource == current
    }

    private fun hasUsefulData(snapshot: OAuthStateSnapshot): Boolean {
        if (snapshot.token != null || snapshot.registration != null || snapshot.resourceMetadata != null) return true
        if (snapshot.authorizationMetadata != null) return true
        if (!snapshot.registeredRedirectUri.isNullOrBlank()) return true
        if (!snapshot.resourceMetadataUrl.isNullOrBlank()) return true
        if (!snapshot.authorizationServer.isNullOrBlank()) return true
        if (!snapshot.lastRequestedScope.isNullOrBlank()) return true
        return false
    }

    private fun keyFor(serverId: String): String = serverId
}

private fun buildServiceName(baseDir: Path): String {
    val normalized = baseDir.toAbsolutePath().normalize().toString()
    val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(StandardCharsets.UTF_8))
    val hex = digest.joinToString(separator = "") { "%02x".format(it) }
    return "broxy.oauth.$hex"
}
