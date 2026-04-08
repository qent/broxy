package io.qent.broxy.agents.runtime.filesystem

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal class AgentFileSystemPathMetadata {
    fun comparator(
        sortBy: String,
        descending: Boolean,
    ): Comparator<Path> {
        val comparator =
            when (sortBy) {
                "size" -> compareBy<Path> { size(it) }.thenBy { it.fileName.toString().lowercase() }
                "modified" -> compareBy<Path> { modifiedEpochMillis(it) }.thenBy { it.fileName.toString().lowercase() }
                else -> compareBy<Path> { it.fileName.toString().lowercase() }
            }
        return if (descending) comparator.reversed() else comparator
    }

    fun type(path: Path): String =
        when {
            Files.isSymbolicLink(path) -> "symlink"
            Files.isDirectory(path) -> "directory"
            Files.isRegularFile(path) -> "file"
            else -> "other"
        }

    fun size(path: Path): Long =
        runCatching {
            if (Files.isRegularFile(path)) Files.size(path) else 0L
        }.getOrDefault(0L)

    fun modifiedEpochMillis(path: Path): Long =
        runCatching { Files.getLastModifiedTime(path).toMillis() }
            .getOrDefault(0L)

    fun isHiddenPath(
        path: Path,
        root: Path,
    ): Boolean {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalizedPath = path.toAbsolutePath().normalize()
        if (!normalizedPath.startsWith(normalizedRoot)) {
            return false
        }
        val relative = normalizedRoot.relativize(normalizedPath)
        return relative.any { segment -> segment.toString().startsWith(".") }
    }

    fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(SHA_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        private const val SHA_BUFFER_SIZE = 8_192
    }
}
