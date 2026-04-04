package io.qent.broxy.registry.data

import io.qent.broxy.registry.catalog.CatalogBundle
import io.qent.broxy.registry.catalog.CatalogRegistryIndex
import io.qent.broxy.registry.catalog.CatalogServerDetail
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

private const val DEFAULT_REPO_OWNER = "qent"
private const val DEFAULT_REPO_NAME = "broxy-registry"
private const val DEFAULT_REPO_BRANCH = "main"
private const val CATALOG_BUNDLE_RESOURCE = "/catalog/catalog_bundle.json"
private const val CATALOG_CACHE_FILE_NAME = "catalog_bundle.json"
private const val CONNECT_TIMEOUT_MS = 5_000
private const val READ_TIMEOUT_MS = 15_000

class GithubCatalogRepository(
    private val cacheDir: Path,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val owner: String = DEFAULT_REPO_OWNER,
    private val repo: String = DEFAULT_REPO_NAME,
    private val branch: String = DEFAULT_REPO_BRANCH,
    private val rawBaseUrl: String = "https://raw.githubusercontent.com/$owner/$repo/$branch",
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        },
) : CatalogRepository {
    private val lock = Any()
    private val cacheFile = cacheDir.resolve(CATALOG_CACHE_FILE_NAME)
    private val indexUrl = "$rawBaseUrl/index.json"

    override suspend fun loadCatalog(): Result<CatalogBundle> =
        runCatching {
            synchronized(lock) {
                loadFromCache() ?: loadFromBundledResource() ?: CatalogBundle()
            }
        }

    override suspend fun refreshCatalog(): Result<CatalogBundle?> =
        runCatching {
            synchronized(lock) {
                val metadata = fetchIndexMetadata()
                val cached = loadFromCache()
                val shouldDownload =
                    cached == null ||
                        metadata.lastModifiedEpochMillis == null ||
                        cached.updatedAtEpochMillis == null ||
                        cached.updatedAtEpochMillis != metadata.lastModifiedEpochMillis
                if (!shouldDownload) {
                    return@runCatching null
                }
                val remote = fetchRemoteBundle(metadata.lastModifiedEpochMillis)
                saveToCache(remote)
                remote
            }
        }

    private fun loadFromBundledResource(): CatalogBundle? {
        val raw =
            CatalogBundleResourceMarker::class.java
                .getResourceAsStream(CATALOG_BUNDLE_RESOURCE)
                ?.use { stream -> stream.readBytes().toString(Charsets.UTF_8) }
                ?: return null
        return runCatching { json.decodeFromString(CatalogBundle.serializer(), raw) }.getOrNull()
    }

    private fun loadFromCache(): CatalogBundle? {
        if (!cacheFile.exists() || !cacheFile.isRegularFile()) return null
        val raw = runCatching { Files.readString(cacheFile) }.getOrNull() ?: return null
        return runCatching { json.decodeFromString(CatalogBundle.serializer(), raw) }.getOrNull()
    }

    private fun fetchRemoteBundle(updatedAtEpochMillis: Long?): CatalogBundle {
        val indexRaw = fetchText(indexUrl)
        val index = json.decodeFromString(CatalogRegistryIndex.serializer(), indexRaw)
        val servers =
            index.servers.map { ref ->
                val path = ref.path.trim().removePrefix("/")
                if (path.isEmpty()) {
                    error("Catalog server path for '${ref.id}' cannot be blank")
                }
                val raw = fetchText("$rawBaseUrl/$path")
                json.decodeFromString(CatalogServerDetail.serializer(), raw)
            }
        return CatalogBundle(
            source = "$owner/$repo@$branch",
            updatedAtEpochMillis = updatedAtEpochMillis ?: now(),
            servers = servers,
        )
    }

    private fun fetchIndexMetadata(): CatalogIndexMetadata =
        withConnection(indexUrl, requestMethod = "HEAD") { connection ->
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                error("HEAD $indexUrl failed with HTTP $responseCode")
            }
            CatalogIndexMetadata(
                lastModifiedEpochMillis =
                    connection
                        .getHeaderFieldDate("Last-Modified", -1L)
                        .takeIf { it > 0L },
            )
        }

    private fun fetchText(url: String): String =
        withConnection(url, requestMethod = "GET") { connection ->
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                error("GET $url failed with HTTP $responseCode")
            }
            connection.inputStream.bufferedReader().use { reader -> reader.readText() }
        }

    private fun <T> withConnection(
        url: String,
        requestMethod: String,
        block: (HttpURLConnection) -> T,
    ): T {
        val connection =
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                this.requestMethod = requestMethod
                setRequestProperty("Accept", "application/json")
                instanceFollowRedirects = true
            }
        return try {
            block(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun saveToCache(bundle: CatalogBundle) {
        cacheDir.createDirectories()
        val payload = json.encodeToString(CatalogBundle.serializer(), bundle)
        val tempFile = cacheFile.resolveSibling("${cacheFile.fileName}.tmp")
        Files.writeString(tempFile, payload, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        try {
            Files.move(tempFile, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(tempFile, cacheFile, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

private object CatalogBundleResourceMarker

private data class CatalogIndexMetadata(
    val lastModifiedEpochMillis: Long?,
)
