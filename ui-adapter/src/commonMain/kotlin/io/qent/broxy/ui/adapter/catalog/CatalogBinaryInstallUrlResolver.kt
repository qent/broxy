package io.qent.broxy.ui.adapter.catalog

import kotlinx.serialization.json.Json

object CatalogBinaryInstallUrlResolver {
    private const val URLS_RESOURCE_PATH = "/catalog_binary_install_urls.json"

    private val json = Json { ignoreUnknownKeys = true }
    private val urlsByBinary: Map<String, String> by lazy { loadUrlsByBinary() }

    fun resolve(binaryName: String?): String? {
        val binaryKey = normalizeBinaryKey(binaryName) ?: return null
        return urlsByBinary[binaryKey]
    }

    internal fun normalizeBinaryKey(binaryName: String?): String? {
        val trimmed = binaryName?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return null
        }
        return trimmed.lowercase()
    }

    private fun loadUrlsByBinary(): Map<String, String> {
        val rawMappingsJson =
            CatalogBinaryInstallUrlResourceMarker::class.java
                .getResourceAsStream(URLS_RESOURCE_PATH)
                ?.use { stream -> stream.readBytes().toString(Charsets.UTF_8) }
                ?: return emptyMap()
        val rawMappings = runCatching { json.decodeFromString<Map<String, String>>(rawMappingsJson) }.getOrNull() ?: return emptyMap()

        val normalizedMappings = linkedMapOf<String, String>()
        rawMappings.forEach { (rawBinaryName, rawUrl) ->
            val binaryKey = normalizeBinaryKey(rawBinaryName) ?: return@forEach
            val url = rawUrl.trim().takeIf { it.isNotEmpty() } ?: return@forEach
            normalizedMappings.putIfAbsent(binaryKey, url)
        }
        return normalizedMappings
    }
}

private object CatalogBinaryInstallUrlResourceMarker
