package io.qent.broxy.ui.adapter.icons

import io.qent.broxy.ui.adapter.data.FilePickRequest
import io.qent.broxy.ui.adapter.data.SystemPicker
import io.qent.broxy.ui.adapter.data.defaultConfigDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import kotlin.io.path.name

class ServerIconRepositoryJvm(
    private val systemPicker: SystemPicker,
    private val baseDir: Path = defaultConfigDir(),
) : ServerIconRepository {
    private val iconsDir = baseDir.resolve("icons")
    private val allowedExtensions = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "tif", "tiff")

    override suspend fun pickAndImportIcon(): Result<String?> =
        systemPicker
            .pickFile(
                FilePickRequest(
                    title = "Select server icon",
                    initialPath = iconsDir.toString(),
                    allowedExtensions = allowedExtensions,
                ),
            ).mapCatching { selectedPath ->
                val selected = selectedPath?.let(Paths::get) ?: return@mapCatching null
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
