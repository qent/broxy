package io.qent.broxy.core.mcp.auth

import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists

class OAuthStateStore(
    baseDir: Path = Paths.get(System.getProperty("user.home"), ".config", "broxy"),
    private val logger: Logger = ConsoleLogger,
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        },
) {
    @Serializable
    private data class CacheFile(
        val servers: Map<String, OAuthStateSnapshot> = emptyMap(),
    )

    private val file: Path = baseDir.resolve("oauth_cache.json")
    private val lock = Any()
    private var cached: MutableMap<String, OAuthStateSnapshot>? = null

    fun load(
        serverId: String,
        resourceUrl: String?,
    ): OAuthStateSnapshot? =
        synchronized(lock) {
            val cache = loadCacheLocked()
            val snapshot = cache[serverId] ?: return null
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
        val cache = loadCacheLocked()
        val existing = cache[serverId]
        if (existing == snapshot) return@synchronized
        cache[serverId] = snapshot
        persistLocked(cache)
    }

    fun remove(serverId: String) {
        synchronized(lock) {
            val cache = loadCacheLocked()
            if (cache.remove(serverId) != null) {
                persistLocked(cache)
            }
        }
    }

    private fun loadCacheLocked(): MutableMap<String, OAuthStateSnapshot> {
        val existing = cached
        if (existing != null) return existing
        val loaded =
            if (!file.exists()) {
                mutableMapOf()
            } else {
                try {
                    val text = Files.readString(file)
                    json.decodeFromString(CacheFile.serializer(), text).servers.toMutableMap()
                } catch (ex: Exception) {
                    logger.warn("Failed to load OAuth cache at ${file.toAbsolutePath()}: ${ex.message}", ex)
                    mutableMapOf()
                }
            }
        cached = loaded
        return loaded
    }

    private fun persistLocked(cache: Map<String, OAuthStateSnapshot>) {
        try {
            val parent = file.parent
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent)
            }
            val content = json.encodeToString(CacheFile.serializer(), CacheFile(servers = cache))
            Files.writeString(
                file,
                content,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
        } catch (ex: IOException) {
            logger.warn("Failed to persist OAuth cache to ${file.toAbsolutePath()}: ${ex.message}", ex)
        }
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
}
