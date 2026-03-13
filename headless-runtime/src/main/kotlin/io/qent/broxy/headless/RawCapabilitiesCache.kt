package io.qent.broxy.headless

import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Base64

@Serializable
data class RawCapabilitiesCacheEntry(
    val serverId: String,
    val timestampMillis: Long,
    val capabilities: ServerCapabilities,
)

class RawCapabilitiesCache(
    baseDir: Path,
    private val logger: Logger = ConsoleLogger,
    private val json: Json = defaultJson,
) {
    private val root = baseDir.resolve("capabilities_raw")
    private val lock = Any()

    fun loadAll(): List<RawCapabilitiesCacheEntry> {
        if (!Files.isDirectory(root)) return emptyList()
        val entries = mutableListOf<RawCapabilitiesCacheEntry>()
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

    fun saveSnapshot(
        snapshot: Map<String, ServerCapabilities>,
        timestampMillis: Long,
    ) {
        synchronized(lock) {
            runCatching {
                Files.createDirectories(root)
                val validFiles = mutableSetOf<String>()
                snapshot.forEach { (serverId, capabilities) ->
                    val entry =
                        RawCapabilitiesCacheEntry(
                            serverId = serverId,
                            timestampMillis = timestampMillis,
                            capabilities = capabilities,
                        )
                    val fileName = fileName(serverId)
                    validFiles += fileName
                    val path = root.resolve(fileName)
                    writeAtomically(path, json.encodeToString(entry))
                }
                retainInternal(validFiles)
            }.onFailure { ex ->
                logger.warn("Failed to persist raw capabilities cache: ${ex.message}")
            }
        }
    }

    fun retain(validIds: Set<String>) {
        val validFiles = validIds.mapTo(mutableSetOf()) { fileName(it) }
        synchronized(lock) {
            runCatching {
                if (!Files.isDirectory(root)) return@runCatching
                retainInternal(validFiles)
            }.onFailure { ex ->
                logger.warn("Failed to prune raw capabilities cache: ${ex.message}")
            }
        }
    }

    private fun retainInternal(validFiles: Set<String>) {
        if (!Files.isDirectory(root)) return
        directoryStream(root).use { stream ->
            stream.forEach { path ->
                val name = path.fileName.toString()
                if (!name.startsWith(FILE_PREFIX) || !name.endsWith(FILE_SUFFIX)) return@forEach
                if (validFiles.isEmpty() || name !in validFiles) {
                    Files.deleteIfExists(path)
                }
            }
        }
    }

    private fun readEntry(path: Path): RawCapabilitiesCacheEntry? =
        runCatching {
            val payload = Files.readString(path)
            json.decodeFromString<RawCapabilitiesCacheEntry>(payload)
        }.onFailure { ex ->
            logger.warn("Failed to decode raw capabilities cache entry '${path.fileName}': ${ex.message}")
        }.getOrNull()

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
