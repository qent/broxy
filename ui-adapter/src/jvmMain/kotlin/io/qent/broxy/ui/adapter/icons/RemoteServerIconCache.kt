package io.qent.broxy.ui.adapter.icons

import io.qent.broxy.core.utils.AppCacheDir
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

private const val REMOTE_ICON_CACHE_DIR_NAME = "server-icons"
private const val REMOTE_ICON_CONNECT_TIMEOUT_MS = 5_000
private const val REMOTE_ICON_READ_TIMEOUT_MS = 10_000

private val KNOWN_IMAGE_EXTENSIONS =
    setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "tif", "tiff", "ico", "svg", "avif")

class RemoteServerIconCache(
    private val cacheDir: Path = AppCacheDir.resolve().resolve(REMOTE_ICON_CACHE_DIR_NAME),
    private val downloader: (String) -> ByteArray? = ::downloadRemoteIconBytes,
) {
    private val lock = Any()

    fun load(url: String): ByteArray? {
        val normalizedUrl = url.trim()
        if (normalizedUrl.isEmpty()) return null
        val cacheFile = cacheFileFor(normalizedUrl)
        if (cacheFile == null) {
            return downloader(normalizedUrl)
        }

        synchronized(lock) {
            readCachedBytes(cacheFile)?.let { return it }

            val downloaded = downloader(normalizedUrl) ?: return null
            writeCachedBytes(cacheFile, downloaded)
            return downloaded
        }
    }

    internal fun cacheFileFor(url: String): Path? {
        val parsed = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = parsed.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val extension = resolveCacheExtension(parsed.path.orEmpty())
        return cacheDir.resolve("${sha256(url)}$extension")
    }

    private fun readCachedBytes(file: Path): ByteArray? {
        if (!file.exists() || !file.isRegularFile()) return null
        return runCatching { Files.readAllBytes(file) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun writeCachedBytes(
        file: Path,
        payload: ByteArray,
    ) {
        if (payload.isEmpty()) return
        runCatching {
            cacheDir.createDirectories()
            val tempFile = file.resolveSibling("${file.fileName}.tmp")
            Files.write(
                tempFile,
                payload,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            try {
                Files.move(
                    tempFile,
                    file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: Exception) {
                Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}

private fun resolveCacheExtension(path: String): String {
    val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val normalized = if (ext in KNOWN_IMAGE_EXTENSIONS) ext else "img"
    return ".$normalized"
}

private fun sha256(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private fun downloadRemoteIconBytes(url: String): ByteArray? =
    runCatching {
        val connection =
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = REMOTE_ICON_CONNECT_TIMEOUT_MS
                readTimeout = REMOTE_ICON_READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "image/*,*/*;q=0.8")
                instanceFollowRedirects = true
            }
        try {
            if (connection.responseCode !in 200..299) {
                return@runCatching null
            }
            connection.inputStream.use { stream -> stream.readBytes().takeIf { it.isNotEmpty() } }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
