package io.qent.broxy.ui.adapter.icons

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteServerIconCacheTest {
    @Test
    fun loadsAndCachesRemoteIcon() {
        val cacheDir = Files.createTempDirectory("broxy-remote-icon-cache")
        try {
            var downloadCalls = 0
            val expected = byteArrayOf(1, 2, 3, 4)
            val cache =
                RemoteServerIconCache(cacheDir) {
                    downloadCalls += 1
                    expected
                }
            val url = "https://example.com/icons/remote.png"

            val first = cache.load(url)
            val second = cache.load(url)

            assertContentEquals(expected, first)
            assertContentEquals(expected, second)
            assertEquals(1, downloadCalls)
            assertEquals(1, countRegularFiles(cacheDir))
        } finally {
            cacheDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun fallsBackToCachedIconWhenDownloadFails() {
        val cacheDir = Files.createTempDirectory("broxy-remote-icon-cache")
        try {
            val url = "https://example.com/icons/remote.webp"
            val cached = byteArrayOf(9, 8, 7)
            val primingCache = RemoteServerIconCache(cacheDir) { cached }
            assertContentEquals(cached, primingCache.load(url))

            var downloadCalls = 0
            val failingCache =
                RemoteServerIconCache(cacheDir) {
                    downloadCalls += 1
                    null
                }

            val loaded = failingCache.load(url)

            assertContentEquals(cached, loaded)
            assertEquals(0, downloadCalls)
        } finally {
            cacheDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun buildsStableCachePathForHttpUrlsOnly() {
        val cacheDir = Files.createTempDirectory("broxy-remote-icon-cache")
        try {
            val cache = RemoteServerIconCache(cacheDir) { null }
            val pngPath = cache.cacheFileFor("https://example.com/assets/icon.PNG?size=64")
            val noExtPath = cache.cacheFileFor("https://example.com/assets/icon")
            val filePath = cache.cacheFileFor("file:///tmp/icon.png")

            val resolvedPngPath = assertNotNull(pngPath)
            val resolvedNoExtPath = assertNotNull(noExtPath)
            val hashedName = resolvedPngPath.fileName.toString().substringBefore('.')
            assertTrue(resolvedPngPath.fileName.toString().endsWith(".png"))
            assertTrue(resolvedNoExtPath.fileName.toString().endsWith(".img"))
            assertEquals(64, hashedName.length)
            assertTrue(hashedName.all { it.isDigit() || it in 'a'..'f' })
            assertNull(filePath)
        } finally {
            cacheDir.toFile().deleteRecursively()
        }
    }
}

private fun countRegularFiles(dir: Path): Int {
    var count = 0
    Files.newDirectoryStream(dir).use { stream ->
        for (entry in stream) {
            if (Files.isRegularFile(entry)) {
                count += 1
            }
        }
    }
    return count
}
