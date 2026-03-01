package io.qent.broxy.ui.adapter.icons

import java.awt.FileDialog
import java.awt.Frame
import java.io.FilenameFilter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import kotlin.io.path.name

class ServerIconRepositoryJvm(
    private val baseDir: Path = defaultConfigDir(),
) : ServerIconRepository {
    private val iconsDir = baseDir.resolve("icons")
    private val allowedExtensions = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "tif", "tiff")

    override suspend fun pickAndImportIcon(): Result<String?> =
        runCatching {
            val selected = pickImageFile() ?: return@runCatching null
            Files.createDirectories(iconsDir)
            val extension = selected.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val normalizedExtension = if (extension.isBlank()) "" else ".$extension"
            val fileName = "server-icon-${UUID.randomUUID()}$normalizedExtension"
            val target = iconsDir.resolve(fileName)
            Files.copy(selected, target, StandardCopyOption.REPLACE_EXISTING)
            "icons/$fileName"
        }

    override suspend fun deleteIcon(iconPath: String): Result<Unit> =
        runCatching {
            val resolved = resolveIconPath(iconPath) ?: return@runCatching
            Files.deleteIfExists(resolved)
        }

    private fun pickImageFile(): Path? {
        val dialog = FileDialog(null as Frame?, "Select server icon", FileDialog.LOAD)
        dialog.filenameFilter =
            FilenameFilter { _, name ->
                val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                extension in allowedExtensions
            }
        dialog.isVisible = true
        val file = dialog.file ?: return null
        val dir = dialog.directory ?: return null
        return Paths.get(dir, file)
    }

    private fun resolveIconPath(iconPath: String): Path? {
        val trimmed = iconPath.trim()
        if (trimmed.isEmpty()) return null
        val path = Paths.get(trimmed)
        val resolved = if (path.isAbsolute) path else baseDir.resolve(path)
        val normalized = resolved.normalize()
        val normalizedIcons = iconsDir.normalize()
        return if (normalized.startsWith(normalizedIcons)) normalized else null
    }
}

private fun defaultConfigDir(): Path = Paths.get(System.getProperty("user.home"), ".config", "broxy")
