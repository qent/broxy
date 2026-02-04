package io.qent.broxy.core.utils

import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale

object AppCacheDir {
    fun resolve(
        appId: String = "broxy",
        env: Map<String, String> = System.getenv(),
        userHome: String = System.getProperty("user.home") ?: "",
    ): Path {
        val osName = System.getProperty("os.name")?.lowercase(Locale.ROOT) ?: ""
        return when {
            osName.contains("mac") -> Paths.get(userHome, "Library", "Caches", appId)
            osName.contains("win") -> {
                val base = env["LOCALAPPDATA"] ?: env["APPDATA"] ?: userHome
                Paths.get(base, appId, "Cache")
            }
            else -> {
                val base = env["XDG_CACHE_HOME"] ?: Paths.get(userHome, ".cache").toString()
                Paths.get(base, appId)
            }
        }
    }
}
