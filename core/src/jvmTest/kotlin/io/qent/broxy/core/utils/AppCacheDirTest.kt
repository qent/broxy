package io.qent.broxy.core.utils

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

class AppCacheDirTest {
    @Test
    fun resolve_uses_mac_cache_location() {
        val original = System.getProperty("os.name")
        try {
            System.setProperty("os.name", "Mac OS X")
            val path = AppCacheDir.resolve(appId = "broxy", env = emptyMap(), userHome = "/Users/tester")
            assertEquals(Paths.get("/Users/tester", "Library", "Caches", "broxy"), path)
        } finally {
            if (original != null) {
                System.setProperty("os.name", original)
            }
        }
    }

    @Test
    fun resolve_uses_windows_cache_location() {
        val original = System.getProperty("os.name")
        try {
            System.setProperty("os.name", "Windows 11")
            val path =
                AppCacheDir.resolve(
                    appId = "broxy",
                    env = mapOf("LOCALAPPDATA" to "C:\\Users\\tester\\AppData\\Local"),
                    userHome = "C:\\Users\\tester",
                )
            assertEquals(Paths.get("C:\\Users\\tester\\AppData\\Local", "broxy", "Cache"), path)
        } finally {
            if (original != null) {
                System.setProperty("os.name", original)
            }
        }
    }

    @Test
    fun resolve_uses_xdg_cache_location() {
        val original = System.getProperty("os.name")
        try {
            System.setProperty("os.name", "Linux")
            val path =
                AppCacheDir.resolve(
                    appId = "broxy",
                    env = mapOf("XDG_CACHE_HOME" to "/tmp/cache"),
                    userHome = "/home/tester",
                )
            assertEquals(Paths.get("/tmp/cache", "broxy"), path)
        } finally {
            if (original != null) {
                System.setProperty("os.name", original)
            }
        }
    }
}
