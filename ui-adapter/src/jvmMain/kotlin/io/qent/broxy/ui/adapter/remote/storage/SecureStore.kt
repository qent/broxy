package io.qent.broxy.ui.adapter.remote.storage

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.*

interface SecureStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun clear(key: String)
}

class FileSecureStore(private val dir: Path) : SecureStore {
    init {
        runCatching { Files.createDirectories(dir) }
    }

    private fun fileFor(key: String): Path = dir.resolve(key.replace("[^a-zA-Z0-9._-]".toRegex(), "_"))

    override fun read(key: String): String? = runCatching {
        val file = fileFor(key)
        if (!Files.exists(file)) return null
        Files.readString(file)
    }.getOrNull()

    override fun write(key: String, value: String) {
        val file = fileFor(key)
        runCatching { Files.createDirectories(dir) }
        try {
            Files.writeString(
                file,
                value,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            )
            if (Files.getFileAttributeView(file, java.nio.file.attribute.PosixFileAttributeView::class.java) != null) {
                runCatching {
                    Files.setPosixFilePermissions(
                        file,
                        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
                    )
                }
            }
        } catch (_: IOException) {
        }
    }

    override fun clear(key: String) {
        val file = fileFor(key)
        runCatching { Files.deleteIfExists(file) }
    }
}
