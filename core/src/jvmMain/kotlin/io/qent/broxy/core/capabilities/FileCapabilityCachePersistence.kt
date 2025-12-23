package io.qent.broxy.core.capabilities

import io.qent.broxy.core.utils.Logger
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Base64

class FileCapabilityCachePersistence(
    baseDir: Path,
    private val logger: Logger? = null,
    private val json: Json = defaultJson,
) : CapabilityCachePersistence {
    private val root = baseDir.resolve("capabilities")
    private val lock = Any()

    override fun loadAll(): List<CapabilityCacheEntry> {
        if (!Files.isDirectory(root)) return emptyList()
        val entries = mutableListOf<CapabilityCacheEntry>()
        synchronized(lock) {
            directoryStream(root).use { stream ->
                stream.forEach { path ->
                    val name = path.fileName.toString()
                    if (!name.startsWith(FILE_PREFIX) || !name.endsWith(FILE_SUFFIX)) return@forEach
                    val entry = readEntry(path) ?: return@forEach
                    entries += entry
                }
            }
        }
        return entries
    }

    override fun save(entry: CapabilityCacheEntry) {
        val payload = json.encodeToString(entry)
        synchronized(lock) {
            runCatching {
                Files.createDirectories(root)
                val path = root.resolve(fileName(entry.serverId))
                writeAtomically(path, payload)
            }.onFailure { ex ->
                logger?.warn("Failed to persist capability cache for '${entry.serverId}': ${ex.message}")
            }
        }
    }

    override fun remove(serverId: String) {
        synchronized(lock) {
            runCatching {
                val path = root.resolve(fileName(serverId))
                Files.deleteIfExists(path)
            }.onFailure { ex ->
                logger?.warn("Failed to remove capability cache for '$serverId': ${ex.message}")
            }
        }
    }

    override fun retain(validIds: Set<String>) {
        val validFiles = validIds.mapTo(mutableSetOf()) { fileName(it) }
        synchronized(lock) {
            runCatching {
                if (!Files.isDirectory(root)) return@runCatching
                directoryStream(root).use { stream ->
                    stream.forEach { path ->
                        val name = path.fileName.toString()
                        if (!name.startsWith(FILE_PREFIX) || !name.endsWith(FILE_SUFFIX)) return@forEach
                        if (validIds.isEmpty() || name !in validFiles) {
                            Files.deleteIfExists(path)
                        }
                    }
                }
            }.onFailure { ex ->
                logger?.warn("Failed to prune capability cache: ${ex.message}")
            }
        }
    }

    private fun readEntry(path: Path): CapabilityCacheEntry? {
        return runCatching {
            val payload = Files.readString(path)
            json.decodeFromString<CapabilityCacheEntry>(payload)
        }.onFailure { ex ->
            logger?.warn("Failed to decode capability cache entry '${path.fileName}': ${ex.message}")
        }.getOrNull()
    }

    private fun writeAtomically(
        path: Path,
        payload: String,
    ) {
        val tmp = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(tmp, payload, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun fileName(serverId: String): String = "${FILE_PREFIX}${encode(serverId)}${FILE_SUFFIX}"

    private fun encode(serverId: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(serverId.toByteArray(Charsets.UTF_8))

    private fun directoryStream(path: Path): DirectoryStream<Path> = Files.newDirectoryStream(path)

    private companion object {
        private const val FILE_PREFIX = "caps_"
        private const val FILE_SUFFIX = ".json"

        private val defaultJson =
            Json {
                encodeDefaults = true
                ignoreUnknownKeys = true
            }
    }
}
